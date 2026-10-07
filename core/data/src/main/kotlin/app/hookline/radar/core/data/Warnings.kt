package app.hookline.radar.core.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.time.Instant

/** Warning severity on the shared 1-4 scale (MeteoAlarm awareness levels; NWS mapped onto it). */
enum class WarningLevel(val label: String, val argb: Int) {
    MINOR("Minor", 0xFF9AA5AE.toInt()),
    YELLOW("Yellow", 0xFFF2D23B.toInt()),
    ORANGE("Orange", 0xFFF2902B.toInt()),
    RED("Red", 0xFFE5312B.toInt()),
}

/**
 * One active warning from any source, ready for the list and the map.
 * Polygons are outer rings in lat/lon.
 */
data class WarningItem(
    val id: String,
    val source: String,
    val event: String,
    val headline: String,
    val area: String,
    val level: WarningLevel,
    val onset: Instant?,
    val expires: Instant?,
    val description: String?,
    val instruction: String?,
    val polygons: List<List<LatLon>>,
    /** Hazard colour for NWS warnings; level colour otherwise. */
    val argb: Int,
    val web: String? = null,
    /** MeteoAlarm region codes (EMMA_ID) this warning covers; empty for NWS. */
    val codes: List<String> = emptyList(),
) {
    /** Rough bounding box (s, w, n, e) of all polygons, or null when there are none. */
    val box: DoubleArray? by lazy {
        if (polygons.isEmpty()) null else {
            var s = 90.0; var w = 180.0; var n = -90.0; var e = -180.0
            for (r in polygons) for (p in r) { s = minOf(s, p.lat); n = maxOf(n, p.lat); w = minOf(w, p.lon); e = maxOf(e, p.lon) }
            doubleArrayOf(s, w, n, e)
        }
    }

    fun intersects(south: Double, west: Double, north: Double, east: Double): Boolean =
        box?.let { it[0] <= north && it[2] >= south && it[1] <= east && it[3] >= west } ?: false

    fun covers(p: LatLon): Boolean = polygons.any { Geo.contains(it, p) }
    fun isActive(now: Instant): Boolean = expires == null || expires.isAfter(now)
}

/** NWS alerts in the shared warning model. */
fun Alert.toWarningItem(): WarningItem {
    val level = when (hazard) {
        Hazard.TORNADO_EMERGENCY, Hazard.TORNADO_WARNING -> WarningLevel.RED
        Hazard.SEVERE_WARNING, Hazard.FLASH_FLOOD_WARNING -> WarningLevel.ORANGE
        Hazard.TORNADO_WATCH, Hazard.SEVERE_WATCH -> WarningLevel.YELLOW
        Hazard.OTHER -> WarningLevel.MINOR
    }
    val argb = when (hazard) {
        Hazard.TORNADO_EMERGENCY -> 0xFFB000B0.toInt()
        Hazard.TORNADO_WARNING -> 0xFFE0245E.toInt()
        Hazard.SEVERE_WARNING -> 0xFFF5A623.toInt()
        Hazard.FLASH_FLOOD_WARNING -> 0xFF2E9E5B.toInt()
        Hazard.TORNADO_WATCH -> 0xFFFFD84D.toInt()
        Hazard.SEVERE_WATCH -> 0xFFE8A6C8.toInt()
        Hazard.OTHER -> level.argb
    }
    return WarningItem(
        id = id, source = "NWS", event = event, headline = headline ?: event, area = headline ?: "",
        level = level, onset = null, expires = expires, description = description, instruction = instruction,
        polygons = polygons, argb = argb,
    )
}

/**
 * MeteoAlarm warning regions (EMMA_ID) with simplified outlines, bundled
 * from EUMETNET MeteoAlarm's geocodes (CC BY 4.0). Packed by
 * tools/meteoalarm/pack_regions.py; ~330 KB for all 2,003 regions.
 */
class WarningRegions private constructor(val regions: Map<String, Region>) {

