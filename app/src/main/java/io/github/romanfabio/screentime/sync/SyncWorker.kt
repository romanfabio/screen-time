package io.github.romanfabio.screentime.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.romanfabio.screentime.container

/** Copies new usage events into our own database. Cheap: normally a few hundred milliseconds. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = applicationContext.container
        if (!container.eventSource.hasUsageAccess()) {
            // Nothing to do until the user grants access again; the app shows a banner for that.
            Log.w(TAG, "usage access missing, skipping sync")
            return Result.success()
        }
        return try {
            val summary = container.ingest.run()
            Log.i(TAG, "synced: $summary")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "sync failed (attempt $runAttemptCount)", e)
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    private companion object {
        const val TAG = "SyncWorker"
        const val MAX_ATTEMPTS = 3
    }
}
