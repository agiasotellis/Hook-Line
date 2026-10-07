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
import app.hookline.radar.core.data.MeteoAlarm
import app.hookline.radar.core.data.NwsAlerts
import app.hookline.radar.core.data.WarningItem
import app.hookline.radar.core.data.WarningLevel
import app.hookline.radar.core.data.WarningRegions
import java.time.Instant
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
    private val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
    private val nws = NwsAlerts(http, BuildConfig.NWS_USER_AGENT)
    private val meteoAlarm = MeteoAlarm(http)

    /** Storm Mode on/off (on by default). */
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(v) = prefs.edit().putBoolean("enabled", v).apply()

    /** Lowest European warning level that notifies. */
    var euMinLevel: WarningLevel
        get() = runCatching { WarningLevel.valueOf(prefs.getString("eu_min_level", null) ?: "ORANGE") }.getOrDefault(WarningLevel.ORANGE)
        set(v) = prefs.edit().putString("eu_min_level", v.name).apply()

    /**
     * Saved places. Interim: "Home" is set from the device location when it's
     * inside NWS or MeteoAlarm coverage, until the Places screen exists.
     */
    fun places(): List<Pair<String, LatLon>> {
        if (!prefs.contains("home_lat")) return emptyList()
        return listOf("Home" to LatLon(prefs.getFloat("home_lat", 0f).toDouble(), prefs.getFloat("home_lon", 0f).toDouble()))
    }

    /** MeteoAlarm region code of home, when home is in Europe. */
    fun homeRegion(): String? = prefs.getString("home_region", null)

    fun homeLabel(): String? = when {
        !prefs.contains("home_lat") -> null
        else -> prefs.getString("home_label", null) ?: "Your location"
    }

    fun setHome(p: LatLon?, regionCode: String?, label: String?) {
        val e = prefs.edit()
        if (p == null) e.remove("home_lat").remove("home_lon")
        else e.putFloat("home_lat", p.lat.toFloat()).putFloat("home_lon", p.lon.toFloat())
        if (regionCode == null) e.remove("home_region") else e.putString("home_region", regionCode)
        if (label == null) e.remove("home_label") else e.putString("home_label", label)
        e.apply()
    }

    /** Checks home; returns the NWS alerts found (the worker uses them for auto-activation). */
    suspend fun check(): List<Alert> {
        if (!enabled) return emptyList()
        val found = mutableListOf<Alert>()
        val region = homeRegion()
        for ((name, point) in places()) {
            if (region != null) {
                val min = euMinLevel
                val now = Instant.now()
                meteoAlarm.warnings(WarningRegions.countryOf(region))
                    .filter { region in it.codes && it.level.ordinal >= min.ordinal && it.isActive(now) }
                    .filter { markSeen(it.id + ":" + it.level) }
                    .forEach { notify(name, it) }
                continue
            }
            val alerts = nws.activeAt(point).filter { it.hazard != Hazard.OTHER }
            alerts.filter { markSeen(it.id) }.forEach { notify(name, it) }
            found += alerts
        }
        return found
    }

    @SuppressLint("MissingPermission")
    private fun notify(place: String, w: WarningItem) {
        if (!canNotify()) return
        val until = w.expires?.atZone(ZoneId.systemDefault())?.format(DateTimeFormatter.ofPattern("EEE HH:mm"))
        val title = buildString {
            append(w.event).append(" for ").append(place)
            if (until != null) append(" until ").append(until)
        }
        val n = NotificationCompat.Builder(context, HooklineApp.CHANNEL_WARNINGS)
            .setSmallIcon(app.hookline.radar.R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(w.area)
            .setStyle(NotificationCompat.BigTextStyle().bigText(listOfNotNull(w.headline, w.description, w.instruction).joinToString("\n\n")))
            .setPriority(if (w.level == WarningLevel.RED) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(w.id.hashCode(), n)
    }

    private fun canNotify(): Boolean =
        android.os.Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun markSeen(id: String): Boolean {
        val seen = prefs.getStringSet("seen", emptySet()).orEmpty()
        if (id in seen) return false
        // Keep the set bounded.
        prefs.edit().putStringSet("seen", (seen.toList().takeLast(200) + id).toSet()).apply()
        return true
    }

    @SuppressLint("MissingPermission")
    private fun notify(place: String, a: Alert) {
        if (!canNotify()) return
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
