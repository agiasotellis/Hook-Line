package app.couplet.radar.core.radar

import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * CPU fallback that paints a sweep into an ARGB pixel buffer, nearest-bin, so
 * the polar "staircase" look is preserved. Used for widgets, Wear OS tiles,
 * loop export and as the pre-GL interim renderer. The live map uses the GL
 * radial mesh (see [RadialGeometry.radialMesh]).
 */
object SweepRasterizer {

    /**
     * @param centerX,centerY radar site position in output pixels (may be off-image)
     * @param metersPerPixel  map scale
     */
    fun rasterize(
        sweep: Sweep,
        moment: Moment,
        palette: Palette,
        width: Int,
        height: Int,
        centerX: Float,
        centerY: Float,
        metersPerPixel: Float,
        transform: ((Radial, MomentData) -> MomentData)? = null,
    ): IntArray {
        val out = IntArray(width * height)
        val radials = sweep.radials.filter { moment in it.moments }
        if (radials.isEmpty()) return out
        val data = radials.map { r -> r.moments.getValue(moment).let { transform?.invoke(r, it) ?: it } }

        // Azimuth lookup at 0.1 deg: index of the radial whose span contains it.
        val lookup = IntArray(3600) { -1 }
        radials.forEachIndexed { i, r ->
            val half = r.azimuthSpacingDeg / 2f
            var a = r.azimuthDeg - half
            while (a < r.azimuthDeg + half) {
                val idx = (((floor(a * 10f).toInt()) % 3600) + 3600) % 3600
                lookup[idx] = i
                a += 0.1f
            }
        }

        for (y in 0 until height) {
            val dy = (centerY - (y + 0.5f)) * metersPerPixel // north positive
            for (x in 0 until width) {
                val dx = ((x + 0.5f) - centerX) * metersPerPixel
                val range = sqrt(dx * dx + dy * dy)
                var az = Math.toDegrees(atan2(dx.toDouble(), dy.toDouble())).toFloat()
                if (az < 0) az += 360f
                val ri = lookup[(az * 10f).toInt().coerceIn(0, 3599)]
                if (ri < 0) continue
                val md = data[ri]
                val g = ((range - md.firstGateMeters + md.gateSpacingMeters / 2f) / md.gateSpacingMeters).toInt()
                if (g < 0 || g >= md.gateCount) continue
                out[y * width + x] = palette.colorFor(md.gates[g])
            }
        }
        return out
    }
}
