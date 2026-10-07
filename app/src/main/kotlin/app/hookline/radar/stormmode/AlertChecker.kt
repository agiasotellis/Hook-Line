package app.hookline.radar.stormmode

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.hookline.radar.BuildConfig
import app.hookline.radar.HooklineApp
import app.hookline.radar.core.data.Alert
import app.hookline.radar.core.data.Coverage
import app.hookline.radar.core.data.Hazard
import app.hookline.radar.core.data.LatLon
import app.hookline.radar.core.data.LocalGrid
import app.hookline.radar.core.data.MeteoAlarm
import app.hookline.radar.core.data.Nowcast
import app.hookline.radar.core.data.NowcastResult
import app.hookline.radar.core.data.NwsAlerts
import app.hookline.radar.core.data.RainFrame
import app.hookline.radar.core.data.RainSource
import app.hookline.radar.core.data.WarningItem
import app.hookline.radar.core.data.WarningLevel
import app.hookline.radar.core.data.WarningRegions
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Shared by the 15-minute worker ("Watching") and the 60-second foreground
 * service ("Active"): for every place, fetch official warnings (NWS in the
 * US, MeteoAlarm in Europe) and the rain outlook, and notify once per
 * warning or rain event. Also refreshes the home-screen widget.
 */
class AlertChecker(private val context: Context) {

