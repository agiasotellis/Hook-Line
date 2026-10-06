package app.couplet.radar.stormmode

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.couplet.radar.core.data.Hazard
import java.util.concurrent.TimeUnit

/** Storm Mode "Watching": a 15-minute background check (the WorkManager minimum). */
class StormModeWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = runCatching {
        val alerts = AlertChecker(applicationContext).check()
        val autoActivate = applicationContext.getSharedPreferences("storm_mode", Context.MODE_PRIVATE)
            .getBoolean("auto_active_in_watch", false)
        if (autoActivate && alerts.any { it.hazard == Hazard.TORNADO_WATCH || it.hazard == Hazard.SEVERE_WATCH }) {
            // Android 12+ can refuse to start a foreground service from the background;
            // in that case the warning notification is still delivered by the checker.
            runCatching { StormModeService.start(applicationContext) }
        }
        Result.success()
    }.getOrElse { Result.retry() }

    companion object {
        private const val NAME = "storm-mode-watching"

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<StormModeWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(NAME)
    }
}
