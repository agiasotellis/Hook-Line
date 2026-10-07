package app.hookline.radar.core.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * A map tile source for imagery drawn by the base map: either an XYZ tile
 * template or a WMS template with MapLibre's {bbox-epsg-3857} token.
 * [west]/[east] limit where it is drawn, so neighbouring satellites don't overlap.
 */
data class ImagerySource(
    val id: String,
    val url: String,
    val maxZoom: Int,
    val west: Double,
    val east: Double,
    val south: Double = -75.0,
    val north: Double = 75.0,
    val attribution: String,
)

/**
 * Open imagery for places without open radar:
 * - infrared satellite worldwide (NASA GIBS for GOES and Himawari; EUMETSAT
 *   for Meteosat over Europe, Africa and the Indian Ocean),
 * - the NWS MRMS radar mosaic for the US, shown when zoomed out.
 */
object Imagery {
    const val GIBS_ATTRIBUTION = "Satellite: NASA GIBS, NOAA GOES, JMA Himawari"
    fun eumetsatAttribution(year: Int) = "Contains modified EUMETSAT Meteosat data $year"
    const val MRMS_ATTRIBUTION = "Radar mosaic: NOAA / NWS MRMS"

    private const val GIBS = "https://gibs.earthdata.nasa.gov/wmts/epsg3857/nrt/wmts.cgi?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0" +
        "&STYLE=default&TILEMATRIXSET=GoogleMapsCompatible_Level6&FORMAT=image%2Fjpeg&TILEMATRIX={z}&TILEROW={y}&TILECOL={x}&LAYER="

    /**
     * Longitude bands, each given to the satellite with the best view of it:
     * GOES-West (137.2 W), GOES-East (75.2 W), Meteosat 0 deg, Meteosat IODC (45.5 E), Himawari (140.7 E).
     */
    fun satellites(eumetsat: EumetsatLayers?, now: Instant = Instant.now()): List<ImagerySource> {
        val out = mutableListOf(
            ImagerySource("sat-goes-w", GIBS + "GOES-West_ABI_Band13_Clean_Infrared_v0_NRT_ZENJPEG", 6, -180.0, -105.0, attribution = GIBS_ATTRIBUTION),
            ImagerySource("sat-goes-e", GIBS + "GOES-East_ABI_Band13_Clean_Infrared_v0_NRT_ZENJPEG", 6, -105.0, -30.0, attribution = GIBS_ATTRIBUTION),
            ImagerySource("sat-himawari", GIBS + "Himawari_AHI_Band13_Clean_Infrared_v0_NRT_ZENJPEG", 6, 85.0, 180.0, attribution = GIBS_ATTRIBUTION),
        )
        if (eumetsat != null) {
            val time = eumetsatTime(now)
            val credit = eumetsatAttribution(now.atZone(ZoneOffset.UTC).year)
            eumetsat.zeroDegree?.let {
                out += ImagerySource("sat-msg-0", wms(it, time), 7, -30.0, 42.0, attribution = credit)
            }
            eumetsat.indianOcean?.let {
                out += ImagerySource("sat-msg-iodc", wms(it, time), 7, 42.0, 85.0, attribution = credit)
            }
        }
        return out
    }

    /**
     * EUMETSAT imagery is free (CC BY 4.0) at hourly resolution; we always
     * ask for the last full hour that has certainly been processed.
     */
    fun eumetsatTime(now: Instant): String =
        DateTimeFormatter.ISO_INSTANT.format(now.minus(25, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.HOURS))

    private fun wms(layer: String, time: String): String =
        "https://view.eumetsat.int/geoserver/wms?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&STYLES=&CRS=EPSG:3857" +
            "&WIDTH=256&HEIGHT=256&FORMAT=image/jpeg&BBOX={bbox-epsg-3857}&TIME=$time&LAYERS=$layer"

    /**
     * NWS MRMS base reflectivity (public domain), rendered by NOAA's map
     * server for each tile. [stamp] changes the URL so new mosaics load
     * (it updates about every 10 minutes); the server ignores it.
     */
    fun mrms(stamp: Long): ImagerySource = ImagerySource(
        id = "mrms",
        url = "https://mapservices.weather.noaa.gov/eventdriven/rest/services/radar/radar_base_reflectivity/MapServer/export" +
            "?bbox={bbox-epsg-3857}&bboxSR=3857&imageSR=3857&size=256,256&format=png32&transparent=true&f=image&_=$stamp",
        maxZoom = 10, west = -180.0, east = -60.0, south = 10.0, north = 72.0,
        attribution = MRMS_ATTRIBUTION,
    )

    /** Where the NWS mosaic has data: CONUS and southern Canada, Alaska, Hawaii, Puerto Rico, Guam. */
    fun inMrms(p: LatLon): Boolean =
        (p.lat in 20.0..55.0 && p.lon in -130.0..-60.0) ||
            (p.lat in 51.0..72.0 && p.lon in -170.0..-129.0) ||
            (p.lat in 18.0..23.0 && p.lon in -161.0..-154.0) ||
            (p.lat in 17.0..19.5 && p.lon in -68.5..-64.5) ||
            (p.lat in 12.5..14.5 && p.lon in 144.0..146.0)
}

/** EUMETView layer names, discovered at runtime from GetCapabilities. */
data class EumetsatLayers(val zeroDegree: String?, val indianOcean: String?) {
    companion object {
        const val CAPABILITIES = "https://view.eumetsat.int/geoserver/wms?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetCapabilities"

        private val NAME = Regex("<Name>\\s*([A-Za-z0-9_\\-]+:[A-Za-z0-9_\\-]+)\\s*</Name>")

        /** Picks infrared layers by name: MTG first (it replaces MSG at 0 deg), then MSG. */
        fun pick(names: Collection<String>): EumetsatLayers {
            val lower = names.associateBy { it.lowercase() }
            fun first(vararg patterns: Regex): String? =
                patterns.firstNotNullOfOrNull { p -> lower.keys.sorted().firstOrNull { p.matches(it) }?.let { lower[it] } }
            val zero = first(
                Regex("mtg_fd:ir105_hrfi"), Regex("mtg_fd:ir105"), Regex("mtg_fd:ir105.*"),
                Regex("msg_fes:ir108"), Regex("msg_fes:ir108.*"),
            )
            val iodc = first(Regex("msg_iodc:ir108"), Regex("msg_iodc:ir108.*"))
            return EumetsatLayers(zero, iodc)
        }

        fun parse(capabilities: String): EumetsatLayers = pick(NAME.findAll(capabilities).map { it.groupValues[1] }.toList())

        suspend fun discover(http: OkHttpClient): EumetsatLayers = withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url(CAPABILITIES).build()).execute().use { r ->
                if (!r.isSuccessful) throw IOException("EUMETView HTTP ${r.code}")
                parse(r.body?.string().orEmpty())
            }
        }
    }
}
