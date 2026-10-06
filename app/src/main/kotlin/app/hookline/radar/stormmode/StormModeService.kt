package app.hookline.radar.stormmode

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.hookline.radar.HooklineApp
import app.hookline.radar.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Storm Mode "Active": foreground service polling NWS every 60 s.
 * The persistent notification states the interval so users always know which
 * mode they're in (honest about battery use).
 */
class StormModeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = NotificationCompat.Builder(this, HooklineApp.CHANNEL_STORM_MODE)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.storm_mode_running))
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)

        scope.launch {
            val checker = AlertChecker(this@StormModeService)
            while (isActive) {
                runCatching { checker.check() }
                delay(60_000)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 7001
        private const val ACTION_STOP = "app.hookline.radar.STOP_STORM_MODE"

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, StormModeService::class.java))

        fun stop(context: Context) =
            context.startService(Intent(context, StormModeService::class.java).setAction(ACTION_STOP))
    }
}
