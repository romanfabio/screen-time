package io.github.romanfabio.screentime.domain

import androidx.room.withTransaction
import io.github.romanfabio.screentime.data.db.AppDatabase
import io.github.romanfabio.screentime.data.db.AppSessionEntity
import io.github.romanfabio.screentime.data.db.DailyDeviceEntity
import io.github.romanfabio.screentime.data.db.DeviceIntervalEntity
import io.github.romanfabio.screentime.data.db.HourlyAppUsageEntity
import io.github.romanfabio.screentime.data.db.NotificationEventEntity
import io.github.romanfabio.screentime.data.db.SyncStateEntity
import io.github.romanfabio.screentime.data.db.UnlockEventEntity
import io.github.romanfabio.screentime.data.source.AppInfoResolver
import io.github.romanfabio.screentime.data.source.UsageEventProvider
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class IngestSummary(
    val events: Int,
    val sessions: Int,
    val intervals: Int,
    val unlocks: Int,
    val notifications: Int,
    val fromDate: LocalDate,
    val toDate: LocalDate,
)

/**
 * Reads new events, turns them into rows and refreshes the aggregates.
 *
 * Idempotent: everything at or after the stored cursor is deleted and rewritten, and the cursor only moves
 * to a point from which the Sessionizer can restart without losing context (see docs/ARCHITECTURE.md).
 */
