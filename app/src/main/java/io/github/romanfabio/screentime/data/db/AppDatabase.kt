package io.github.romanfabio.screentime.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        AppSessionEntity::class,
        DeviceIntervalEntity::class,
        UnlockEventEntity::class,
        NotificationEventEntity::class,
        HourlyAppUsageEntity::class,
        DailyDeviceEntity::class,
        AppInfoEntity::class,
        SyncStateEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun usageDao(): UsageDao

    companion object {
        /** `app_info` is only a cache of PackageManager data: add the column and let it be re-resolved. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE app_info ADD COLUMN hasLauncherIcon INTEGER NOT NULL DEFAULT 1")
                db.execSQL("DELETE FROM app_info")
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "screentime.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
