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
    fun apply(radial: Radial, motionFromDeg: Float, motionSpeedMs: Float): MomentData? {
        val vel = radial.moments[Moment.VELOCITY] ?: return null
        val towardDeg = (motionFromDeg + 180f) % 360f
        // Positive radial velocity is away from the radar.
        val component = motionSpeedMs * cos(Math.toRadians((radial.azimuthDeg - towardDeg).toDouble())).toFloat()
        val out = FloatArray(vel.gateCount) { i ->
            val v = vel.gates[i]
            if (Gate.isData(v)) v - component else v
        }
        return vel.copy(gates = out)
    }
}
