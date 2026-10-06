package app.hookline.radar.core.radar

import kotlin.math.cos
import kotlin.math.sin

/**
 * One moment of one sweep packed for the GPU: a radials × gates float grid
 * (uploaded as an R32F texture, sampled NEAREST) plus one wedge per radial.
 *
 * The wedge's u coordinate runs 0→1 from the near edge of the first gate to
 * the far edge of the last, so every gate lands on exactly one texel and the
 * data is shown in its native polar form — no resampling, no smoothing.
 */
class SweepTexture private constructor(
    val moment: Moment,
    val elevationDeg: Float,
    val radialCount: Int,
    val gateCount: Int,
    val firstGateMeters: Int,
    val gateSpacingMeters: Int,
    val azimuths: FloatArray,
    val spacings: FloatArray,
    /** Row-major: values[radial * gateCount + gate]. Sentinels encoded with [encode]. */
    val values: FloatArray,
    val startMillis: Long,
    val endMillis: Long,
) {
    /**
     * Interleaved x, y, u, v — six vertices per radial. Positions are Web
     * Mercator metres relative to the site when [projector] is given (live map),
     * or flat metres east/north otherwise (tests, widgets).
     */
    fun wedgeMesh(projector: MercatorProjector? = null): FloatArray {
        val out = FloatArray(radialCount * 6 * 4)
        val r0 = RadialGeometry.groundRangeMeters(firstGateMeters - gateSpacingMeters / 2.0, elevationDeg.toDouble())
        val r1 = RadialGeometry.groundRangeMeters(
            firstGateMeters - gateSpacingMeters / 2.0 + gateCount.toDouble() * gateSpacingMeters, elevationDeg.toDouble(),
        )
        fun corner(azDeg: Double, r: Double): Pair<Float, Float> {
            if (projector != null) {
                val (x, y) = projector.fromPolar(azDeg, r)
                return x.toFloat() to y.toFloat()
            }
            val a = Math.toRadians(azDeg)
            return (r * sin(a)).toFloat() to (r * cos(a)).toFloat()
        }
        var k = 0
        for (i in 0 until radialCount) {
            val half = spacings[i] / 2.0
            val v = (i + 0.5f) / radialCount
            val (ilx, ily) = corner(azimuths[i] - half, r0)
            val (irx, iry) = corner(azimuths[i] + half, r0)
            val (olx, oly) = corner(azimuths[i] - half, r1)
            val (orx, ory) = corner(azimuths[i] + half, r1)
            // inner-left, outer-left, outer-right, inner-left, outer-right, inner-right
            val verts = floatArrayOf(
                ilx, ily, 0f, v,
                olx, oly, 1f, v,
                orx, ory, 1f, v,
                ilx, ily, 0f, v,
                orx, ory, 1f, v,
                irx, iry, 0f, v,
            )
            verts.copyInto(out, k)
            k += verts.size
        }
        return out
    }

    /** Physical value at a point (metres east/north of site), or null outside the data. */
    fun valueAt(eastM: Double, northM: Double): Float? {
        val (az, range) = RadialGeometry.polarOf(eastM, northM)
        return valueAtPolar(az, range)
    }

    /** Value at an azimuth (deg) and ground range (m) from the site. */
    fun valueAtPolar(az: Double, range: Double): Float? {
        val i = radialIndexFor(az.toFloat()) ?: return null
        val g = ((range - firstGateMeters + gateSpacingMeters / 2.0) / gateSpacingMeters).toInt()
        if (g < 0 || g >= gateCount) return null
        val v = values[i * gateCount + g]
        return when {
            v <= BELOW -> null
            v >= FOLDED -> Gate.RANGE_FOLDED
            else -> v
        }
    }

    private fun radialIndexFor(az: Float): Int? {
        for (i in 0 until radialCount) {
            var d = az - azimuths[i]
            if (d > 180f) d -= 360f
            if (d < -180f) d += 360f
            if (kotlin.math.abs(d) <= spacings[i] / 2f) return i
        }
        return null
    }

    companion object {
        /** Encoded sentinels: GLSL can't test for infinities portably. */
        const val BELOW = -1.0e30f
        const val FOLDED = 1.0e30f

        internal fun of(
            moment: Moment, elevationDeg: Float, radialCount: Int, gateCount: Int, firstGateMeters: Int,
            gateSpacingMeters: Int, azimuths: FloatArray, spacings: FloatArray, values: FloatArray,
            startMillis: Long, endMillis: Long,
        ) = SweepTexture(
            moment, elevationDeg, radialCount, gateCount, firstGateMeters, gateSpacingMeters,
            azimuths, spacings, values, startMillis, endMillis,
        )

        fun encode(v: Float): Float = when {
            v == Gate.RANGE_FOLDED -> FOLDED
            !v.isFinite() -> BELOW
            else -> v
        }

        /**
         * @param transform optional per-radial transform, e.g. storm-relative velocity
         * @return null when the sweep has no data for [moment]
         */
        fun from(
            sweep: Sweep,
            moment: Moment,
            transform: ((Radial, MomentData) -> MomentData)? = null,
        ): SweepTexture? {
            val rows = sweep.radials.mapNotNull { r ->
                r.moments[moment]?.let { md -> r to (transform?.invoke(r, md) ?: md) }
            }.sortedBy { it.first.azimuthDeg }
            if (rows.isEmpty()) return null
            val ref = rows.first().second
            val gates = rows.maxOf { it.second.gateCount }
            val values = FloatArray(rows.size * gates) { BELOW }
            rows.forEachIndexed { i, (_, md) ->
                for (g in 0 until md.gateCount) values[i * gates + g] = encode(md.gates[g])
            }
            return SweepTexture(
                moment = moment,
                elevationDeg = sweep.elevationDeg,
                radialCount = rows.size,
                gateCount = gates,
                firstGateMeters = ref.firstGateMeters,
                gateSpacingMeters = ref.gateSpacingMeters,
                azimuths = FloatArray(rows.size) { rows[it].first.azimuthDeg },
                spacings = FloatArray(rows.size) { rows[it].first.azimuthSpacingDeg },
                values = values,
                startMillis = rows.minOf { it.first.timeMillis },
                endMillis = rows.maxOf { it.first.timeMillis },
            )
        }
    }
}

