package io.github.romanfabio.screentime.domain

import io.github.romanfabio.screentime.data.source.RawEvent

/** `UsageEvents.Event` type constants we care about (see docs/ARCHITECTURE.md). */
object EventType {
    const val ACTIVITY_RESUMED = 1
    const val ACTIVITY_PAUSED = 2
    const val NOTIFICATION_INTERRUPTION = 12
    const val SCREEN_INTERACTIVE = 15
    const val SCREEN_NON_INTERACTIVE = 16
    const val KEYGUARD_SHOWN = 17
    const val KEYGUARD_HIDDEN = 18
    const val ACTIVITY_STOPPED = 23
    const val DEVICE_SHUTDOWN = 26
    const val DEVICE_STARTUP = 27
}

enum class IntervalKind { SCREEN_ON, UNLOCKED }

data class AppSession(val packageName: String, val startMs: Long, val endMs: Long)
data class DeviceInterval(val kind: IntervalKind, val startMs: Long, val endMs: Long)
data class NotificationEvent(val packageName: String, val timeMs: Long)

/**
 * What the next run needs to know to continue exactly where this one stopped.
 * [resumedActivities] are the activities that were resumed and not yet paused, oldest first, as `package/class`.
 */
data class ResumePoint(
    val fromMs: Long,
    val keyguardShowing: Boolean,
    val resumedActivities: List<String>,
)

data class SessionizerResult(
    val appSessions: List<AppSession>,
    val deviceIntervals: List<DeviceInterval>,
    /** Timestamps of KEYGUARD_HIDDEN transitions (unlocks). */
    val unlocks: List<Long>,
    val notifications: List<NotificationEvent>,
    val resume: ResumePoint,
)

/**
 * Turns raw usage events into screen intervals and app foreground sessions.
 *
 * Pure Kotlin, no Android dependencies. Rules (docs/ARCHITECTURE.md):
 *  - SCREEN_ON = screen interactive; UNLOCKED = screen on and keyguard not showing.
 *  - The foreground app is the owner of the most recently resumed activity that has not been paused. This is
 *    tracked per activity, not per package: many apps pause one activity, resume another and only then
 *    report the first as stopped, and a translucent overlay (recents) can be resumed on top of an app that never
 *    pauses. Tracking by package would end the session in both cases.
 *  - An app session only counts while UNLOCKED, so screen-off audio and lock-screen UI are excluded.
 *  - Sessions of the same app separated by less than [MERGE_GAP_MS] are merged (they count as one "open").
 *  - Anything still open at the end is closed provisionally at `endMs`; the resume point makes the next run
 *    start at the beginning of the current screen-on period, so provisional rows are simply rewritten.
 */
