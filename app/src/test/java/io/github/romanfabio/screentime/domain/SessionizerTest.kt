package io.github.romanfabio.screentime.domain

import io.github.romanfabio.screentime.data.source.RawEvent
import io.github.romanfabio.screentime.domain.EventType.ACTIVITY_PAUSED
import io.github.romanfabio.screentime.domain.EventType.ACTIVITY_RESUMED
import io.github.romanfabio.screentime.domain.EventType.ACTIVITY_STOPPED
import io.github.romanfabio.screentime.domain.EventType.DEVICE_SHUTDOWN
import io.github.romanfabio.screentime.domain.EventType.KEYGUARD_HIDDEN
import io.github.romanfabio.screentime.domain.EventType.KEYGUARD_SHOWN
import io.github.romanfabio.screentime.domain.EventType.NOTIFICATION_INTERRUPTION
import io.github.romanfabio.screentime.domain.EventType.SCREEN_INTERACTIVE
import io.github.romanfabio.screentime.domain.EventType.SCREEN_NON_INTERACTIVE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun s(seconds: Long) = seconds * 1000L
private fun ev(sec: Long, type: Int, pkg: String? = null, cls: String? = null) = RawEvent(s(sec), type, pkg, cls)

class SessionizerTest {
    private val sessionizer = Sessionizer()

    private fun run(events: List<RawEvent>, endSec: Long = 10_000, keyguard: Boolean = true) =
        sessionizer.process(events, s(endSec), keyguardShowing = keyguard)

    @Test
    fun `unlock then use one app`() {
        val r = run(
            listOf(
                ev(0, SCREEN_INTERACTIVE),
                ev(10, KEYGUARD_HIDDEN),
                ev(11, ACTIVITY_RESUMED, "a"),
                ev(100, ACTIVITY_PAUSED, "a"),
                ev(110, SCREEN_NON_INTERACTIVE),
            ),
        )
        assertEquals(listOf(AppSession("a", s(11), s(100))), r.appSessions)
        assertEquals(
            listOf(DeviceInterval(IntervalKind.SCREEN_ON, 0, s(110)), DeviceInterval(IntervalKind.UNLOCKED, s(10), s(110))),
            r.deviceIntervals.sortedBy { it.kind },
        )
        assertEquals(listOf(s(10)), r.unlocks)
    }

    @Test
    fun `switching apps closes the previous session`() {
        val r = run(
            listOf(
                ev(0, SCREEN_INTERACTIVE), ev(1, KEYGUARD_HIDDEN),
                ev(2, ACTIVITY_RESUMED, "a"),
                ev(50, ACTIVITY_RESUMED, "b"),
                ev(51, ACTIVITY_PAUSED, "a"), // arrives after b resumed: must not touch b
                ev(80, ACTIVITY_STOPPED, "b"),
                ev(90, SCREEN_NON_INTERACTIVE),
            ),
        )
        assertEquals(listOf(AppSession("a", s(2), s(50)), AppSession("b", s(50), s(80))), r.appSessions)
    }

    @Test
    fun `locking the phone ends the session and relocking resumes it`() {
        val r = run(
            listOf(
                ev(0, SCREEN_INTERACTIVE), ev(1, KEYGUARD_HIDDEN), ev(2, ACTIVITY_RESUMED, "a"),
                ev(60, KEYGUARD_SHOWN),
                ev(100, KEYGUARD_HIDDEN),
                ev(160, SCREEN_NON_INTERACTIVE),
            ),
        )
        // Foreground app is still "a" after the second unlock, so a second session starts at the unlock.
        assertEquals(listOf(AppSession("a", s(2), s(60)), AppSession("a", s(100), s(160))), r.appSessions)
        assertEquals(listOf(s(1), s(100)), r.unlocks)
    }

    @Test
    fun `activity resumed while screen is off is not counted`() {
        val r = run(
            listOf(
                ev(0, ACTIVITY_RESUMED, "music"),
                ev(5, ACTIVITY_PAUSED, "music"),
            ),
        )
        assertTrue(r.appSessions.isEmpty())
        assertTrue(r.deviceIntervals.isEmpty())
    }

    @Test
    fun `app resumed on lock screen only counts after unlock`() {
        val r = run(
            listOf(
                ev(0, SCREEN_INTERACTIVE),
                ev(1, ACTIVITY_RESUMED, "camera"),
                ev(30, KEYGUARD_HIDDEN),
                ev(90, SCREEN_NON_INTERACTIVE),
            ),
        )
        assertEquals(listOf(AppSession("camera", s(30), s(90))), r.appSessions)
    }

