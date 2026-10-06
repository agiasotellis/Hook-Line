package app.couplet.radar

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import app.couplet.radar.stormmode.StormModeWorker

class CoupletApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_WARNINGS, getString(R.string.channel_warnings), NotificationManager.IMPORTANCE_HIGH)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STORM_MODE, getString(R.string.channel_storm_mode), NotificationManager.IMPORTANCE_LOW)
        )
        // "Watching" is the default Storm Mode state chosen at onboarding.
        StormModeWorker.schedule(this)
    }

    companion object {
        const val CHANNEL_WARNINGS = "warnings"
        const val CHANNEL_STORM_MODE = "storm_mode"
    }
}
