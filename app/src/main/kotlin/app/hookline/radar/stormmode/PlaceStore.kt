package app.hookline.radar.stormmode

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import app.hookline.radar.i18n.tr

/**
 * A place Storm Mode watches. Id 0 is the automatic "Current location";
 * the others are saved by the user.
 */
data class Place(
    val id: Long,
    val name: String,
    val lat: Double,
    val lon: Double,
    /** MeteoAlarm region (Europe) or null (US / elsewhere). */
    val region: String? = null,
    val warnings: Boolean = true,
    val rain: Boolean = true,
    /** Last rain outlook, e.g. "Dry for the next hour", and when it was made. */
    val outlook: String? = null,
    val outlookAt: Long = 0,
)

class PlaceStore(context: Context) {
    private val prefs = context.getSharedPreferences("places", Context.MODE_PRIVATE)

    fun saved(): List<Place> = runCatching {
        val a = JSONArray(prefs.getString("list", "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Place(
                id = o.getLong("id"), name = o.getString("name"), lat = o.getDouble("lat"), lon = o.getDouble("lon"),
                region = o.optString("region").ifEmpty { null },
                warnings = o.optBoolean("warnings", true), rain = o.optBoolean("rain", true),
                outlook = o.optString("outlook").ifEmpty { null }, outlookAt = o.optLong("outlookAt", 0),
            )
        }
    }.getOrDefault(emptyList())

    private fun write(list: List<Place>) {
        val a = JSONArray()
        list.forEach { p ->
            a.put(
                JSONObject().put("id", p.id).put("name", p.name).put("lat", p.lat).put("lon", p.lon)
                    .put("region", p.region ?: "").put("warnings", p.warnings).put("rain", p.rain)
                    .put("outlook", p.outlook ?: "").put("outlookAt", p.outlookAt),
            )
        }
        prefs.edit().putString("list", a.toString()).apply()
    }

    fun add(name: String, lat: Double, lon: Double, region: String?): Place {
        val list = saved()
        val p = Place((list.maxOfOrNull { it.id } ?: 0) + 1, name.ifBlank { tr("Place %s", list.size + 1) }, lat, lon, region)
        write(list + p)
        return p
    }

    fun remove(id: Long) = write(saved().filter { it.id != id })

    fun update(id: Long, f: (Place) -> Place) = write(saved().map { if (it.id == id) f(it) else it })

    // Settings for the automatic "Current location" place.
    var currentWarnings: Boolean
        get() = prefs.getBoolean("current_warnings", true)
        set(v) = prefs.edit().putBoolean("current_warnings", v).apply()
    var currentRain: Boolean
        get() = prefs.getBoolean("current_rain", true)
        set(v) = prefs.edit().putBoolean("current_rain", v).apply()
    var currentOutlook: String?
        get() = prefs.getString("current_outlook", null)
        set(v) = prefs.edit().putString("current_outlook", v).putLong("current_outlook_at", System.currentTimeMillis()).apply()
    val currentOutlookAt: Long get() = prefs.getLong("current_outlook_at", 0)

    /** "Wi-Fi only" for rain checks (they download 1–3 MB each). */
    var rainOnWifiOnly: Boolean
        get() = prefs.getBoolean("rain_wifi_only", false)
        set(v) = prefs.edit().putBoolean("rain_wifi_only", v).apply()
}
