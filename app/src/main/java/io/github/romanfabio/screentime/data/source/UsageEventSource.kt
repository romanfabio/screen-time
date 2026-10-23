package io.github.romanfabio.screentime.data.source

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process

/** Raw event as delivered by [UsageStatsManager]; only the fields we need. */
data class RawEvent(val timeMs: Long, val type: Int, val packageName: String?, val className: String? = null)

fun interface UsageEventProvider {
    /** Events with `fromMs <= time < toMs`, in chronological order. */
    fun queryEvents(fromMs: Long, toMs: Long): List<RawEvent>
}

class UsageEventSource(private val context: Context) : UsageEventProvider {

    fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            // unsafeCheckOpNoThrow only exists from API 29; minSdk is 28.
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    override fun queryEvents(fromMs: Long, toMs: Long): List<RawEvent> {
        val manager = context.getSystemService(UsageStatsManager::class.java)
        val events = manager.queryEvents(fromMs, toMs)
        val out = ArrayList<RawEvent>()
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            out += RawEvent(event.timeStamp, event.eventType, event.packageName, event.className)
        }
        return out
    }
}
