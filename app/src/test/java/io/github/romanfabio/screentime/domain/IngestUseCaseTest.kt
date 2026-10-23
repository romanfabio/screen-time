package io.github.romanfabio.screentime.domain

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.romanfabio.screentime.data.db.AppDatabase
import io.github.romanfabio.screentime.data.db.AppInfoEntity
import io.github.romanfabio.screentime.data.source.AppInfoResolver
import io.github.romanfabio.screentime.data.source.RawEvent
import io.github.romanfabio.screentime.data.source.UsageEventProvider
import io.github.romanfabio.screentime.domain.EventType.ACTIVITY_PAUSED
import io.github.romanfabio.screentime.domain.EventType.ACTIVITY_RESUMED
import io.github.romanfabio.screentime.domain.EventType.KEYGUARD_HIDDEN
import io.github.romanfabio.screentime.domain.EventType.NOTIFICATION_INTERRUPTION
import io.github.romanfabio.screentime.domain.EventType.SCREEN_INTERACTIVE
import io.github.romanfabio.screentime.domain.EventType.SCREEN_NON_INTERACTIVE
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private val UTC = ZoneId.of("UTC")
private const val MIN = 60_000L

private fun t(day: Int, h: Int, m: Int = 0, s: Int = 0) =
    ZonedDateTime.of(2026, 3, day, h, m, s, 0, UTC).toInstant().toEpochMilli()

private class FakeEvents : UsageEventProvider {
    val events = mutableListOf<RawEvent>()
    override fun queryEvents(fromMs: Long, toMs: Long) = events.filter { it.timeMs in fromMs until toMs }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class IngestUseCaseTest {
    private lateinit var db: AppDatabase
    private val source = FakeEvents()
    private var now = t(10, 12)
    private lateinit var ingest: IngestUseCase

    @BeforeTest
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val resolver = AppInfoResolver { pkgs, ts -> pkgs.map { AppInfoEntity(it, "label-$it", false, false, ts) } }
        ingest = IngestUseCase(db, source, resolver, clock = { now }, zone = { UTC })
    }

    @AfterTest
    fun tearDown() = db.close()

    private fun morning() = listOf(
        RawEvent(t(10, 8), SCREEN_INTERACTIVE, null),
        RawEvent(t(10, 8, 0, 5), KEYGUARD_HIDDEN, null),
        RawEvent(t(10, 8, 0, 6), ACTIVITY_RESUMED, "a"),
        RawEvent(t(10, 8, 10), NOTIFICATION_INTERRUPTION, "n"),
        RawEvent(t(10, 8, 30), ACTIVITY_PAUSED, "a"),
        RawEvent(t(10, 8, 30, 1), SCREEN_NON_INTERACTIVE, null),
    )

    @Test
    fun `first run stores sessions, aggregates and app info`() = runBlocking<Unit> {
        source.events += morning()
        val s = ingest.run()
        assertEquals(6, s.events)

        val dao = db.usageDao()
        val day = dao.dailyBetween("2026-03-10", "2026-03-10").single()
        assertEquals(30 * MIN - 5_000 + 1_000, day.unlockedMs) // 08:00:05 .. 08:30:01
        assertEquals(1, day.unlocks)
        val app = dao.appTotalsBetween("2026-03-10", "2026-03-10").single { it.packageName == "a" }
        assertEquals(30 * MIN - 6_000, app.foregroundMs)
        assertEquals(1, app.opens)
        assertEquals(1, dao.appTotalsBetween("2026-03-10", "2026-03-10").single { it.packageName == "n" }.notifications)
        assertNotNull(dao.allAppInfo().find { it.packageName == "a" })
    }

    @Test
    fun `running twice without new events changes nothing`() = runBlocking<Unit> {
        source.events += morning()
        ingest.run()
        val dao = db.usageDao()
        val before = dao.dailyBetween("0", "z") to dao.hourlyBetween("0", "z")
        now += 5 * MIN
        ingest.run()
        assertEquals(before, dao.dailyBetween("0", "z") to dao.hourlyBetween("0", "z"))
    }

    @Test
    fun `open session grows between runs and is not double counted`() = runBlocking<Unit> {
        source.events += listOf(
            RawEvent(t(10, 11), SCREEN_INTERACTIVE, null),
            RawEvent(t(10, 11, 0, 1), KEYGUARD_HIDDEN, null),
            RawEvent(t(10, 11, 0, 2), ACTIVITY_RESUMED, "a"),
        )
        now = t(10, 11, 10)
        ingest.run()
        val dao = db.usageDao()
        assertEquals(10 * MIN - 2_000, dao.appTotalsBetween("2026-03-10", "2026-03-10").single().foregroundMs)

        now = t(10, 11, 30)
        source.events += listOf(RawEvent(t(10, 11, 20), ACTIVITY_PAUSED, "a"), RawEvent(t(10, 11, 20, 1), SCREEN_NON_INTERACTIVE, null))
        ingest.run()
        val app = dao.appTotalsBetween("2026-03-10", "2026-03-10").single()
        assertEquals(20 * MIN - 2_000, app.foregroundMs)
        assertEquals(1, app.opens)
        assertEquals(1, dao.dailyBetween("2026-03-10", "2026-03-10").single().unlocks)
    }

    @Test
    fun `usage after midnight lands on the next day`() = runBlocking<Unit> {
        source.events += listOf(
            RawEvent(t(10, 23, 50), SCREEN_INTERACTIVE, null),
            RawEvent(t(10, 23, 50, 1), KEYGUARD_HIDDEN, null),
            RawEvent(t(10, 23, 50, 2), ACTIVITY_RESUMED, "a"),
            RawEvent(t(11, 0, 20), ACTIVITY_PAUSED, "a"),
            RawEvent(t(11, 0, 20, 1), SCREEN_NON_INTERACTIVE, null),
        )
        now = t(11, 1)
        ingest.run()
        val days = db.usageDao().dailyBetween("2026-03-10", "2026-03-11").associateBy { it.date }
        assertEquals(10 * MIN - 1_000, days.getValue("2026-03-10").unlockedMs)
        assertEquals(20 * MIN + 1_000, days.getValue("2026-03-11").unlockedMs)
    }
}
