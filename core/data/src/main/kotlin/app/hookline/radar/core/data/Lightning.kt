package app.hookline.radar.core.data

import app.hookline.radar.core.hdf5.Hdf5File
import app.hookline.radar.core.hdf5.Hdf5Object
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

data class Flash(val lat: Float, val lon: Float, val timeMillis: Long)

/**
 * Lightning flashes from the GOES Geostationary Lightning Mapper (GLM L2
 * LCFA), NOAA Open Data on AWS: one netCDF-4 file every 20 seconds per
 * satellite, about 200–300 KB. Covers the Americas only (GLM ends near
 * 23° W); NOAA data, free for any use with attribution.
 */
class GlmLightning(
    private val http: OkHttpClient,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** GOES-West for the Pacific side, GOES-East otherwise; null outside GLM coverage. */
    fun bucketFor(p: LatLon): String? = when {
        p.lat !in -57.0..57.0 -> null
        p.lon in -180.0..-115.0 || p.lon in 160.0..180.0 -> "noaa-goes18"
        p.lon in -115.0..-25.0 -> "noaa-goes19"
        else -> null
    }

    /**
     * New flashes since [sinceMillis] from files not in [known].
     * Returns the flashes and the file keys read (add them to [known]).
     */
    suspend fun fetchNew(bucket: String, sinceMillis: Long, known: Set<String>, maxFiles: Int = 20): Pair<List<Flash>, List<String>> {
        val now = clock()
        val keys = mutableListOf<String>()
        for (t in linkedSetOf(sinceMillis, now)) {
            val prefix = hourPrefix(t)
            val xml = String(get("https://$bucket.s3.amazonaws.com/?list-type=2&prefix=$prefix"), Charsets.UTF_8)
            keys += S3Listing.keys(xml)
        }
        val wanted = keys.distinct()
            .filter { it !in known && (startTime(it) ?: 0L) >= sinceMillis - 20_000 }
            .sortedBy { startTime(it) }
            .takeLast(maxFiles)
        val flashes = coroutineScope {
            wanted.chunked(4).flatMap { batch ->
                batch.map { k ->
                    async {
                        runCatching { parse(get("https://$bucket.s3.amazonaws.com/$k"), startTime(k) ?: now) }.getOrDefault(emptyList())
                    }
                }.awaitAll().flatten()
            }
        }
        return flashes to wanted
    }

    private suspend fun get(url: String): ByteArray = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("GLM HTTP ${r.code}")
            r.body?.bytes() ?: throw IOException("Empty body")
        }
    }

    companion object {
        fun hourPrefix(millis: Long): String {
            val t = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC)
            return "GLM-L2-LCFA/%04d/%03d/%02d/".format(t.year, t.dayOfYear, t.hour)
        }

        /** `OR_GLM-L2-LCFA_G19_s20262791200000_e..._c....nc` → start time (last digit is tenths). */
        fun startTime(key: String): Long? {
            val m = Regex("""_s(\d{4})(\d{3})(\d{2})(\d{2})(\d{2})(\d)_""").find(key) ?: return null
            val (y, doy, h, mi, s, tenth) = m.destructured
            val day = LocalDate.ofYearDay(y.toInt(), doy.toInt())
            return day.atTime(h.toInt(), mi.toInt(), s.toInt()).toInstant(ZoneOffset.UTC).toEpochMilli() + tenth.toInt() * 100L
        }

        /**
         * Reads flash positions and times. Packing attributes (scale_factor,
         * add_offset, _Unsigned) are read from the file, never assumed.
         */
        fun parse(bytes: ByteArray, fileStart: Long): List<Flash> {
            val f = Hdf5File.open(bytes)
            val root = f.root()
            val lat = values(root.child("flash_lat"))
            val lon = values(root.child("flash_lon"))
            val n = minOf(lat.size, lon.size)
            val start = root.attributes()["time_coverage_start"]?.takeIf { it.isString() }?.asString()
                ?.let { runCatching { Instant.parse(it.trim().replace(" ", "T").let { s -> if (s.endsWith("Z")) s else s + "Z" }).toEpochMilli() }.getOrNull() }
                ?: fileStart
            val offsets = runCatching { values(root.child("flash_time_offset_of_first_event")) }.getOrNull()
            return (0 until n).mapNotNull { i ->
                val la = lat[i]; val lo = lon[i]
                if (la.isNaN() || lo.isNaN() || la !in -90.0..90.0) null
                else Flash(la.toFloat(), lo.toFloat(), start + ((offsets?.getOrNull(i) ?: 0.0) * 1000).toLong())
            }
        }

        private fun values(o: Hdf5Object?): DoubleArray {
            o ?: return DoubleArray(0)
            val raw = o.readRaw()
            val type = o.datatype()
            val count = raw.size / type.size
            val attrs = o.attributes()
            val scale = attrs["scale_factor"]?.asDouble() ?: 1.0
            val offset = attrs["add_offset"]?.asDouble() ?: 0.0
            val unsigned = attrs["_Unsigned"]?.takeIf { it.isString() }?.asString()?.trim()?.equals("true", true) == true
            val fill = attrs["_FillValue"]?.let { runCatching { it.asDouble() }.getOrNull() }
            return DoubleArray(count) { i ->
                var v = type.getDouble(raw, i)
                if (fill != null && v == fill) return@DoubleArray Double.NaN
                if (unsigned && v < 0) v += Math.pow(2.0, 8.0 * type.size)
                v * scale + offset
            }
        }
    }
}
