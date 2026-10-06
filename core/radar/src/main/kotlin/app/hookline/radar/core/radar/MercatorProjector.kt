package app.hookline.radar.core.radar

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Projects onto Web Mercator (EPSG:3857) metres relative to a radar site, so
 * radar geometry lines up exactly with the vector base map's tiles.
 *
 * Polar radar coordinates (azimuth, ground range) go through a great-circle
 * destination first, so beams are placed correctly even 400+ km out where a
 * flat projection would drift by kilometres.
 */
class MercatorProjector(val siteLat: Double, val siteLon: Double) {
    private val y0 = mercY(siteLat)

    fun fromLatLon(lat: Double, lon: Double): Pair<Double, Double> {
        var dLon = lon - siteLon
        if (dLon > 180) dLon -= 360
        if (dLon < -180) dLon += 360
        return Math.toRadians(dLon) * R to (mercY(lat) - y0)
    }

    fun fromPolar(azimuthDeg: Double, groundRangeM: Double): Pair<Double, Double> {
        val (lat, lon) = RadialGeometry.destination(siteLat, siteLon, azimuthDeg, groundRangeM)
        return fromLatLon(lat, lon)
    }

    /** Ground metres per Mercator metre at the site (Mercator stretches by 1/cos(lat)). */
    val groundScale: Double get() = cos(Math.toRadians(siteLat))

    companion object {
        const val R = 6_378_137.0 // WGS84 / Web Mercator sphere

        private fun mercY(latDeg: Double): Double = R * ln(tan(PI / 4 + Math.toRadians(latDeg) / 2))

        /** Bearing (deg clockwise from north) and great-circle distance (m) from a to b. */
        fun bearingDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Pair<Double, Double> {
            val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
            val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
            val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
            val dist = 2 * 6_371_000.0 * asin(sqrt(a.coerceIn(0.0, 1.0)))
            val brg = Math.toDegrees(atan2(sin(dl) * cos(p2), cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)))
            return ((brg + 360) % 360) to dist
        }
    }
}