    class Region(val code: String, val name: String, val polygons: List<List<LatLon>>) {
        val country: String get() = WarningRegions.countryOf(code)
        private val box: DoubleArray = run {
            var s = 90.0; var w = 180.0; var n = -90.0; var e = -180.0
            for (r in polygons) for (p in r) { s = minOf(s, p.lat); n = maxOf(n, p.lat); w = minOf(w, p.lon); e = maxOf(e, p.lon) }
            doubleArrayOf(s, w, n, e)
        }

        fun intersects(south: Double, west: Double, north: Double, east: Double): Boolean =
            box[0] <= north && box[2] >= south && box[1] <= east && box[3] >= west

        fun contains(p: LatLon): Boolean =
            p.lat in box[0]..box[2] && p.lon in box[1]..box[3] && polygons.any { Geo.contains(it, p) }
    }

    /** The region containing [p], if it is inside MeteoAlarm coverage. */
    fun regionAt(p: LatLon): Region? = regions.values.firstOrNull { it.contains(p) }

    /** Feed countries (ISO 3166 alpha-2) with regions in the box. */
    fun countriesIn(south: Double, west: Double, north: Double, east: Double): Set<String> =
        regions.values.filter { it.intersects(south, west, north, east) }.map { it.country }.toSet()

    companion object {
        /** MeteoAlarm feed names by country code. */
        val FEEDS: Map<String, String> = mapOf(
            "AT" to "austria", "BA" to "bosnia-herzegovina", "BE" to "belgium", "BG" to "bulgaria", "HR" to "croatia",
            "CY" to "cyprus", "CZ" to "czechia", "DK" to "denmark", "EE" to "estonia", "FI" to "finland", "FR" to "france",
            "DE" to "germany", "GR" to "greece", "HU" to "hungary", "IS" to "iceland", "IE" to "ireland", "IL" to "israel",
            "IT" to "italy", "LV" to "latvia", "LT" to "lithuania", "LU" to "luxembourg", "MT" to "malta", "MD" to "moldova",
            "ME" to "montenegro", "NL" to "netherlands", "MK" to "republic-of-north-macedonia", "NO" to "norway",
            "PL" to "poland", "PT" to "portugal", "RO" to "romania", "RS" to "serbia", "SK" to "slovakia", "SI" to "slovenia",
            "ES" to "spain", "SE" to "sweden", "CH" to "switzerland", "UA" to "ukraine", "GB" to "united-kingdom",
        )

        /** Irish regions use the old "EI" prefix. */
        fun countryOf(code: String): String = code.take(2).uppercase().let { if (it == "EI") "IE" else it }

        @Volatile private var cached: WarningRegions? = null

        /** Loads the bundled outlines once (about 50 ms). */
        fun bundled(): WarningRegions = cached ?: synchronized(this) {
            cached ?: (WarningRegions::class.java.getResourceAsStream("/meteoalarm/regions.bin")
                ?: throw IOException("Warning regions resource missing")).use { read(it) }.also { cached = it }
        }

        fun read(input: InputStream): WarningRegions {
            val d = DataInputStream(input.buffered())
            val magic = ByteArray(4).also { d.readFully(it) }
            if (String(magic, Charsets.US_ASCII) != "HLRG") throw IOException("Bad regions file")
            val version = java.lang.Short.reverseBytes(d.readShort()).toInt()
            if (version != 1) throw IOException("Unsupported regions version $version")
            val count = Integer.reverseBytes(d.readInt())
            val out = LinkedHashMap<String, Region>(count * 2)
            repeat(count) {
                val code = String(ByteArray(d.readUnsignedByte()).also { d.readFully(it) }, Charsets.UTF_8)
                val name = String(ByteArray(d.readUnsignedByte()).also { d.readFully(it) }, Charsets.UTF_8)
                val nPoly = java.lang.Short.toUnsignedInt(java.lang.Short.reverseBytes(d.readShort()))
                val polys = ArrayList<List<LatLon>>(nPoly)
                repeat(nPoly) {
                    val n = Integer.reverseBytes(d.readInt())
                    var lat = 0L; var lon = 0L
                    val ring = ArrayList<LatLon>(n)
                    repeat(n) {
                        lat += unzigzag(varint(d)); lon += unzigzag(varint(d))
                        ring += LatLon(lat / 1e4, lon / 1e4)
                    }
                    polys += ring
                }
                out[code] = Region(code, name, polys)
            }
            return WarningRegions(out)
        }

        private fun varint(d: DataInputStream): Long {
            var result = 0L; var shift = 0
            while (true) {
                val b = d.readUnsignedByte()
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }

        private fun unzigzag(n: Long): Long = (n ushr 1) xor -(n and 1)
    }
}

/**
 * EUMETNET MeteoAlarm: official warnings from European weather services, one
 * JSON feed per country (CC BY 4.0, attribution "MeteoAlarm / EUMETNET").
 */
class MeteoAlarm(
    private val http: OkHttpClient,
    private val regions: () -> WarningRegions = WarningRegions::bundled,
    private val base: String = "https://feeds.meteoalarm.org/api/v1/warnings/feeds-",
) {
    suspend fun warnings(country: String): List<WarningItem> {
        val feed = WarningRegions.FEEDS[country] ?: return emptyList()
        val body = withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url(base + feed).build()).execute().use { r ->
                if (!r.isSuccessful) throw IOException("MeteoAlarm HTTP ${r.code}")
                r.body?.string().orEmpty()
            }
        }
        return withContext(Dispatchers.Default) { parse(body, regions()) }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Parses a feed. Each warning becomes one item; areas resolve to the
         * bundled region outlines by EMMA_ID. English text is preferred.
         * Level 1 ("green", no warning) entries are dropped.
         */
        fun parse(body: String, regions: WarningRegions): List<WarningItem> {
            val root = json.parseToJsonElement(body)
            val list = (root as? JsonObject)?.get("warnings") as? JsonArray ?: return emptyList()
            val out = ArrayList<WarningItem>()
            for (w in list) {
                val alert = (w as? JsonObject)?.get("alert")?.jsonObject ?: continue
                val infos = (alert["info"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
                val info = infos.firstOrNull { it.str("language")?.startsWith("en") == true } ?: infos.firstOrNull() ?: continue
                val params = (info["parameter"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
                    .associate { (it.str("valueName") ?: "") to (it.str("value") ?: "") }
                val levelNum = params["awareness_level"]?.substringBefore(';')?.trim()?.toIntOrNull() ?: continue
                val level = when (levelNum) {
                    2 -> WarningLevel.YELLOW
                    3 -> WarningLevel.ORANGE
                    4 -> WarningLevel.RED
                    else -> continue
                }
                val type = params["awareness_type"]?.substringAfter(';')?.trim()?.takeIf { it.isNotEmpty() }
                val areas = (info["area"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
                val codes = areas.flatMap { a ->
                    (a["geocode"] as? JsonArray)?.mapNotNull { g ->
                        val o = g as? JsonObject
                        if (o?.str("valueName") == "EMMA_ID") o.str("value") else null
                    }.orEmpty()
                }
                val polys = codes.flatMap { regions.regions[it]?.polygons.orEmpty() }
                val areaNames = areas.mapNotNull { it.str("areaDesc") }.distinct().joinToString(", ")
                val event = type?.let { "${level.label} warning: $it" } ?: (info.str("event") ?: "${level.label} warning")
                out += WarningItem(
                    id = alert.str("identifier") ?: (event + areaNames),
                    source = "MeteoAlarm",
                    event = event,
                    headline = info.str("headline") ?: event,
                    area = areaNames,
                    level = level,
                    onset = info.str("onset")?.let(::instant),
                    expires = info.str("expires")?.let(::instant),
                    description = info.str("description"),
                    instruction = info.str("instruction"),
                    polygons = polys,
                    argb = level.argb,
                    web = info.str("web"),
                    codes = codes,
                )
            }
            return out
        }

        private fun instant(s: String): Instant? = runCatching { java.time.OffsetDateTime.parse(s).toInstant() }.getOrNull()

        private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull

        @Suppress("unused")
        private fun JsonElement.arr(): JsonArray? = runCatching { jsonArray }.getOrNull()
    }
}
