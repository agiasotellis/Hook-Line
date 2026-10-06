package app.couplet.radar.core.radar

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Geometry for drawing radials in their native polar form (no resampling).
 *
 * The renderer draws each radial as a strip of quads, one per gate, using a
 * value texture + palette LUT in the fragment shader. This class produces the
 * ground-projected corner positions; the GL layer only uploads them.
 */
object RadialGeometry {
    private const val EARTH_RADIUS_M = 6_371_000.0
    /** 4/3 effective earth radius for standard beam refraction. */
    private const val EFFECTIVE_RADIUS_M = EARTH_RADIUS_M * 4.0 / 3.0

    /** Beam centre height above radar level (m) at slant range r, elevation e. */
    fun beamHeightMeters(slantRangeM: Double, elevationDeg: Double): Double {
        val e = Math.toRadians(elevationDeg)
        return sqrt(slantRangeM * slantRangeM + EFFECTIVE_RADIUS_M * EFFECTIVE_RADIUS_M +
            2 * slantRangeM * EFFECTIVE_RADIUS_M * sin(e)) - EFFECTIVE_RADIUS_M
    }

    /** Distance along the ground (m) for slant range r at elevation e. */
    fun groundRangeMeters(slantRangeM: Double, elevationDeg: Double): Double {
        val e = Math.toRadians(elevationDeg)
        val h = beamHeightMeters(slantRangeM, elevationDeg)
        return EFFECTIVE_RADIUS_M * asin(slantRangeM * cos(e) / (EFFECTIVE_RADIUS_M + h))
    }

    /** Great-circle destination from a site, returning (lat, lon) in degrees. */
    fun destination(latDeg: Double, lonDeg: Double, bearingDeg: Double, distanceM: Double): Pair<Double, Double> {
        val d = distanceM / EARTH_RADIUS_M
        val b = Math.toRadians(bearingDeg)
        val lat1 = Math.toRadians(latDeg)
        val lon1 = Math.toRadians(lonDeg)
        val lat2 = asin(sin(lat1) * cos(d) + cos(lat1) * sin(d) * cos(b))
        val lon2 = lon1 + atan2(sin(b) * sin(d) * cos(lat1), cos(d) - sin(lat1) * sin(lat2))
        return Math.toDegrees(lat2) to ((Math.toDegrees(lon2) + 540) % 360 - 180)
    }

    /**
     * Build a flat triangle list (x,y pairs in metres east/north of the site)
     * for one radial's gates. Two triangles per gate, six vertices.
     * Gates that are below threshold are still emitted; the shader discards
     * them, which keeps the mesh static between scans of the same VCP.
     */
    fun radialMesh(azimuthDeg: Float, spacingDeg: Float, data: MomentData, elevationDeg: Float): FloatArray {
        val half = spacingDeg / 2.0
        val a0 = Math.toRadians(azimuthDeg - half)
        val a1 = Math.toRadians(azimuthDeg + half)
        val out = FloatArray(data.gateCount * 12)
        var k = 0
        for (g in 0 until data.gateCount) {
            val r0 = groundRangeMeters(data.rangeOfGateMeters(g) - data.gateSpacingMeters / 2.0, elevationDeg.toDouble())
            val r1 = groundRangeMeters(data.rangeOfGateMeters(g) + data.gateSpacingMeters / 2.0, elevationDeg.toDouble())
            // Azimuth is clockwise from north: x = r sin(a), y = r cos(a).
            val p00x = (r0 * sin(a0)).toFloat(); val p00y = (r0 * cos(a0)).toFloat()
            val p01x = (r0 * sin(a1)).toFloat(); val p01y = (r0 * cos(a1)).toFloat()
            val p10x = (r1 * sin(a0)).toFloat(); val p10y = (r1 * cos(a0)).toFloat()
            val p11x = (r1 * sin(a1)).toFloat(); val p11y = (r1 * cos(a1)).toFloat()
            floatArrayOf(p00x, p00y, p10x, p10y, p11x, p11y, p00x, p00y, p11x, p11y, p01x, p01y)
                .copyInto(out, k)
            k += 12
        }
        return out
    }

    /** Azimuth (deg clockwise from north) and ground range (m) of a point east/north of the site. */
    fun polarOf(eastM: Double, northM: Double): Pair<Double, Double> {
        val az = (Math.toDegrees(atan2(eastM, northM)) + 360) % 360
        return az to sqrt(eastM * eastM + northM * northM)
    }

    internal const val TWO_PI = 2 * PI
}
