package io.github.romanfabio.screentime.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "app_session",
    indices = [Index(value = ["packageName", "startMs"], unique = true), Index("startMs")],
)
data class AppSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val startMs: Long,
    val endMs: Long,
)

@Entity(
    tableName = "device_interval",
    indices = [Index(value = ["kind", "startMs"], unique = true), Index("startMs")],
)
data class DeviceIntervalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** `SCREEN_ON` or `UNLOCKED`. */
    val kind: String,
    val startMs: Long,
    val endMs: Long,
)

@Entity(tableName = "unlock_event")
data class UnlockEventEntity(@PrimaryKey val timeMs: Long)

@Entity(tableName = "notification_event", primaryKeys = ["packageName", "timeMs"], indices = [Index("timeMs")])
data class NotificationEventEntity(val packageName: String, val timeMs: Long)

/** Local date as ISO `yyyy-MM-dd` so ranges can be compared as strings. */
@Entity(tableName = "hourly_app_usage", primaryKeys = ["date", "hour", "packageName"])
data class HourlyAppUsageEntity(
    val date: String,
    val hour: Int,
    val packageName: String,
    val foregroundMs: Long,
    val opens: Int,
    val notifications: Int,
)

@Entity(tableName = "daily_device")
data class DailyDeviceEntity(
    @PrimaryKey val date: String,
    val screenOnMs: Long,
    val unlockedMs: Long,
    val unlocks: Int,
    val firstPickupMs: Long?,
    val lastUseMs: Long?,
)

@Entity(tableName = "app_info")
data class AppInfoEntity(
    @PrimaryKey val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val isLauncher: Boolean,
    val lastSeenMs: Long,
    val uninstalled: Boolean = false,
    /** Has an icon in the app drawer; system packages without one (SystemUI, keyboards…) are background plumbing. */
    val hasLauncherIcon: Boolean = true,
)

@Entity(tableName = "sync_state")
data class SyncStateEntity(@PrimaryKey val key: String, val value: String)

/** Per-app totals over a date range (query result, not a table). */
data class AppTotal(
    val packageName: String,
    val foregroundMs: Long,
    val opens: Int,
    val notifications: Int,
)