    @Test
    fun `screen on without a keyguard counts as unlocked but not as an unlock`() {
        val r = run(
            listOf(ev(0, SCREEN_INTERACTIVE), ev(1, ACTIVITY_RESUMED, "a"), ev(50, SCREEN_NON_INTERACTIVE)),
            keyguard = false,
        )
        assertEquals(listOf(AppSession("a", s(1), s(50))), r.appSessions)
        assertTrue(r.unlocks.isEmpty())
        assertEquals(s(50), r.deviceIntervals.single { it.kind == IntervalKind.UNLOCKED }.endMs)
    }

    @Test
    fun `quick screen off and on without keyguard stays unlocked`() {
        val r = run(
            listOf(
                ev(0, SCREEN_INTERACTIVE), ev(1, KEYGUARD_HIDDEN),
                ev(2, SCREEN_NON_INTERACTIVE),
                ev(10, SCREEN_INTERACTIVE), // keyguard never shown again
                ev(20, SCREEN_NON_INTERACTIVE),
            ),
        )
        assertEquals(2, r.deviceIntervals.count { it.kind == IntervalKind.UNLOCKED })
        assertEquals(listOf(s(1)), r.unlocks)
    }

    @Test
    fun `sessions of the same app closer than 5 seconds merge`() {
        val r = run(
            listOf(
                ev(0, SCREEN_INTERACTIVE), ev(1, KEYGUARD_HIDDEN),
                ev(2, ACTIVITY_RESUMED, "a"), ev(20, ACTIVITY_PAUSED, "a"),
                ev(22, ACTIVITY_RESUMED, "a"), ev(40, ACTIVITY_PAUSED, "a"), // 2 s gap: merged
                ev(50, ACTIVITY_RESUMED, "a"), ev(60, ACTIVITY_PAUSED, "a"), // 10 s gap: separate
                ev(70, SCREEN_NON_INTERACTIVE),
            ),
        )
        assertEquals(listOf(AppSession("a", s(2), s(40)), AppSession("a", s(50), s(60))), r.appSessions)
    }

    @Test
    fun `screen off closes sessions even without pause event`() {
        val r = run(
            listOf(
                ev(0, SCREEN_INTERACTIVE), ev(1, KEYGUARD_HIDDEN), ev(2, ACTIVITY_RESUMED, "a"),
                ev(30, SCREEN_NON_INTERACTIVE),
            ),
        )
        assertEquals(listOf(AppSession("a", s(2), s(30))), r.appSessions)
    }

    @Test
    fun `shutdown closes everything and resets keyguard`() {
        val r = run(
            listOf(
                ev(0, SCREEN_INTERACTIVE), ev(1, KEYGUARD_HIDDEN), ev(2, ACTIVITY_RESUMED, "a"),
                ev(30, DEVICE_SHUTDOWN),
                ev(500, SCREEN_INTERACTIVE),
            ),
            endSec = 600,
        )
        assertEquals(listOf(AppSession("a", s(2), s(30))), r.appSessions)
        // After boot the keyguard is showing, so the new screen-on period is not unlocked.
        assertEquals(1, r.deviceIntervals.count { it.kind == IntervalKind.UNLOCKED })
    }

    @Test
    fun `a huge hole in the log does not invent usage`() {
        val r = run(
            listOf(
                ev(0, SCREEN_INTERACTIVE), ev(1, KEYGUARD_HIDDEN), ev(2, ACTIVITY_RESUMED, "a"),
                ev(10 * 3600, SCREEN_NON_INTERACTIVE),
            ),
            endSec = 11 * 3600,
        )
        // Everything is cut at the last event seen before the hole (t = 2 s), not at the 10 h mark.
        assertTrue(r.appSessions.isEmpty())
        assertEquals(s(2), r.deviceIntervals.single { it.kind == IntervalKind.SCREEN_ON }.endMs)
    }

    @Test
    fun `open session is closed provisionally at end time`() {
        val r = run(
            listOf(ev(0, SCREEN_INTERACTIVE), ev(1, KEYGUARD_HIDDEN), ev(2, ACTIVITY_RESUMED, "a")),
            endSec = 100,
        )
        assertEquals(listOf(AppSession("a", s(2), s(100))), r.appSessions)
        // Resume at the start of the screen-on period, with the state from before it.
        assertEquals(ResumePoint(0, keyguardShowing = true, resumedActivities = emptyList()), r.resume)
    }

