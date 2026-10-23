package io.github.romanfabio.screentime

import android.app.Application
import android.content.Context
import io.github.romanfabio.screentime.data.AppPrefs
import io.github.romanfabio.screentime.data.PermissionStatus
import io.github.romanfabio.screentime.data.db.AppDatabase
import io.github.romanfabio.screentime.data.repo.DashboardRepository
import io.github.romanfabio.screentime.data.source.AppInfoSource
import io.github.romanfabio.screentime.data.source.UsageEventSource
import io.github.romanfabio.screentime.domain.IngestUseCase
import io.github.romanfabio.screentime.sync.SyncScheduler

/** Manual dependency container; the app is small enough not to need a DI framework. */
class AppContainer(context: Context) {
    val db: AppDatabase by lazy { AppDatabase.create(context) }
    val eventSource = UsageEventSource(context)
    val appInfoSource = AppInfoSource(context)
    val ingest: IngestUseCase by lazy { IngestUseCase(db, eventSource, appInfoSource) }
    val prefs = AppPrefs(context)
    val dashboard: DashboardRepository by lazy { DashboardRepository(db, prefs, context.packageName) }
    val permissions = PermissionStatus(context, eventSource)
}

class ScreenTimeApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // Idempotent: keeps the existing schedule, WorkManager restores it after reboot by itself.
        SyncScheduler.schedulePeriodic(this)
    }
}

val Context.container: AppContainer
    get() = (applicationContext as ScreenTimeApplication).container
