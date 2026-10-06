package app.hookline.radar.stormmode

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.hookline.radar.BuildConfig
import app.hookline.radar.HooklineApp
import app.hookline.radar.core.data.Alert
import app.hookline.radar.core.data.Hazard
import app.hookline.radar.core.data.LatLon
import app.hookline.radar.core.data.NwsAlerts
import okhttp3.OkHttpClient
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Shared by the 15-minute worker ("Watching") and the 60-second foreground
 * service ("Active"): fetch NWS alerts for each saved place and notify once
 * per alert id.
 */
class AlertChecker(private val context: Context) {

    private val prefs = context.getSharedPreferences("storm_mode", Context.MODE_PRIVATE)
    private val nws = NwsAlerts(
        OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build(),
        BuildConfig.NWS_USER_AGENT,
    )

    /** Saved places. Interim: a single default until the Places screen exists. */
    fun places(): List<Pair<String, LatLon>> = listOf("Home" to LatLon(
        prefs.getFloat("home_lat", 35.3395f).toDouble(),
        prefs.getFloat("home_lon", -97.4867f).toDouble(),
    ))

    suspend fun check(): List<Alert> {
        val found = mutableListOf<Alert>()
        for ((name, point) in places()) {
            val alerts = nws.activeAt(point).filter { it.hazard != Hazard.OTHER }
            alerts.filter { markSeen(it.id) }.forEach { notify(name, it) }
            found += alerts
        }
        return found
    }

    private fun markSeen(id: String): Boolean {
        val seen = prefs.getStringSet("seen", emptySet()).orEmpty()
        if (id in seen) return false
        // Keep the set bounded.
        prefs.edit().putStringSet("seen", (seen.toList().takeLast(200) + id).toSet()).apply()
        return true
    }

    @SuppressLint("MissingPermission")
    private fun notify(place: String, a: Alert) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= 33
        ) return
        val until = a.expires?.atZone(ZoneId.systemDefault())?.format(DateTimeFormatter.ofPattern("h:mm a"))
        val title = buildString {
            append(a.event).append(" for ").append(place)
            if (until != null) append(" until ").append(until)
        }
        val n = NotificationCompat.Builder(context, HooklineApp.CHANNEL_WARNINGS)
            .setSmallIcon(app.hookline.radar.R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(a.headline ?: a.event)
            .setStyle(NotificationCompat.BigTextStyle().bigText(a.instruction ?: a.headline ?: a.event))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(a.id.hashCode(), n)
    }
}