    @Test
    fun `resuming from the resume point gives the same rows as one long run`() {
        val all = listOf(
            ev(0, SCREEN_INTERACTIVE), ev(1, KEYGUARD_HIDDEN), ev(2, ACTIVITY_RESUMED, "a"),
            ev(50, ACTIVITY_RESUMED, "b"), ev(70, KEYGUARD_SHOWN), ev(80, SCREEN_NON_INTERACTIVE),
            ev(200, SCREEN_INTERACTIVE), ev(201, KEYGUARD_HIDDEN), ev(202, ACTIVITY_RESUMED, "c"),
            ev(260, ACTIVITY_RESUMED, "a"), ev(300, SCREEN_NON_INTERACTIVE),
            ev(310, NOTIFICATION_INTERRUPTION, "n"),
        )
        val full = run(all, endSec = 400)
        for (cut in listOf(40L, 75L, 90L, 210L, 280L, 305L)) {
            val first = run(all.filter { it.timeMs <= s(cut) }, endSec = cut)
            val resume = first.resume
            val second = sessionizer.process(
                all.filter { it.timeMs >= resume.fromMs }, s(400), resume.keyguardShowing, resume.resumedActivities,
            )
            val sessions = first.appSessions.filter { it.startMs < resume.fromMs } + second.appSessions
            val intervals = first.deviceIntervals.filter { it.startMs < resume.fromMs } + second.deviceIntervals
            val unlocks = first.unlocks.filter { it < resume.fromMs } + second.unlocks
            assertEquals(full.appSessions, sessions.sortedBy { it.startMs }, "sessions, cut=$cut")
            assertEquals(full.deviceIntervals, intervals.sortedBy { it.startMs }, "intervals, cut=$cut")
            assertEquals(full.unlocks, unlocks, "unlocks, cut=$cut")
        }
    }

    @Test
    fun `switching between activities of one app does not end the session`() {
        // Multi-activity apps: pause A1, resume A2, and only then report A1 as stopped, all in the same second.
        val r = run(
            listOf(
                ev(0, SCREEN_INTERACTIVE), ev(1, KEYGUARD_HIDDEN),
                ev(2, ACTIVITY_RESUMED, "social", "Main"),
                ev(60, ACTIVITY_PAUSED, "social", "Main"), ev(60, ACTIVITY_RESUMED, "social", "Player"),
                ev(60, ACTIVITY_STOPPED, "social", "Main"),
                ev(200, ACTIVITY_PAUSED, "social", "Player"), ev(200, ACTIVITY_RESUMED, "social", "Main"),
                ev(200, ACTIVITY_STOPPED, "social", "Player"),
                ev(300, SCREEN_NON_INTERACTIVE),
            ),
        )
        assertEquals(listOf(AppSession("social", s(2), s(300))), r.appSessions)
    }

    @Test
    fun `a translucent overlay that never pauses the app hands the foreground back`() {
        // Recents is resumed on top of the player without the player being paused; when it goes away the app is
        // foreground again with no new RESUMED event.
        val r = run(
            listOf(
                ev(0, SCREEN_INTERACTIVE), ev(1, KEYGUARD_HIDDEN),
                ev(2, ACTIVITY_RESUMED, "social", "Player"),
                ev(100, ACTIVITY_RESUMED, "launcher", "Recents"),
                ev(130, ACTIVITY_PAUSED, "launcher", "Recents"), ev(130, ACTIVITY_STOPPED, "launcher", "Recents"),
                ev(400, ACTIVITY_PAUSED, "social", "Player"),
                ev(401, SCREEN_NON_INTERACTIVE),
            ),
        )
        assertEquals(
            listOf(AppSession("social", s(2), s(100)), AppSession("launcher", s(100), s(130)), AppSession("social", s(130), s(400))),
            r.appSessions,
        )
    }

