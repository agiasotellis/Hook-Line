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
import app.hookline.radar.core.data.toWarningItem
import app.hookline.radar.core.data.Confidence
import app.hookline.radar.core.data.RainForecast
import app.hookline.radar.core.data.RainForecaster
import app.hookline.radar.i18n.ForecastText
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import app.hookline.radar.i18n.tr
import app.hookline.radar.i18n.nowcastSummary

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
    val alertPrefs = AlertPrefs(context)

    /** Storm Mode on/off (on by default). */
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(v) {
            prefs.edit().putBoolean("enabled", v).apply()
            // The widgets say when Storm Mode is off, since they stop updating.
            WarningsWidget.refresh(context); RainBarWidget.refresh(context)
        }

    /** Lowest European warning level that notifies. */
    var euMinLevel: WarningLevel
        get() = runCatching { WarningLevel.valueOf(prefs.getString("eu_min_level", null) ?: "ORANGE") }.getOrDefault(WarningLevel.ORANGE)
        set(v) = prefs.edit().putString("eu_min_level", v.name).apply()

    /** The automatic place: where the device was last seen. */
    fun places(): List<Pair<String, LatLon>> {
        if (!prefs.contains("home_lat")) return emptyList()
        return listOf(tr("Current location") to LatLon(prefs.getFloat("home_lat", 0f).toDouble(), prefs.getFloat("home_lon", 0f).toDouble()))
    }

    /** Current location (id 0, if known) plus saved places. */
    fun allPlaces(): List<Place> {
        val cur = places().firstOrNull()?.let { (_, p) ->
            Place(0, tr("Current location"), p.lat, p.lon, homeRegion(), store.currentWarnings, store.currentRain, store.currentOutlook, store.currentOutlookAt)
        }
        return listOfNotNull(cur) + store.saved()
    }

    /** MeteoAlarm region code of the current location, when it's in Europe. */
    fun homeRegion(): String? = prefs.getString("home_region", null)

    fun homeLabel(): String? = when {
        !prefs.contains("home_lat") -> null
        else -> prefs.getString("home_label", null) ?: tr("Your location")
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
        val widgetEntries = mutableListOf<WarningsWidget.Entry>()
        val failed = mutableSetOf<Long>()
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
                    active.filter { it.level >= WarningLevel.YELLOW }.forEach { widgetEntries += WarningsWidget.Entry.of(place.id, place.name, it) }
                    // Nothing is recorded as delivered unless it can actually be shown.
                    if (canNotify()) active.filter { it.level.ordinal >= euMinLevel.ordinal }
                        .filter { mayNotifyWarning(severe(it)) }
                        .filter { markSeen("${it.id}:${it.level}:${place.id}") }
                        .filter { isNew(place.id, it, severe(it)) }
                        .forEach { notify(place.name, it) }
                } else if (Coverage.inUs(point)) {
                    val alerts = nws.activeAt(point).filter { it.hazard != Hazard.OTHER }
                    if (place.id == widgetPlaceId(places)) widgetWarning = alerts.firstOrNull()?.event
                    alerts.forEach { widgetEntries += WarningsWidget.Entry.of(place.id, place.name, it.toWarningItem()) }
                    if (canNotify()) alerts.filter { mayNotifyWarning(severe(it)) }
                        .filter { markSeen("${it.id}:${place.id}") }
                        .filter { isNew(place.id, it.toWarningItem(), severe(it)) }
                        .forEach { notify(place.name, it) }
                    found += alerts
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // One place failing shouldn't stop the others.
                failed += place.id
            }
        }
        runCatching { WarningsWidget.publish(context, widgetEntries, failed) }
        if (includeRain && rainDue()) checkRain(places, widgetWarning)
        alertPrefs.lastCheckMillis = System.currentTimeMillis()
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
        val widgetId = widgetPlaceId(places)
        // The widgets' place gets an outlook even when its rain alerts are off.
        val wanted = places.filter { it.rain || it.id == widgetId }
        val byKind = wanted.groupBy { rainSource.kindFor(LatLon(it.lat, it.lon)) }
        for ((kind, group) in byKind) {
            if (kind == null) {
                group.forEach { saveOutlook(it, tr("No rain radar here")) }
                continue
            }
            try {
                val keys = rainSource.latestKeys(kind)
                val (newestKey, newestTime) = keys.firstOrNull() ?: continue
                val cur: RainFrame = rainSource.load(kind, newestKey, newestTime)
                var older: RainFrame? = null
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
                    val now = System.currentTimeMillis()
                    val forecast = RainForecaster.analyse(kind, grid, prev, now)
                    val summary = ForecastText.summary(forecast, now)
                    saveOutlook(place, summary)
                    if (place.rain) maybeNotifyRain(place, forecast)
                    if (place.id == widgetId) {
                        runCatching { RainBarWidget.publish(context, place, forecast) }
                        runCatching { RadarWidget.publish(context, place, grid, kind, summary, widgetWarning) }
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

    /**
     * Rain alerts, at most one per place per 90 minutes, outside quiet hours,
     * when the rain reaches the user's chosen strength:
     * - "rain on its way": a forecast arrival still ahead, at medium confidence
     *   or better, or at any confidence when it's heavy (showers and storms are
     *   exactly when confidence is low and a warning matters most; the wording
     *   then says "possible");
     * - "rain has started": it's raining at the place now and wasn't at the
     *   previous check, which catches showers that form overhead or arrive
     *   between checks, which a forecast can't announce in advance.
     */
    @SuppressLint("MissingPermission")
    private fun maybeNotifyRain(place: Place, f: RainForecast) {
        val now = System.currentTimeMillis()
        val stateKey = "rain_state_${place.id}"
        val wasRaining = prefs.getBoolean(stateKey, false)
        if (f.confidence != Confidence.NONE) prefs.edit().putBoolean(stateKey, f.result.rainingNow).apply()
        if (f.confidence == Confidence.NONE) return
        val nowIntensity = app.hookline.radar.core.data.RainIntensity.of(f.kind, f.series[0])
        val started = f.result.rainingNow && !wasRaining && nowIntensity >= alertPrefs.rainMin
        val window = f.arrivalWindow
        val coming = !f.result.rainingNow && window != null &&
            f.minutesFromNow(window.last, now) > 0 &&
            f.peak >= alertPrefs.rainMin &&
            (f.confidence != Confidence.LOW || f.peak >= app.hookline.radar.core.data.RainIntensity.HEAVY)
        if (!started && !coming) return
        if (alertPrefs.inQuietHours(now)) return
        val key = "rain_notified_${place.id}"
        if (now - prefs.getLong(key, 0) < 90 * 60_000L) return
        if (!canNotify()) return
        prefs.edit().putLong(key, now).apply()
        val text = if (started) tr("Rain started since the last check (radar %s).", clock(f.observedMillis))
        else tr("%s. Forecast from the latest radar; showers can still form or fade.", ForecastText.confidence(f.confidence))
        val n = NotificationCompat.Builder(context, HooklineApp.CHANNEL_RAIN)
            .setSmallIcon(app.hookline.radar.R.drawable.ic_stat_hookline)
            .setContentTitle(tr("%s at %s", ForecastText.headline(f, now), place.name))
            .setContentText(text)
            .setContentIntent(RadarWidget.openAppIntent(context))
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(("rain" + place.id).hashCode(), n)
    }

    private fun clock(ms: Long) = DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

    /** Quiet hours hold back warnings, except severe ones if the user allows them. */
    private fun mayNotifyWarning(severe: Boolean): Boolean =
        !alertPrefs.inQuietHours() || (severe && alertPrefs.quietLetSevere)

    /** Red MeteoAlarm warnings; NWS tornado and flash flood warnings, and anything tagged as an emergency or destructive. */
    private fun severe(w: WarningItem): Boolean = w.level == WarningLevel.RED

    private fun severe(a: Alert): Boolean {
        val text = (a.description.orEmpty() + " " + a.headline.orEmpty()).uppercase()
        return a.hazard == Hazard.TORNADO_WARNING || a.hazard == Hazard.TORNADO_EMERGENCY ||
            a.hazard == Hazard.FLASH_FLOOD_WARNING || "EMERGENCY" in text || "DESTRUCTIVE" in text
    }

    /**
     * Duplicate suppression for re-issued warnings. Services publish an
     * updated or extended warning under a new id; that shouldn't buzz the
     * phone again. A warning counts as a repeat only if it's the same event
     * for the same place, its time span overlaps the previous one, it isn't
     * more severe, and it isn't severe at all (those always notify).
     */
    private fun isNew(placeId: Long, w: WarningItem, severe: Boolean): Boolean {
        val k = "recent_${placeId}_${w.event.lowercase().hashCode()}"
        val now = System.currentTimeMillis()
        val onset = w.onset?.toEpochMilli() ?: now
        val expires = w.expires?.toEpochMilli() ?: (now + 3 * 3_600_000L)
        val prev = prefs.getString(k, null)?.split('|')?.mapNotNull { it.toLongOrNull() }?.takeIf { it.size == 3 }
        val overlaps = prev != null && onset <= prev[2] && expires >= prev[1]
        val repeat = overlaps && !severe && w.level.ordinal <= prev!![0]
        // Remember the latest level seen and the combined span of the overlapping warnings.
        val record = if (overlaps) "${w.level.ordinal}|${minOf(onset, prev!![1])}|${maxOf(expires, prev[2])}" else "${w.level.ordinal}|$onset|$expires"
        prefs.edit().putString(k, record).apply()
        return !repeat
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
        val title = if (until != null) tr("%s for %s until %s", w.event, place, until) else tr("%s for %s", w.event, place)
        val n = NotificationCompat.Builder(context, HooklineApp.CHANNEL_WARNINGS)
            .setSmallIcon(app.hookline.radar.R.drawable.ic_stat_hookline)
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
        val title = if (until != null) tr("%s for %s until %s", a.event, place, until) else tr("%s for %s", a.event, place)
        val n = NotificationCompat.Builder(context, HooklineApp.CHANNEL_WARNINGS)
            .setSmallIcon(app.hookline.radar.R.drawable.ic_stat_hookline)
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
        /** A sample notification, to check that alerts reach the user. */
        @SuppressLint("MissingPermission")
        fun sendTest(context: Context): Boolean {
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return false
            val n = NotificationCompat.Builder(context, HooklineApp.CHANNEL_WARNINGS)
                .setSmallIcon(app.hookline.radar.R.drawable.ic_stat_hookline)
                .setContentTitle(tr("Test alert from Hookline"))
                .setContentText(tr("Warnings and rain alerts will look like this."))
                .setContentIntent(RadarWidget.openAppIntent(context))
                .setAutoCancel(true)
                .build()
            context.getSystemService(NotificationManager::class.java).notify(4242, n)
            return true
        }

        /** Storm Mode switch, readable without building a checker (widgets). */
        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences("storm_mode", Context.MODE_PRIVATE).getBoolean("enabled", true)

        /** Rain grid: 121 × 2 km = ±120 km around each place. */
        const val CELL_KM = 2.0
        const val GRID = 121
    }
}