class Sessionizer(
    private val maxGapMs: Long = MAX_EVENT_GAP_MS,
    private val mergeGapMs: Long = MERGE_GAP_MS,
) {

    fun process(
        events: List<RawEvent>,
        endMs: Long,
        keyguardShowing: Boolean = true,
        resumedActivities: List<String> = emptyList(),
    ): SessionizerResult {
        val sessions = mutableListOf<AppSession>()
        val intervals = mutableListOf<DeviceInterval>()
        val unlocks = mutableListOf<Long>()
        val notifications = mutableListOf<NotificationEvent>()

        var keyguard = keyguardShowing
        // Insertion order = resume order; the last entry owns the foreground. Values are package names.
        val resumed = LinkedHashMap<String, String>()
        resumedActivities.forEach { resumed[it] = it.substringBefore('/') }
        // Times of PAUSED events whose STOPPED has not arrived yet, per activity. Lets us tell "STOPPED of the
        // instance that was just replaced" (ignore) from "STOPPED of an activity that was never reported paused"
        // (it is gone). A STOPPED only pairs with a PAUSED that happened at most [PAUSE_STOP_WINDOW_MS] before.
        val pendingStops = HashMap<String, ArrayDeque<Long>>()
        var screenOnSince: Long? = null
        var unlockedSince: Long? = null
        var sessionPkg: String? = null
        var sessionSince = 0L
        var lastEventMs = events.firstOrNull()?.timeMs ?: endMs
        // State just before the SCREEN_INTERACTIVE that started the current screen-on period.
        var screenOnSnapshot: ResumePoint? = null

        fun foreground(): String? = resumed.values.lastOrNull()

        fun closeSession(at: Long) {
            val pkg = sessionPkg ?: return
            if (at > sessionSince) sessions += AppSession(pkg, sessionSince, at)
            sessionPkg = null
        }

        fun closeUnlocked(at: Long) {
            closeSession(at)
            val since = unlockedSince ?: return
            if (at > since) intervals += DeviceInterval(IntervalKind.UNLOCKED, since, at)
            unlockedSince = null
        }

        fun closeScreen(at: Long) {
            closeUnlocked(at)
            val since = screenOnSince ?: return
            if (at > since) intervals += DeviceInterval(IntervalKind.SCREEN_ON, since, at)
            screenOnSince = null
            screenOnSnapshot = null
        }

        /** Makes the open session follow the current foreground app. */
        fun syncSession(at: Long) {
            if (unlockedSince == null) return
            val pkg = foreground()
            if (pkg == sessionPkg) return
            closeSession(at)
            if (pkg != null) {
                sessionPkg = pkg
                sessionSince = at
            }
        }

        fun openUnlocked(at: Long) {
            unlockedSince = at
            syncSession(at)
        }

        for (e in events) {
            // The log has a hole (shutdown without event, process death...): don't invent hours of usage.
            if (screenOnSince != null && e.timeMs - lastEventMs > maxGapMs) closeScreen(lastEventMs)
            lastEventMs = e.timeMs
            val t = e.timeMs
            when (e.type) {
                EventType.SCREEN_INTERACTIVE -> if (screenOnSince == null) {
                    screenOnSnapshot = ResumePoint(t, keyguard, resumed.keys.toList())
                    screenOnSince = t
                    if (!keyguard) openUnlocked(t)
                }
                EventType.SCREEN_NON_INTERACTIVE -> {
                    // The system pauses everything when the screen goes off and resumes the top activity again at
                    // wake-up, so anything still "resumed" now is a leftover from a missing PAUSED event.
                    pendingStops.clear()
                    resumed.clear()
                    closeScreen(t)
                }
                EventType.KEYGUARD_SHOWN -> {
                    keyguard = true
                    closeUnlocked(t)
                }
                EventType.KEYGUARD_HIDDEN -> if (keyguard) {
                    keyguard = false
                    unlocks += t
                    if (screenOnSince != null && unlockedSince == null) openUnlocked(t)
                }
                EventType.ACTIVITY_RESUMED -> {
                    val pkg = e.packageName ?: continue
                    val key = activityKey(pkg, e.className)
                    resumed.remove(key) // re-resumed: move it to the top
                    resumed[key] = pkg
                    syncSession(t)
                }
                EventType.ACTIVITY_PAUSED, EventType.ACTIVITY_STOPPED -> {
                    val pkg = e.packageName ?: continue
                    if (e.className == null) {
                        // Cannot be matched to an activity: end every activity of the package.
                        resumed.entries.removeAll { it.value == pkg }
                    } else {
                        val key = activityKey(pkg, e.className)
                        if (e.type == EventType.ACTIVITY_PAUSED) {
                            resumed.remove(key)
                            pendingStops.getOrPut(key) { ArrayDeque() }.addLast(t)
                        } else {
                            // STOPPED normally follows PAUSED within a moment and may arrive after the same class was
                            // resumed again (a new instance): then it belongs to the old instance and must not end
                            // the new one. Without a recent PAUSED the system skipped it (e.g. an app covered by
                            // Recents or a permission dialog), so the activity is gone.
                            val pending = pendingStops[key]
                            while (pending != null && pending.isNotEmpty() && t - pending.first() > PAUSE_STOP_WINDOW_MS) {
                                pending.removeFirst()
                            }
                            if (pending != null && pending.isNotEmpty()) pending.removeFirst() else resumed.remove(key)
                        }
                    }
                    syncSession(t)
                }
                EventType.DEVICE_SHUTDOWN -> {
                    pendingStops.clear()
                    closeScreen(t)
                    keyguard = true
                    resumed.clear()
                }
                EventType.DEVICE_STARTUP -> {
                    keyguard = true
                    resumed.clear()
                }
                EventType.NOTIFICATION_INTERRUPTION -> e.packageName?.let { notifications += NotificationEvent(it, t) }
            }
        }

        val resume = screenOnSnapshot
            ?: ResumePoint(if (events.isEmpty()) endMs else lastEventMs + 1, keyguard, resumed.keys.toList())
        // Provisional close: never extend past the last event by more than the sanity cap.
        val provisionalEnd = minOf(endMs, lastEventMs + maxGapMs)
        closeScreen(provisionalEnd)

        return SessionizerResult(
            appSessions = mergeSessions(sessions),
            deviceIntervals = intervals.sortedBy { it.startMs },
            unlocks = unlocks,
            notifications = notifications,
            resume = resume,
        )
    }

    private fun activityKey(pkg: String, cls: String?) = "$pkg/${cls.orEmpty()}"

    private fun mergeSessions(sessions: List<AppSession>): List<AppSession> {
        val out = mutableListOf<AppSession>()
        val lastByPackage = HashMap<String, Int>()
        for (s in sessions.sortedBy { it.startMs }) {
            val idx = lastByPackage[s.packageName]
            val prev = idx?.let { out[it] }
            if (prev != null && s.startMs - prev.endMs < mergeGapMs) {
                out[idx] = prev.copy(endMs = maxOf(prev.endMs, s.endMs))
            } else {
                lastByPackage[s.packageName] = out.size
                out += s
            }
        }
        return out
    }

    companion object {
        const val MERGE_GAP_MS = 5_000L
        const val MAX_EVENT_GAP_MS = 4 * 60 * 60 * 1000L
        const val PAUSE_STOP_WINDOW_MS = 2_000L
    }
}