class IngestUseCase(
    private val db: AppDatabase,
    private val events: UsageEventProvider,
    private val appInfo: AppInfoResolver? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val sessionizer: Sessionizer = Sessionizer(),
) {
    private val dao = db.usageDao()

    /** Serializes runs from the worker and from the UI, which share one process. */
    private val mutex = Mutex()

    suspend fun run(): IngestSummary = mutex.withLock { runLocked() }

    private suspend fun runLocked(): IngestSummary {
        val now = clock()
        val zoneId = zone()
        // After a change in how events are interpreted, re-read everything Android still has. Rows older than
        // the oldest available event are kept as they are: Android no longer has the events to re-derive them.
        val reprocess = dao.getState(SESSIONIZER_VERSION_KEY) != SESSIONIZER_VERSION
        val storedCursor = dao.getState(CURSOR)?.toLongOrNull() ?: 0L
        val cursor = if (reprocess) 0L else storedCursor
        val keyguard = if (reprocess) true else dao.getState(KEYGUARD)?.toBooleanStrictOrNull() ?: true
        val resumed = if (reprocess) emptyList() else dao.getState(RESUMED)?.lines()?.filter { it.isNotEmpty() }.orEmpty()

        val raw = events.queryEvents(cursor, now)
        val result = sessionizer.process(raw, now, keyguard, resumed)
        // Rows to replace: everything from the stored cursor, or from the first event we got when reprocessing.
        val deleteFrom = if (reprocess) (raw.firstOrNull()?.timeMs ?: now) else cursor

        return db.withTransaction {
            dao.deleteSessionsFrom(deleteFrom)
            dao.deleteIntervalsFrom(deleteFrom)
            dao.deleteUnlocksFrom(deleteFrom)
            dao.deleteNotificationsFrom(deleteFrom)

            dao.insertSessions(result.appSessions.map { AppSessionEntity(0, it.packageName, it.startMs, it.endMs) })
            dao.insertIntervals(result.deviceIntervals.map { DeviceIntervalEntity(0, it.kind.name, it.startMs, it.endMs) })
            dao.insertUnlocks(result.unlocks.map { UnlockEventEntity(it) })
            dao.insertNotifications(result.notifications.map { NotificationEventEntity(it.packageName, it.timeMs) })

            // Dates to recompute: from the earliest thing we just wrote (or today) through today.
            val earliest = listOfNotNull(
                result.appSessions.minOfOrNull { it.startMs },
                result.deviceIntervals.minOfOrNull { it.startMs },
                result.unlocks.minOrNull(),
                result.notifications.minOfOrNull { it.timeMs },
            ).minOrNull() ?: now
            // After a change in how aggregates are computed, redo every day from the first stored row.
            val rebuild = reprocess || dao.getState(AGGREGATE_VERSION_KEY) != AGGREGATE_VERSION
            val earliestToUse = if (rebuild) listOfNotNull(dao.earliestIntervalStart(), dao.earliestSessionStart(), earliest)
                .min() else earliest
            val fromDate = Instant.ofEpochMilli(earliestToUse).atZone(zoneId).toLocalDate()
            val toDate = Instant.ofEpochMilli(now).atZone(zoneId).toLocalDate()
            val rangeStart = fromDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val rangeEnd = toDate.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()

            val agg = Aggregator.aggregate(
                sessions = dao.sessionsOverlapping(rangeStart, rangeEnd).map { AppSession(it.packageName, it.startMs, it.endMs) },
                intervals = dao.intervalsOverlapping(rangeStart, rangeEnd)
                    .map { DeviceInterval(IntervalKind.valueOf(it.kind), it.startMs, it.endMs) },
                unlocks = dao.unlocksBetween(rangeStart, rangeEnd).map { it.timeMs },
                notifications = dao.notificationsBetween(rangeStart, rangeEnd).map { NotificationEvent(it.packageName, it.timeMs) },
                zone = zoneId,
                from = fromDate,
                to = toDate,
            )
            dao.deleteHourlyBetween(fromDate.toString(), toDate.toString())
            dao.deleteDailyBetween(fromDate.toString(), toDate.toString())
            dao.insertHourly(
                agg.hourly.map {
                    HourlyAppUsageEntity(it.date.toString(), it.hour, it.packageName, it.foregroundMs, it.opens, it.notifications)
                },
            )
            dao.insertDaily(
                agg.daily.map {
                    DailyDeviceEntity(it.date.toString(), it.screenOnMs, it.unlockedMs, it.unlocks, it.firstPickupMs, it.lastUseMs)
                },
            )

            if (appInfo != null) {
                // The cache is derived data: re-resolve everything when the classification rules change.
                if (dao.getState(APP_INFO_VERSION_KEY) != APP_INFO_VERSION) {
                    dao.clearAppInfo()
                    dao.putState(SyncStateEntity(APP_INFO_VERSION_KEY, APP_INFO_VERSION))
                }
                // Also covers packages whose cached info was dropped by a schema migration.
                val seen = (result.appSessions.map { it.packageName } + result.notifications.map { it.packageName } +
                    dao.usedPackages()).toSet()
                val missing = seen - dao.knownPackages().toSet()
                if (missing.isNotEmpty()) dao.upsertAppInfo(appInfo.resolve(missing, now))
            }

            dao.putState(SyncStateEntity(CURSOR, result.resume.fromMs.toString()))
            dao.putState(SyncStateEntity(KEYGUARD, result.resume.keyguardShowing.toString()))
            dao.putState(SyncStateEntity(RESUMED, result.resume.resumedActivities.joinToString(RESUMED_SEPARATOR)))
            dao.putState(SyncStateEntity(SESSIONIZER_VERSION_KEY, SESSIONIZER_VERSION))
            dao.putState(SyncStateEntity(LAST_SYNC, now.toString()))
            dao.putState(SyncStateEntity(AGGREGATE_VERSION_KEY, AGGREGATE_VERSION))

            IngestSummary(
                events = raw.size,
                sessions = result.appSessions.size,
                intervals = result.deviceIntervals.size,
                unlocks = result.unlocks.size,
                notifications = result.notifications.size,
                fromDate = fromDate,
                toDate = toDate,
            )
        }
    }

    companion object {
        const val CURSOR = "cursor_ms"
        const val KEYGUARD = "keyguard_showing"
        const val APP_INFO_VERSION_KEY = "app_info_version"
        const val APP_INFO_VERSION = "2" // 2: FallbackHome no longer counts as a launcher
        const val RESUMED = "resumed_activities"
        private const val RESUMED_SEPARATOR = "\n"
        const val SESSIONIZER_VERSION_KEY = "sessionizer_version"

        /** Bump when [Sessionizer] rules change; the next run re-reads all events Android still has. */
        const val SESSIONIZER_VERSION = "5"
        const val LAST_SYNC = "last_sync_ms"
        const val AGGREGATE_VERSION_KEY = "aggregate_version"

        /** Bump when [Aggregator] semantics change; the next run then rebuilds all days from the raw rows. */
        const val AGGREGATE_VERSION = "2"
    }
}
