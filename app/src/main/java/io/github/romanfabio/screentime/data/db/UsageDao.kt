package io.github.romanfabio.screentime.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface UsageDao {

    // --- raw rows -------------------------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSessions(rows: List<AppSessionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIntervals(rows: List<DeviceIntervalEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUnlocks(rows: List<UnlockEventEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotifications(rows: List<NotificationEventEntity>)

    @Query("DELETE FROM app_session WHERE startMs >= :fromMs")
    suspend fun deleteSessionsFrom(fromMs: Long)

    @Query("DELETE FROM device_interval WHERE startMs >= :fromMs")
    suspend fun deleteIntervalsFrom(fromMs: Long)

    @Query("DELETE FROM unlock_event WHERE timeMs >= :fromMs")
    suspend fun deleteUnlocksFrom(fromMs: Long)

    @Query("DELETE FROM notification_event WHERE timeMs >= :fromMs")
    suspend fun deleteNotificationsFrom(fromMs: Long)

    @Query("SELECT MIN(startMs) FROM device_interval")
    suspend fun earliestIntervalStart(): Long?

    @Query("SELECT MIN(startMs) FROM app_session")
    suspend fun earliestSessionStart(): Long?

    @Query("SELECT COUNT(*) FROM app_session")
    suspend fun sessionCount(): Int

    @Query("SELECT COUNT(*) FROM unlock_event")
    suspend fun unlockCount(): Int

    @Query("SELECT * FROM app_session WHERE endMs > :fromMs AND startMs < :toMs ORDER BY startMs")
    suspend fun sessionsOverlapping(fromMs: Long, toMs: Long): List<AppSessionEntity>

    @Query("SELECT * FROM device_interval WHERE endMs > :fromMs AND startMs < :toMs ORDER BY startMs")
    suspend fun intervalsOverlapping(fromMs: Long, toMs: Long): List<DeviceIntervalEntity>

    @Query("SELECT * FROM unlock_event WHERE timeMs >= :fromMs AND timeMs < :toMs ORDER BY timeMs")
    suspend fun unlocksBetween(fromMs: Long, toMs: Long): List<UnlockEventEntity>

    @Query("SELECT * FROM notification_event WHERE timeMs >= :fromMs AND timeMs < :toMs ORDER BY timeMs")
    suspend fun notificationsBetween(fromMs: Long, toMs: Long): List<NotificationEventEntity>

    // --- aggregates -----------------------------------------------------------------------------

    @Query("DELETE FROM hourly_app_usage WHERE date >= :fromDate AND date <= :toDate")
    suspend fun deleteHourlyBetween(fromDate: String, toDate: String)

    @Query("DELETE FROM daily_device WHERE date >= :fromDate AND date <= :toDate")
    suspend fun deleteDailyBetween(fromDate: String, toDate: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHourly(rows: List<HourlyAppUsageEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDaily(rows: List<DailyDeviceEntity>)

    @Query("SELECT * FROM daily_device WHERE date >= :fromDate AND date <= :toDate ORDER BY date")
    suspend fun dailyBetween(fromDate: String, toDate: String): List<DailyDeviceEntity>

    @Query("SELECT * FROM hourly_app_usage WHERE date >= :fromDate AND date <= :toDate ORDER BY date, hour")
    suspend fun hourlyBetween(fromDate: String, toDate: String): List<HourlyAppUsageEntity>

    @Query("SELECT * FROM hourly_app_usage WHERE packageName = :packageName AND date >= :fromDate AND date <= :toDate")
    suspend fun hourlyForPackage(packageName: String, fromDate: String, toDate: String): List<HourlyAppUsageEntity>

    @Query("SELECT MIN(date) FROM daily_device")
    suspend fun earliestDailyDate(): String?

    @Query(
        "SELECT packageName, SUM(foregroundMs) AS foregroundMs, SUM(opens) AS opens, " +
            "SUM(notifications) AS notifications FROM hourly_app_usage " +
            "WHERE date >= :fromDate AND date <= :toDate GROUP BY packageName ORDER BY foregroundMs DESC",
    )
    suspend fun appTotalsBetween(fromDate: String, toDate: String): List<AppTotal>

    // --- app info / sync state ------------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAppInfo(rows: List<AppInfoEntity>)

    @Query("SELECT DISTINCT packageName FROM hourly_app_usage")
    suspend fun usedPackages(): List<String>

    @Query("DELETE FROM app_info")
    suspend fun clearAppInfo()

    @Query("SELECT packageName FROM app_info")
    suspend fun knownPackages(): List<String>

    @Query("SELECT * FROM app_info")
    suspend fun allAppInfo(): List<AppInfoEntity>

    @Query("SELECT value FROM sync_state WHERE `key` = :key")
    suspend fun getState(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putState(row: SyncStateEntity)
}
