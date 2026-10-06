package app.hookline.radar.demo

import app.hookline.radar.core.radar.Gate
import app.hookline.radar.core.radar.Moment
import app.hookline.radar.core.radar.MomentData
import app.hookline.radar.core.radar.Radial
import app.hookline.radar.core.radar.RadialStatus
import app.hookline.radar.core.radar.SiteLocation
import app.hookline.radar.core.radar.Sweep
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin

/**
 * A synthetic hook-echo supercell so the app renders something meaningful
 * offline, in previews and in screenshot tests. Mirrors
 * tools/mockup-radar/radar.py used for the design mockups.
 */
object DemoStorm {
    private const val GATES = 460
    private const val GATE_M = 250
    private const val FIRST_M = 2125

    /** Storm features in km east/north of the radar. */
    private const val MESO_X = -31.0
    private const val MESO_Y = 65.0

    fun sweep(): Sweep {
        val radials = (0 until 720).map { i ->
            val az = i * 0.5f + 0.25f
            val ref = FloatArray(GATES)
            val vel = FloatArray(GATES)
            val a = Math.toRadians(az.toDouble())
            for (g in 0 until GATES) {
                val rKm = (FIRST_M + g * GATE_M) / 1000.0
                val x = rKm * sin(a)
                val y = rKm * cos(a)
                val z = reflectivity(x, y)
                ref[g] = if (z < 5) Gate.BELOW_THRESHOLD else z.toFloat()
                vel[g] = if (z < 8) Gate.BELOW_THRESHOLD else radialVelocity(x, y, a).toFloat()
            }
            Radial(
                radarId = "KTLX",
                timeMillis = System.currentTimeMillis() - 48_000,
                azimuthNumber = i + 1,
                azimuthDeg = az,
                azimuthSpacingDeg = 0.5f,
                elevationNumber = 1,
                elevationDeg = 0.48f,
                radialStatus = RadialStatus.INTERMEDIATE,
                moments = mapOf(
                    Moment.REFLECTIVITY to MomentData(Moment.REFLECTIVITY, FIRST_M, GATE_M, ref),
                    Moment.VELOCITY to MomentData(Moment.VELOCITY, FIRST_M, GATE_M, vel),
                ),
                site = SiteLocation(35.333f, -97.278f, 370),
            )
        }
        return Sweep(1, 0.48f, radials)
    }

    private fun g(x: Double, y: Double, cx: Double, cy: Double, sx: Double, sy: Double, ang: Double = 0.0): Double {
        val dx = x - cx; val dy = y - cy
        val u = (dx * cos(ang) + dy * sin(ang)) / sx
        val w = (-dx * sin(ang) + dy * cos(ang)) / sy
        return exp(-(u * u + w * w))
    }

    private fun texture(x: Double, y: Double) = sin(x * 0.9 + sin(y * 1.1)) * cos(y * 0.7 + sin(x * 0.5))

    private fun reflectivity(x: Double, y: Double): Double {
        val n = texture(x, y)
        var z = -20.0
        z = maxOf(z, 44 * g(x, y, MESO_X + 13, MESO_Y + 18, 19.0, 7.0, 0.8) + 6 * n) // forward flank
        z = maxOf(z, 65 * g(x, y, MESO_X + 4, MESO_Y + 6, 6.0, 4.0, 0.8) + 4 * n) // core
        // hook wrapping cyclonically around the mesocyclone
        for (k in 0..40) {
            val t = k / 40.0
            val ang = Math.toRadians(10 - 200 * t)
            val r = 3.3 - 1.2 * t
            val cx = MESO_X + r * cos(ang) * 1.1
            val cy = MESO_Y + r * sin(ang) * 0.9
            val d = hypot(x - cx, y - cy) / (1.4 + 0.7 * (1 - t))
            z = maxOf(z, (54 - 18 * t) * exp(-d * d) + 3 * n)
        }
        z -= 26 * g(x, y, MESO_X + 4, MESO_Y - 0.7, 1.6, 1.3) // inflow notch
        z = maxOf(z, 58 * g(x, y, MESO_X, MESO_Y, 0.6, 0.6)) // debris ball
        z = maxOf(z, 34 * g(x, y, MESO_X - 9, MESO_Y - 22, 30.0, 8.0, 0.7) + 12 * n - 6) // trailing line
        return z
    }

    private fun radialVelocity(x: Double, y: Double, az: Double): Double {
        var vx = 9.0 + 2 * texture(y, x)
        var vy = 7.0 + 2 * texture(x * 0.5, y)
        val dx = x - MESO_X; val dy = y - MESO_Y
        val r = hypot(dx, dy) + 1e-6
        val rc = 1.2
        val vt = if (r < rc) 42 * r / rc else 42 * rc / r
        // cyclonic (counter-clockwise, north-up)
        vx += vt * (-dy / r)
        vy += vt * (dx / r)
        return vx * sin(az) + vy * cos(az)
    }
}
