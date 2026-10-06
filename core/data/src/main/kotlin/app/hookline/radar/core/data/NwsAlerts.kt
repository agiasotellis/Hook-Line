package app.hookline.radar.core.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Instant
import java.util.Locale

data class LatLon(val lat: Double, val lon: Double)

enum class Hazard { TORNADO_WARNING, TORNADO_EMERGENCY, SEVERE_WARNING, FLASH_FLOOD_WARNING, TORNADO_WATCH, SEVERE_WATCH, OTHER }

data class Alert(
    val id: String,
    val event: String,
    val headline: String?,
    val severity: String?,
    val expires: Instant?,
    val description: String?,
    val instruction: String?,
    /** Outer rings only; NWS storm-based warnings are simple polygons. */
    val polygons: List<List<LatLon>>,
    val tornadoDetection: String? = null,
    val maxHailInches: Double? = null,
    val maxWindMph: Int? = null,
) {
    val hazard: Hazard
        get() = when {
            event == "Tornado Warning" && description.orEmpty().contains("TORNADO EMERGENCY", ignoreCase = true) -> Hazard.TORNADO_EMERGENCY
            event == "Tornado Warning" -> Hazard.TORNADO_WARNING
            event == "Severe Thunderstorm Warning" -> Hazard.SEVERE_WARNING
            event == "Flash Flood Warning" -> Hazard.FLASH_FLOOD_WARNING
            event == "Tornado Watch" -> Hazard.TORNADO_WATCH
            event == "Severe Thunderstorm Watch" -> Hazard.SEVERE_WATCH
            else -> Hazard.OTHER
        }

    fun covers(p: LatLon): Boolean = polygons.any { Geo.contains(it, p) }
}

/**
 * NWS API client (api.weather.gov). Free; requires an identifying User-Agent.
 * Watches are zone/county based and may arrive without geometry — callers
 * should fall back to the `point` query, which already filters by location.
 */
class NwsAlerts(
    private val http: OkHttpClient,
    private val userAgent: String,
    private val base: String = "https://api.weather.gov",
) {
    suspend fun activeAt(point: LatLon): List<Alert> =
        parse(get("$base/alerts/active?point=%.4f,%.4f".format(Locale.US, point.lat, point.lon)))

    suspend fun activeInArea(stateCode: String): List<Alert> = parse(get("$base/alerts/active?area=$stateCode"))

    /** All active alerts of the given event types nationwide, e.g. every tornado warning. */
    suspend fun activeEvents(events: List<String>): List<Alert> {
        val url = "$base/alerts/active".toHttpUrl().newBuilder()
            .addQueryParameter("event", events.joinToString(","))
            .build()
        return parse(get(url.toString()))
    }

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "application/geo+json")
            .build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("NWS HTTP ${r.code}")
            r.body?.string().orEmpty()
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(geoJson: String): List<Alert> {
            val root = json.parseToJsonElement(geoJson).jsonObject
            val features = root["features"]?.jsonArray ?: return emptyList()
            return features.mapNotNull { f ->
                val obj = f.jsonObject
                val p = obj["properties"]?.jsonObject ?: return@mapNotNull null
                val params = p["parameters"]?.jsonObject
                Alert(
                    id = p.str("id") ?: obj.str("id") ?: return@mapNotNull null,
                    event = p.str("event") ?: "Alert",
                    headline = p.str("headline"),
                    severity = p.str("severity"),
                    expires = (p.str("ends") ?: p.str("expires"))?.let { runCatching { Instant.parse(it) }.getOrNull() },
                    description = p.str("description"),
                    instruction = p.str("instruction"),
                    polygons = polygons(obj["geometry"]),
                    tornadoDetection = params.firstParam("tornadoDetection"),
                    maxHailInches = params.firstParam("maxHailSize")?.toDoubleOrNull(),
                    maxWindMph = params.firstParam("maxWindGust")?.filter { it.isDigit() }?.toIntOrNull(),
                )
            }
        }

        private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull

        private fun JsonObject?.firstParam(k: String): String? =
            (this?.get(k) as? JsonArray)?.firstOrNull()?.jsonPrimitive?.contentOrNull

        private fun polygons(geometry: JsonElement?): List<List<LatLon>> {
            if (geometry == null || geometry is JsonNull) return emptyList()
            val g = geometry.jsonObject
            val coords = g["coordinates"]?.jsonArray ?: return emptyList()
            fun ring(a: JsonArray) = a.map { pt ->
                val xy = pt.jsonArray
                LatLon(lat = xy[1].jsonPrimitive.doubleOrNull ?: 0.0, lon = xy[0].jsonPrimitive.doubleOrNull ?: 0.0)
            }
            return when (g.str("type")) {
                "Polygon" -> listOf(ring(coords[0].jsonArray))
                "MultiPolygon" -> coords.map { ring(it.jsonArray[0].jsonArray) }
                else -> emptyList()
            }
        }
    }
}

object Geo {
    /** Ray-casting point-in-polygon on lat/lon (fine at warning scale). */
    fun contains(ring: List<LatLon>, p: LatLon): Boolean {
        var inside = false
        var j = ring.size - 1
        for (i in ring.indices) {
            val a = ring[i]; val b = ring[j]
            if ((a.lat > p.lat) != (b.lat > p.lat) &&
                p.lon < (b.lon - a.lon) * (p.lat - a.lat) / (b.lat - a.lat) + a.lon
            ) inside = !inside
            j = i
        }
        return inside
    }
}
