package app.hookline.radar.core.data

import app.hookline.radar.core.odim.Odim
import app.hookline.radar.core.radar.Moment
import app.hookline.radar.core.radar.OdimAdapter
import app.hookline.radar.core.radar.SiteLocation
import app.hookline.radar.core.radar.Sweep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Dual-polarisation moments (CC and ZDR) for German radars, from the
 * Deutscher Wetterdienst open-data server (CC BY 4.0, "Source: Deutscher
 * Wetterdienst"). OPERA doesn't exchange dual-pol yet; DWD publishes the
 * lowest, terrain-following precipitation scan every 5 minutes.
 */
class DwdDualPol(
    private val http: OkHttpClient,
    private val base: String = "https://opendata.dwd.de/weather/radar/sites",
) {
    data class Result(val sweep: Sweep, val timeMillis: Long)

    @Volatile private var workingLayout: String? = null

    /** Latest sweep of [moment] (CC or ZDR) for an OPERA site id like "deess", or null if DWD has none. */
    suspend fun latest(siteId: String, moment: Moment): Result? {
        val code = dwdCode(siteId) ?: return null
        val product = PRODUCTS[moment] ?: return null
        // DWD has moved these folders before; try the known layouts and remember the one that works.
        val candidates = (listOfNotNull(workingLayout) + LAYOUTS).distinct()
        var found: Pair<String, String>? = null
        for (layout in candidates) {
            val dir = "$base/sweep_pcp_$product/$code/$layout"
            val listing = get(dir)?.let { String(it, Charsets.UTF_8) } ?: continue
            val file = newest(listing, product, code) ?: continue
            workingLayout = layout
            found = dir to file
            break
        }
        val (dir, file) = found ?: return null
        val bytes = get(dir + file) ?: return null
        return withContext(Dispatchers.Default) {
            val odim = Odim.read(bytes)
            val loc = if (odim.lat.isNaN()) null else SiteLocation(odim.lat.toFloat(), odim.lon.toFloat(), odim.heightM.toInt())
            val scan = odim.scans.firstOrNull() ?: return@withContext null
            val sweep = OdimAdapter.toSweep(scan, siteId, (scan.elevationDeg * 10).toInt(), loc) ?: return@withContext null
            if (!sweep.has(moment)) null else Result(sweep, scan.endMillis)
        }
    }

    private suspend fun get(url: String): ByteArray? = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            when {
                r.code == 404 -> null
                !r.isSuccessful -> throw IOException("DWD HTTP ${r.code}")
                else -> r.body?.bytes()
            }
        }
    }

    companion object {
        /** DWD's three-letter codes; the OPERA id is "de" + code. */
        val SITES = setOf(
            "asb", "boo", "drs", "eis", "ess", "fbg", "fld", "hnr", "isn", "mem", "neu", "nhb", "oft", "pro", "ros", "tur", "umd",
        )
        private val LAYOUTS = listOf("unfiltered/", "hdf5/unfiltered/", "hdf5/filter_polarimetric/", "hdf5/filter_simple/")
        private val PRODUCTS = mapOf(
            Moment.CORRELATION_COEFFICIENT to "rhohv",
            Moment.DIFFERENTIAL_REFLECTIVITY to "zdr",
        )

        fun dwdCode(siteId: String): String? =
            siteId.lowercase().takeIf { it.startsWith("de") }?.substring(2)?.takeIf { it in SITES }

        fun supports(siteId: String): Boolean = dwdCode(siteId) != null

        /**
         * Newest file in an Apache directory listing. Names carry a 16-digit
         * timestamp, e.g. ras07-pcpng01_sweeph5onem_rhohv_00-2026100712050000-ess-10410-hd5.
         */
        fun newest(listing: String, product: String, code: String): String? {
            val re = Regex("""href="([^"/]*sweeph5onem_u?${product}_00-(\d{16})-$code-\d+[-.]hd5)"""")
            return re.findAll(listing).maxByOrNull { it.groupValues[2] }?.groupValues?.get(1)
        }
    }
}
