package io.github.romanfabio.screentime.data.repo

import androidx.room.InvalidationTracker
import io.github.romanfabio.screentime.data.AppPrefs
import io.github.romanfabio.screentime.data.db.AppDatabase
import io.github.romanfabio.screentime.data.db.HourlyAppUsageEntity
import io.github.romanfabio.screentime.domain.AppDetailBuilder
import io.github.romanfabio.screentime.domain.AppDetailData
import io.github.romanfabio.screentime.domain.AppMeta
import io.github.romanfabio.screentime.domain.Exporter
import io.github.romanfabio.screentime.domain.IngestUseCase
import io.github.romanfabio.screentime.domain.DailyRow
import io.github.romanfabio.screentime.domain.DashboardBuilder
import io.github.romanfabio.screentime.domain.DashboardData
import io.github.romanfabio.screentime.domain.DashboardRange
import io.github.romanfabio.screentime.domain.HourlyRow
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

class DashboardRepository(
    private val db: AppDatabase,
    private val prefs: AppPrefs,
    private val ownPackage: String,
) {
    /** Emits whenever the aggregates change (e.g. after a sync), and once on collection. */
    fun changes(): Flow<Unit> = merge(
        db.invalidationTracker.createFlow("daily_device", "hourly_app_usage", "app_info").map { },
        prefs.showHiddenAppsFlow.map { },
    )

    suspend fun loadAppDetail(packageName: String, range: DashboardRange, endDate: LocalDate): AppDetailData {
        val dao = db.usageDao()
        val start = DashboardBuilder.periodStart(range, endDate)
        val from = DashboardBuilder.periodStart(range, start.minusDays(1)).toString()
        val rows = dao.hourlyForPackage(packageName, from, endDate.toString()).map(::toRow)
        val label = dao.allAppInfo().firstOrNull { it.packageName == packageName }?.label ?: packageName
        val history = dao.earliestDailyDate()?.let(LocalDate::parse)
        return AppDetailBuilder.build(packageName, label, range, endDate, rows, history)
    }

    suspend fun exportCsv(): String {
        val dao = db.usageDao()
        return Exporter.csv(dao.hourlyBetween(ALL_FROM, ALL_TO).map(::toRow), appMeta())
    }

    suspend fun exportJson(nowMs: Long): String {
        val dao = db.usageDao()
        val daily = dao.dailyBetween(ALL_FROM, ALL_TO).map {
            DailyRow(LocalDate.parse(it.date), it.screenOnMs, it.unlockedMs, it.unlocks, it.firstPickupMs, it.lastUseMs)
        }
        return Exporter.json(daily, dao.hourlyBetween(ALL_FROM, ALL_TO).map(::toRow), appMeta(), nowMs)
    }

    suspend fun lastSyncMs(): Long? = db.usageDao().getState(IngestUseCase.LAST_SYNC)?.toLongOrNull()

    private suspend fun appMeta(): Map<String, AppMeta> = db.usageDao().allAppInfo().associate {
        it.packageName to AppMeta(it.label, it.isSystem, it.isLauncher, it.hasLauncherIcon)
    }

    private fun toRow(it: HourlyAppUsageEntity) =
        HourlyRow(LocalDate.parse(it.date), it.hour, it.packageName, it.foregroundMs, it.opens, it.notifications)

    suspend fun load(range: DashboardRange, endDate: LocalDate): DashboardData {
        val dao = db.usageDao()
        val start = DashboardBuilder.periodStart(range, endDate)
        val from = DashboardBuilder.periodStart(range, start.minusDays(1)).toString() // includes the previous period
        val to = endDate.toString()

        val daily = dao.dailyBetween(from, to).map {
            DailyRow(LocalDate.parse(it.date), it.screenOnMs, it.unlockedMs, it.unlocks, it.firstPickupMs, it.lastUseMs)
        }
        val hourly = dao.hourlyBetween(from, to).map {
            HourlyRow(LocalDate.parse(it.date), it.hour, it.packageName, it.foregroundMs, it.opens, it.notifications)
        }
        val apps = dao.allAppInfo().associate {
            it.packageName to AppMeta(it.label, it.isSystem, it.isLauncher, it.hasLauncherIcon)
        }
        return DashboardBuilder.build(range, endDate, daily, hourly, apps, ownPackage, prefs.showHiddenApps)
    }

    private companion object {
        const val ALL_FROM = "0000-00-00"
        const val ALL_TO = "9999-99-99"
    }
}
