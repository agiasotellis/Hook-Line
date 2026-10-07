package app.hookline.radar.core.data

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A radar Hookline can stream.
 * @param country ISO 3166 alpha-2 code used in the European data paths (empty for NEXRAD)
 */
data class RadarSite(
    val id: String,
    val name: String,
    val location: LatLon,
    val network: Network = Network.NEXRAD,
    val country: String = "",
) {
    /** Short label for the site chip: ICAO for NEXRAD, upper-case ODIM code for Europe. */
    val label: String get() = if (network == Network.NEXRAD) id else id.uppercase()
}

object Sites {
    val ALL: List<RadarSite> get() = SiteCatalog.ALL

    fun byId(id: String): RadarSite? = ALL.firstOrNull { it.id == id }

    private const val EARTH_RADIUS_M = 6_371_000.0

    fun distanceKm(a: LatLon, b: LatLon): Double {
        val p1 = Math.toRadians(a.lat); val p2 = Math.toRadians(b.lat)
        val dp = p2 - p1; val dl = Math.toRadians(b.lon - a.lon)
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0))) / 1000.0
    }

    /** Nearest radar to [p] within [maxKm], or null. */
    fun nearest(p: LatLon, maxKm: Double = 230.0): RadarSite? =
        ALL.map { it to distanceKm(p, it.location) }.filter { it.second <= maxKm }.minByOrNull { it.second }?.first

    /** All radars sorted by distance from [p]. */
    fun byDistance(p: LatLon): List<RadarSite> = ALL.sortedBy { distanceKm(p, it.location) }

    /**
     * Local equirectangular projection to metres east/north of [origin].
     * Kept for tests and small-area maths; the map uses Web Mercator.
     */
    fun toLocalMeters(p: LatLon, origin: LatLon): Pair<Double, Double> {
        val east = Math.toRadians(p.lon - origin.lon) * EARTH_RADIUS_M * cos(Math.toRadians((p.lat + origin.lat) / 2))
        val north = Math.toRadians(p.lat - origin.lat) * EARTH_RADIUS_M
        return east to north
    }
}

/** Where each kind of data exists. */
object Coverage {
    /** Approximate footprint of the EUMETNET OPERA pan-European composite. */
    fun inOperaComposite(p: LatLon): Boolean = p.lat in 31.0..72.0 && p.lon in -31.0..51.0

    /** Contiguous US, Alaska, Hawaii, Puerto Rico and Guam: where NWS warnings apply. */
    fun inUs(p: LatLon): Boolean =
        (p.lat in 24.0..50.0 && p.lon in -125.0..-66.0) ||
            (p.lat in 51.0..72.0 && p.lon in -170.0..-129.0) ||
            (p.lat in 18.5..22.5 && p.lon in -161.0..-154.0) ||
            (p.lat in 17.5..18.7 && p.lon in -68.0..-65.0) ||
            (p.lat in 13.0..14.0 && p.lon in 144.0..145.5)
}
