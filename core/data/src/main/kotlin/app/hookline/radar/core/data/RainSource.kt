package app.hookline.radar.core.data

import app.hookline.radar.core.odim.CompositeTile
import app.hookline.radar.core.odim.Odim
import app.hookline.radar.core.radar.Grib2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** One radar frame that can be cut into [LocalGrid]s around places. */
interface RainFrame {
    val timeMillis: Long
    fun grid(center: LatLon, cellKm: Double, size: Int): LocalGrid
}

/**
 * Rain data for "rain starting soon":
 * - Europe: OPERA RATE composite (mm/h, every 15 min, about 1.5 MB);
 * - US: MRMS ReflectivityAtLowestAltitude (dBZ, every 2 min).
 * Thresholds differ by source, see [thresholds].
 */
class RainSource(
    private val http: OkHttpClient,
    private val ord: OrdStore = OrdS3(http),
    private val mrmsBase: String = "https://noaa-mrms-pds.s3.amazonaws.com",
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class Kind { OPERA_RATE, MRMS_DBZ }

    /** Rain and heavy-rain thresholds in the source's unit. */
    fun thresholds(kind: Kind): Pair<Float, Float> = when (kind) {
        Kind.OPERA_RATE -> 0.2f to 7.5f // mm/h
        Kind.MRMS_DBZ -> 18f to 40f // dBZ
    }

    fun kindFor(p: LatLon): Kind? = when {
        Imagery.inMrms(p) && p.lat in 20.0..55.0 && p.lon in -130.0..-60.0 -> Kind.MRMS_DBZ
        Coverage.inOperaComposite(p) -> Kind.OPERA_RATE
        else -> null
    }

    /** Keys of the newest frame and one about 10–20 minutes older, newest first. */
    suspend fun latestKeys(kind: Kind): List<Pair<String, Long>> = when (kind) {
        Kind.OPERA_RATE -> operaKeys()
        Kind.MRMS_DBZ -> mrmsKeys()
    }

    suspend fun load(kind: Kind, key: String, time: Long): RainFrame = when (kind) {
        Kind.OPERA_RATE -> {
            val bytes = ord.fetch(key)
            withContext(Dispatchers.Default) {
                val g = Odim.read(bytes).grid ?: throw IOException("No grid in $key")
                val loc = CompositeTile.Locator(g)
                object : RainFrame {
                    override val timeMillis = time
                    override fun grid(center: LatLon, cellKm: Double, size: Int) =
                        LocalGrid.sample(center, cellKm, size, time) { lat, lon ->
                            val v = CompositeTile.valueAt(g, loc, lat, lon)
                            if (v.isNaN()) Float.NaN else if (v.isInfinite() || v < 0) 0f else v
                        }
                }
            }
        }
        Kind.MRMS_DBZ -> {
            val bytes = get("$mrmsBase/$key")
            withContext(Dispatchers.Default) { MrmsFrame(bytes, time) }
        }
    }

    private suspend fun operaKeys(): List<Pair<String, Long>> {
        val now = clock()
        val since = now - 75 * 60_000L
        val found = mutableListOf<Pair<String, Long>>()
        for (day in linkedSetOf(OrdKey.dayPrefix(since), OrdKey.dayPrefix(now))) {
            val folder = "${day}OPERA/COMP/"
            ord.list(folder, folder + "OPERA@" + OrdKey.stampOf(since))
                .filter { it.endsWith("@RATE.h5") }
                .mapNotNull { k -> OperaComposite.compositeKey(k)?.let { k to it.timeMillis } }
                .forEach { found += it }
        }
        return pickPair(found.sortedByDescending { it.second })
    }

    private suspend fun mrmsKeys(): List<Pair<String, Long>> {
        val now = Instant.ofEpochMilli(clock())
        val found = mutableListOf<Pair<String, Long>>()
        for (t in linkedSetOf(now.minusSeconds(3_600), now)) {
            val day = DAY.format(t.atZone(ZoneOffset.UTC))
            val prefix = "CONUS/$MRMS_PRODUCT/$day/"
            val after = prefix + "MRMS_${MRMS_PRODUCT}_" + STAMP.format(now.minusSeconds(3_600).atZone(ZoneOffset.UTC))
            val xml = String(get("$mrmsBase/?list-type=2&prefix=$prefix&start-after=$after"), Charsets.UTF_8)
            S3Listing.keys(xml).mapNotNull { k -> mrmsTime(k)?.let { k to it } }.forEach { found += it }
        }
        return pickPair(found.distinct().sortedByDescending { it.second })
    }

    /** Newest, plus the first one at least 10 minutes older (for motion). */
    private fun pickPair(sorted: List<Pair<String, Long>>): List<Pair<String, Long>> {
        val newest = sorted.firstOrNull() ?: return emptyList()
        val older = sorted.firstOrNull { newest.second - it.second in 600_000L..1_500_000L }
        return listOfNotNull(newest, older)
    }

    private suspend fun get(url: String): ByteArray = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} for $url")
            r.body?.bytes() ?: throw IOException("Empty body")
        }
    }

    /** An MRMS CONUS frame, kept at 0.02° (every second point) to save memory. */
    private class MrmsFrame(bytes: ByteArray, override val timeMillis: Long) : RainFrame {
        private val step = 2
        private var ni = 0; private var nj = 0
        private var lat1 = 0.0; private var lon1 = 0.0; private var dLat = 0.0; private var dLon = 0.0
        private lateinit var data: ShortArray // dBZ * 10, Short.MIN_VALUE = missing

        init {
            Grib2.decode(bytes, onGrid = { g ->
                ni = g.ni / step; nj = g.nj / step
                lat1 = g.lat1; lon1 = g.lon1; dLat = g.dLat * step; dLon = g.dLon * step
                data = ShortArray(ni * nj) { Short.MIN_VALUE }
            }) { r, row ->
                if (r % step == 0 && r / step < nj) {
                    val rr = r / step
                    for (c in 0 until ni) {
                        val v = row[c * step]
                        // MRMS: -99 = no echo or missing, -999 = outside coverage.
                        data[rr * ni + c] = if (v < -900f) Short.MIN_VALUE else (v.coerceAtLeast(0f) * 10).roundToInt().coerceAtMost(900).toShort()
                    }
                }
            }
        }

        private fun value(lat: Double, lon: Double): Float {
            val r = ((lat - lat1) / dLat).roundToInt(); val c = ((lon - lon1) / dLon).roundToInt()
            if (r !in 0 until nj || c !in 0 until ni) return Float.NaN
            val v = data[r * ni + c]
            return if (v == Short.MIN_VALUE) Float.NaN else (v / 10f).coerceAtLeast(0f)
        }

        override fun grid(center: LatLon, cellKm: Double, size: Int) =
            LocalGrid.sample(center, cellKm, size, timeMillis) { lat, lon -> value(lat, lon) }
    }

    companion object {
        const val MRMS_PRODUCT = "ReflectivityAtLowestAltitude_00.50"
        private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

        /** `.../MRMS_ReflectivityAtLowestAltitude_00.50_20261006-000200.grib2.gz` → epoch millis. */
        fun mrmsTime(key: String): Long? {
            val m = Regex("""_(\d{8}-\d{6})\.grib2(\.gz)?$""").find(key) ?: return null
            return runCatching { LocalDateTime.parse(m.groupValues[1], STAMP).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
        }
    }
}