    @Test
    fun `resume point carries the resumed activities across runs`() {
        val all = listOf(
            ev(0, SCREEN_INTERACTIVE), ev(1, KEYGUARD_HIDDEN), ev(2, ACTIVITY_RESUMED, "a", "X"),
            ev(100, ACTIVITY_RESUMED, "b", "Y"), ev(150, ACTIVITY_PAUSED, "b", "Y"),
            ev(300, SCREEN_NON_INTERACTIVE),
        )
        val full = run(all, endSec = 400)
        val first = run(all.filter { it.timeMs <= s(120) }, endSec = 120)
        val second = sessionizer.process(
            all.filter { it.timeMs >= first.resume.fromMs }, s(400), first.resume.keyguardShowing, first.resume.resumedActivities,
        )
        val merged = (first.appSessions.filter { it.startMs < first.resume.fromMs } + second.appSessions).sortedBy { it.startMs }
        assertEquals(full.appSessions, merged)
        assertEquals(listOf(AppSession("a", s(2), s(100)), AppSession("b", s(100), s(150)), AppSession("a", s(150), s(300))), full.appSessions)
    }

    @Test
    fun `stopped of a previous instance of the same class does not end the new one`() {
        // Same-class swap: PAUSED old instance, RESUMED new instance of the same class, STOPPED old instance.
        val r = run(
            listOf(
                ev(0, SCREEN_INTERACTIVE), ev(1, KEYGUARD_HIDDEN),
                ev(2, ACTIVITY_RESUMED, "settings", "Sub"),
                ev(50, ACTIVITY_PAUSED, "settings", "Sub"), ev(50, ACTIVITY_RESUMED, "settings", "Sub"),
                ev(51, ACTIVITY_STOPPED, "settings", "Sub"),
                ev(120, SCREEN_NON_INTERACTIVE),
            ),
        )
        assertEquals(listOf(AppSession("settings", s(2), s(120))), r.appSessions)
    }

    @Test
    fun `an activity that is only reported stopped is no longer foreground`() {
        // a settings app under Recents: the system sends STOPPED for it without a PAUSED.
        val r = run(
            listOf(
                ev(0, SCREEN_INTERACTIVE), ev(1, KEYGUARD_HIDDEN),
                ev(2, ACTIVITY_RESUMED, "settings", "Sub"),
                ev(60, ACTIVITY_RESUMED, "launcher", "Recents"),
                ev(61, ACTIVITY_STOPPED, "settings", "Sub"),
                ev(62, ACTIVITY_PAUSED, "launcher", "Recents"),
                ev(63, ACTIVITY_STOPPED, "launcher", "Recents"),
                ev(300, SCREEN_NON_INTERACTIVE),
            ),
        )
        // After Recents goes away nothing is resumed: no phantom Settings session until 300 s.
        assertEquals(listOf(AppSession("settings", s(2), s(60)), AppSession("launcher", s(60), s(62))), r.appSessions)
    }

    @Test
    fun `a late stopped is not paired with an old pause`() {
        // PAUSED, quickly RESUMED again (dialog on top), and 3 s later STOPPED: the app really went away.
        val r = run(
            listOf(
                ev(0, SCREEN_INTERACTIVE), ev(1, KEYGUARD_HIDDEN),
                ev(2, ACTIVITY_RESUMED, "focus", "Main"),
                ev(10, ACTIVITY_PAUSED, "focus", "Main"), ev(10, ACTIVITY_RESUMED, "focus", "Main"),
                ev(13, ACTIVITY_STOPPED, "focus", "Main"),
                ev(300, SCREEN_NON_INTERACTIVE),
            ),
        )
        assertEquals(listOf(AppSession("focus", s(2), s(13))), r.appSessions)
    }

    @Test
    fun `screen off forgets activities that never reported a pause`() {
        val r = run(
            listOf(
                ev(0, SCREEN_INTERACTIVE), ev(1, KEYGUARD_HIDDEN), ev(2, ACTIVITY_RESUMED, "a", "X"),
                ev(50, SCREEN_NON_INTERACTIVE), // a never paused
                ev(100, SCREEN_INTERACTIVE), ev(101, KEYGUARD_HIDDEN),
                ev(160, SCREEN_NON_INTERACTIVE),
            ),
        )
        assertEquals(listOf(AppSession("a", s(2), s(50))), r.appSessions)
    }

    @Test
    fun `notifications are collected with their package`() {
        val r = run(listOf(ev(5, NOTIFICATION_INTERRUPTION, "x"), ev(6, NOTIFICATION_INTERRUPTION, null)))
        assertEquals(listOf(NotificationEvent("x", s(5))), r.notifications)
    }
}