    private val prefs = context.getSharedPreferences("storm_mode", Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
    private val nws = NwsAlerts(http, BuildConfig.NWS_USER_AGENT)
    private val meteoAlarm = MeteoAlarm(http)
    private val rainSource = RainSource(http)
    val store = PlaceStore(context)

    /** Storm Mode on/off (on by default). */
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(v) = prefs.edit().putBoolean("enabled", v).apply()

    /** Lowest European warning level that notifies. */
    var euMinLevel: WarningLevel
        get() = runCatching { WarningLevel.valueOf(prefs.getString("eu_min_level", null) ?: "ORANGE") }.getOrDefault(WarningLevel.ORANGE)
        set(v) = prefs.edit().putString("eu_min_level", v.name).apply()

    /** The automatic place: where the device was last seen. */
    fun places(): List<Pair<String, LatLon>> {
        if (!prefs.contains("home_lat")) return emptyList()
        return listOf("Current location" to LatLon(prefs.getFloat("home_lat", 0f).toDouble(), prefs.getFloat("home_lon", 0f).toDouble()))
    }

    /** Current location (id 0, if known) plus saved places. */
    fun allPlaces(): List<Place> {
        val cur = places().firstOrNull()?.let { (_, p) ->
            Place(0, "Current location", p.lat, p.lon, homeRegion(), store.currentWarnings, store.currentRain, store.currentOutlook, store.currentOutlookAt)
        }
        return listOfNotNull(cur) + store.saved()
    }

    /** MeteoAlarm region code of the current location, when it's in Europe. */
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

    /** Checks every place; returns the NWS alerts found (the worker uses them for auto-activation). */
    suspend fun check(includeRain: Boolean = true): List<Alert> {
        if (!enabled) return emptyList()
        val places = allPlaces()
        val found = mutableListOf<Alert>()
        val euFeeds = HashMap<String, List<WarningItem>>()
        var widgetWarning: String? = null
        for (place in places) {
            if (!place.warnings) continue
            val point = LatLon(place.lat, place.lon)
            try {
                val region = place.region
                if (region != null) {
                    val country = WarningRegions.countryOf(region)
                    val items = euFeeds.getOrPut(country) { meteoAlarm.warnings(country) }
                    val now = Instant.now()
                    val active = items.filter { region in it.codes && it.isActive(now) }
                    if (place.id == widgetPlaceId(places)) widgetWarning = active.maxByOrNull { it.level.ordinal }?.event
                    active.filter { it.level.ordinal >= euMinLevel.ordinal }
                        .filter { markSeen("${it.id}:${it.level}:${place.id}") }
                        .forEach { notify(place.name, it) }
                } else if (Coverage.inUs(point)) {
                    val alerts = nws.activeAt(point).filter { it.hazard != Hazard.OTHER }
                    if (place.id == widgetPlaceId(places)) widgetWarning = alerts.firstOrNull()?.event
                    alerts.filter { markSeen("${it.id}:${place.id}") }.forEach { notify(place.name, it) }
                    found += alerts
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // One place failing shouldn't stop the others.
            }
        }
        if (includeRain && rainDue()) checkRain(places, widgetWarning)
        return found
    }

    // ---- rain starting soon ---------------------------------------------------------------

    /** Rain checks download 1–3 MB: every run on Wi-Fi, every 30 min on mobile data (or Wi-Fi only if asked). */
    private fun rainDue(): Boolean {
        val since = System.currentTimeMillis() - prefs.getLong("rain_last", 0)
        if (since < 10 * 60_000L) return false // the Active service runs every minute; rain data changes every 2–15 min
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val metered = cm?.isActiveNetworkMetered ?: true
        if (!metered) return true
        if (store.rainOnWifiOnly) return false
        return since > 29 * 60_000L
    }

    private fun widgetPlaceId(places: List<Place>): Long? = places.firstOrNull { it.rain }?.id ?: places.firstOrNull()?.id

    private suspend fun checkRain(places: List<Place>, widgetWarning: String?) {
        prefs.edit().putLong("rain_last", System.currentTimeMillis()).apply()
        val wanted = places.filter { it.rain }
        val widgetId = widgetPlaceId(places)
        val byKind = wanted.groupBy { rainSource.kindFor(LatLon(it.lat, it.lon)) }
        for ((kind, group) in byKind) {
            if (kind == null) {
                group.forEach { saveOutlook(it, "No rain radar here") }
                continue
            }
            try {
                val keys = rainSource.latestKeys(kind)
                val (newestKey, newestTime) = keys.firstOrNull() ?: continue
                val cur: RainFrame = rainSource.load(kind, newestKey, newestTime)
                var older: RainFrame? = null
                val (thr, heavy) = rainSource.thresholds(kind)
                for (place in group) {
                    val center = LatLon(place.lat, place.lon)
                    val grid = cur.grid(center, CELL_KM, GRID)
                    // Previous grid: the one saved last run if it's 10–25 min older, else cut from the older frame.
                    var prev = loadGrid(place.id)?.takeIf { g ->
                        newestTime - g.timeMillis in 600_000L..1_500_000L && g.size == GRID &&
                            kotlin.math.abs(g.centerLat - center.lat) < 1e-4 && kotlin.math.abs(g.centerLon - center.lon) < 1e-4
                    }
                    if (prev == null && keys.size > 1) {
                        if (older == null) older = rainSource.load(kind, keys[1].first, keys[1].second)
                        prev = older.grid(center, CELL_KM, GRID)
                    }
                    if (loadGrid(place.id)?.timeMillis != newestTime) saveGrid(place.id, grid)
                    val result = Nowcast.forecast(grid, prev?.let { Nowcast.motion(it, grid, thr) }, thr, heavy)
                    saveOutlook(place, result.summary)
                    maybeNotifyRain(place, result)
                    if (place.id == widgetId) {
                        runCatching { RadarWidget.publish(context, place, grid, kind, result, widgetWarning) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Try again next run.
            }
        }
    }

    private fun saveOutlook(p: Place, text: String) {
        if (p.id == 0L) store.currentOutlook = text
        else store.update(p.id) { it.copy(outlook = text, outlookAt = System.currentTimeMillis()) }
    }

    private fun gridFile(id: Long) = File(File(context.filesDir, "nowcast").apply { mkdirs() }, "place_$id.bin")
    private fun loadGrid(id: Long): LocalGrid? = gridFile(id).takeIf { it.exists() }?.let { LocalGrid.decode(it.readBytes()) }
    private fun saveGrid(id: Long, g: LocalGrid) = gridFile(id).writeBytes(g.encode())

    /** One rain notification per place per 90 minutes, only for rain that hasn't started yet. */
    @SuppressLint("MissingPermission")
    private fun maybeNotifyRain(place: Place, r: NowcastResult) {
        val minutes = r.startsInMin ?: return
        if (r.rainingNow) return
        val key = "rain_notified_${place.id}"
        if (System.currentTimeMillis() - prefs.getLong(key, 0) < 90 * 60_000L) return
        if (!canNotify()) return
        prefs.edit().putLong(key, System.currentTimeMillis()).apply()
        val title = (if (r.heavy) "Heavy rain" else "Rain") + " in about $minutes min at ${place.name}"
        val n = NotificationCompat.Builder(context, HooklineApp.CHANNEL_RAIN)
            .setSmallIcon(app.hookline.radar.R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText("From the latest radar and how the rain is moving. Tap to see it.")
            .setContentIntent(RadarWidget.openAppIntent(context))
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(("rain" + place.id).hashCode(), n)
    }

    // ---- warnings ---------------------------------------------------------------------------

    private fun markSeen(id: String): Boolean {
        val seen = prefs.getStringSet("seen", emptySet()).orEmpty()
        if (id in seen) return false
        // Keep the set bounded.
        prefs.edit().putStringSet("seen", (seen.toList().takeLast(300) + id).toSet()).apply()
        return true
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
            .setSmallIcon(app.hookline.radar.R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(w.area)
            .setStyle(NotificationCompat.BigTextStyle().bigText(listOfNotNull(w.headline, w.description, w.instruction).joinToString("\n\n")))
            .setPriority(if (w.level == WarningLevel.RED) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(RadarWidget.openAppIntent(context))
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(w.id.hashCode(), n)
    }

    private fun canNotify(): Boolean =
        android.os.Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun notify(place: String, a: Alert) {
        if (!canNotify()) return
        val until = a.expires?.atZone(ZoneId.systemDefault())?.format(DateTimeFormatter.ofPattern("h:mm a"))
        val title = buildString {
            append(a.event).append(" for ").append(place)
            if (until != null) append(" until ").append(until)
        }
        val n = NotificationCompat.Builder(context, HooklineApp.CHANNEL_WARNINGS)
            .setSmallIcon(app.hookline.radar.R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(a.headline ?: a.event)
            .setStyle(NotificationCompat.BigTextStyle().bigText(a.instruction ?: a.headline ?: a.event))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(RadarWidget.openAppIntent(context))
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(a.id.hashCode(), n)
    }

    companion object {
        /** Rain grid: 121 × 2 km = ±120 km around each place. */
        const val CELL_KM = 2.0
        const val GRID = 121
    }
}
