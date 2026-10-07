package app.hookline.radar.core.data

import app.hookline.radar.core.radar.Grib2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * NOAA MRMS swath products for the contiguous US (NOAA Open Data on AWS,
 * GRIB2, 0.01°): where hail fell (MESH, maximum expected hail size) and
 * where radars detected rotation near the ground.
 */
class MrmsTracks(
    private val http: OkHttpClient,
    private val base: String = "https://noaa-mrms-pds.s3.amazonaws.com",
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class Product(val path: String, val label: String, val unit: String, val refreshMs: Long) {
        HAIL_24H("MESH_Max_1440min_00.50", "Hail swaths, last 24 h", "mm", 30 * 60_000L),
        ROTATION_60M("RotationTrack60min_00.50", "Rotation tracks, last hour", "1/s", 5 * 60_000L),
    }

    /** A downsampled grid (max-pooled so narrow swaths survive). Row 0 is the northern edge. */
    class Swath(
        val width: Int, val height: Int,
        val north: Double, val west: Double, val dLat: Double, val dLon: Double,
        /** Physical values; NaN where there's nothing. */
        val values: FloatArray,
        val timeMillis: Long,
    ) {
        val south: Double get() = north - height * dLat
        val east: Double get() = west + width * dLon

        fun valueAt(lat: Double, lon: Double): Float {
            val r = ((north - lat) / dLat).toInt(); val c = ((lon - west) / dLon).toInt()
            if (r !in 0 until height || c !in 0 until width) return Float.NaN
            return values[r * width + c]
        }
    }

    suspend fun latest(p: Product, pool: Int = 4): Swath {
        val now = Instant.ofEpochMilli(clock())
        var key: String? = null
        for (t in listOf(now, now.minusSeconds(86_400))) {
            val prefix = "CONUS/${p.path}/${DAY.format(t.atZone(ZoneOffset.UTC))}/"
            val xml = String(get("$base/?list-type=2&prefix=$prefix"), Charsets.UTF_8)
            // A day's listing can exceed one page (2-min products): continue to the last page.
            var keys = S3Listing.keys(xml)
            var token = S3Listing.continuationToken(xml)
            var guard = 0
            while (token != null && guard++ < 5) {
                val more = String(get("$base/?list-type=2&prefix=$prefix&continuation-token=" + java.net.URLEncoder.encode(token, "UTF-8")), Charsets.UTF_8)
                keys = S3Listing.keys(more).ifEmpty { keys }
                token = S3Listing.continuationToken(more)
            }
            key = keys.filter { it.endsWith(".grib2.gz") }.maxOrNull()
            if (key != null) break
        }
        val k = key ?: throw IOException("No ${p.label} available")
        val bytes = get("$base/$k")
        return withContext(Dispatchers.Default) { decode(bytes, pool, RainSource.mrmsTime(k) ?: clock()) }
    }

    private suspend fun get(url: String): ByteArray = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("MRMS HTTP ${r.code}")
            r.body?.bytes() ?: throw IOException("Empty body")
        }
    }

    companion object {
        private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

        /** Max-pools [pool]×[pool] blocks; values ≤ 0 (none, missing, no coverage) become NaN. */
        fun decode(bytes: ByteArray, pool: Int, time: Long): Swath {
            var w = 0; var h = 0; var out = FloatArray(0)
            var north = 0.0; var west = 0.0; var dLat = 0.0; var dLon = 0.0
            Grib2.decode(bytes, onGrid = { g ->
                w = g.ni / pool; h = g.nj / pool
                out = FloatArray(w * h) { Float.NaN }
                // Edges of the first cell (grid points are cell centres).
                dLat = kotlin.math.abs(g.dLat) * pool; dLon = g.dLon * pool
                north = g.lat1 + kotlin.math.abs(g.dLat) / 2; west = g.lon1 - g.dLon / 2
            }) { r, row ->
                val rr = r / pool
                if (rr >= h) return@decode
                val base = rr * w
                for (c in 0 until w * pool) {
                    val v = row[c]
                    if (v <= 0f) continue
                    val i = base + c / pool
                    val cur = out[i]
                    if (cur.isNaN() || v > cur) out[i] = v
                }
            }
            return Swath(w, h, north, west, dLat, dLon, out, time)
        }
    }
}