/**
 * Map viewport in metres relative to the radar site. Screen y grows downward;
 * north is up.
 */
data class Viewport(
    val widthPx: Int,
    val heightPx: Int,
    val centerEastM: Double = 0.0,
    val centerNorthM: Double = 0.0,
    val metersPerPixel: Double = 400.0,
) {
    fun toMeters(px: Float, py: Float): Pair<Double, Double> =
        (centerEastM + (px - widthPx / 2.0) * metersPerPixel) to (centerNorthM - (py - heightPx / 2.0) * metersPerPixel)

    fun toScreen(eastM: Double, northM: Double): Pair<Float, Float> =
        ((widthPx / 2.0 + (eastM - centerEastM) / metersPerPixel).toFloat()) to
            ((heightPx / 2.0 - (northM - centerNorthM) / metersPerPixel).toFloat())

    fun panByPixels(dx: Float, dy: Float) = copy(
        centerEastM = centerEastM - dx * metersPerPixel,
        centerNorthM = centerNorthM + dy * metersPerPixel,
    )

    /** Zoom keeping the point under (fx, fy) fixed on screen. */
    fun zoomBy(factor: Float, fx: Float, fy: Float): Viewport {
        val (e, n) = toMeters(fx, fy)
        val mpp = (metersPerPixel / factor).coerceIn(MIN_MPP, MAX_MPP)
        return copy(
            metersPerPixel = mpp,
            centerEastM = e - (fx - widthPx / 2.0) * mpp,
            centerNorthM = n + (fy - heightPx / 2.0) * mpp,
        )
    }

    companion object {
        const val MIN_MPP = 15.0   // ~city-block detail; finer than a 250 m gate
        const val MAX_MPP = 2500.0 // regional view
    }
}

/** Triangulated thick polylines in metres, for rings and warning outlines. */
object ThickLines {
    /**
     * @param points flat x,y pairs
     * @param halfWidth half the stroke width in metres (px × metres-per-pixel / 2)
     * @return flat x,y triangle list, six vertices per segment
     */
    fun build(points: FloatArray, closed: Boolean, halfWidth: Float): FloatArray {
        val n = points.size / 2
        if (n < 2) return FloatArray(0)
        val segs = if (closed) n else n - 1
        val out = FloatArray(segs * 12)
        for (s in 0 until segs) {
            val a = s; val b = (s + 1) % n
            val ax = points[a * 2]; val ay = points[a * 2 + 1]
            val bx = points[b * 2]; val by = points[b * 2 + 1]
            val dx = bx - ax; val dy = by - ay
            val len = kotlin.math.sqrt(dx * dx + dy * dy).takeIf { it > 0f } ?: continue
            val nx = -dy / len * halfWidth; val ny = dx / len * halfWidth
            floatArrayOf(
                ax + nx, ay + ny, ax - nx, ay - ny, bx + nx, by + ny,
                bx + nx, by + ny, ax - nx, ay - ny, bx - nx, by - ny,
            ).copyInto(out, s * 12)
        }
        return out
    }

    fun circle(radius: Float, segments: Int = 96): FloatArray = FloatArray(segments * 2) { i ->
        val a = 2 * Math.PI * (i / 2) / segments
        if (i % 2 == 0) (radius * sin(a)).toFloat() else (radius * cos(a)).toFloat()
    }
}
