package app.hookline.radar.core.data

import kotlin.math.cos

data class RadarSite(val id: String, val name: String, val location: LatLon)

/**
 * Starter list of WSR-88D sites (Plains and Southeast). The full ~160-site
 * table ships as a bundled JSON asset in a later milestone. Live data always
 * prefers the site position from the radar's own VOL block.
 */
object Sites {
    val ALL = listOf(
        RadarSite("KTLX", "Oklahoma City", LatLon(35.333, -97.278)),
        RadarSite("KINX", "Tulsa", LatLon(36.175, -95.564)),
        RadarSite("KFWS", "Dallas / Fort Worth", LatLon(32.573, -97.303)),
        RadarSite("KAMA", "Amarillo", LatLon(35.233, -101.709)),
        RadarSite("KLBB", "Lubbock", LatLon(33.654, -101.814)),
        RadarSite("KDDC", "Dodge City", LatLon(37.761, -99.969)),
        RadarSite("KICT", "Wichita", LatLon(37.655, -97.443)),
        RadarSite("KOAX", "Omaha", LatLon(41.320, -96.367)),
        RadarSite("KSGF", "Springfield, MO", LatLon(37.235, -93.400)),
        RadarSite("KLSX", "St. Louis", LatLon(38.699, -90.683)),
        RadarSite("KLOT", "Chicago", LatLon(41.604, -88.085)),
        RadarSite("KPAH", "Paducah", LatLon(37.068, -88.772)),
        RadarSite("KDGX", "Jackson, MS", LatLon(32.280, -89.984)),
        RadarSite("KHTX", "Huntsville", LatLon(34.931, -86.083)),
        RadarSite("KFFC", "Atlanta", LatLon(33.364, -84.566)),
    )

    fun byId(id: String): RadarSite? = ALL.firstOrNull { it.id == id }

    private const val EARTH_RADIUS_M = 6_371_000.0

    /**
     * Local equirectangular projection to metres east/north of [origin].
     * Error is under ~0.5 % within a radar's 460 km range, well inside a
     * warning polygon's own precision. The GL map switches to proper Web
     * Mercator when the vector base map lands.
     */
    fun toLocalMeters(p: LatLon, origin: LatLon): Pair<Double, Double> {
        val east = Math.toRadians(p.lon - origin.lon) * EARTH_RADIUS_M * cos(Math.toRadians((p.lat + origin.lat) / 2))
        val north = Math.toRadians(p.lat - origin.lat) * EARTH_RADIUS_M
        return east to north
    }
}
