package app.hookline.radar.core.radar

import kotlin.math.cos

/**
 * Storm-relative velocity: subtract the component of storm motion along each
 * radial so rotation stands out from the storm's own movement.
 */
object StormRelative {
    /**
     * @param motionFromDeg direction the storm moves FROM (meteorological), degrees
     * @param motionSpeedMs storm speed in m/s
     */
    /** Part of the storm's motion along a radial at [azimuthDeg]; positive = away from the radar. */
    fun component(azimuthDeg: Float, motionFromDeg: Float, motionSpeedMs: Float): Float {
        val towardDeg = (motionFromDeg + 180f) % 360f
        return motionSpeedMs * cos(Math.toRadians((azimuthDeg - towardDeg).toDouble())).toFloat()
    }

    fun apply(radial: Radial, motionFromDeg: Float, motionSpeedMs: Float): MomentData? {
        val vel = radial.moments[Moment.VELOCITY] ?: return null
        val component = component(radial.azimuthDeg, motionFromDeg, motionSpeedMs)
        val out = FloatArray(vel.gateCount) { i ->
            val v = vel.gates[i]
            if (Gate.isData(v)) v - component else v
        }
        return vel.copy(gates = out)
    }
}
