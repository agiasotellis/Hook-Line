package app.hookline.radar.core.radar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class ProjectionAndCompactTest {

    private val ktlx = MercatorProjector(35.333, -97.278)

    @Test
    fun siteIsOriginAndNorthIsPositiveY() {
        val (x0, y0) = ktlx.fromLatLon(35.333, -97.278)
        assertEquals(0.0, x0, 1e-6); assertEquals(0.0, y0, 1e-6)
        val (x, y) = ktlx.fromPolar(0.0, 100_000.0)
        assertTrue(abs(x) < 1.0)
        // Mercator stretches distances by ~1/cos(lat): 100 km of ground ≈ 123 km of Mercator at 35.3–36.2°N.
        assertTrue(y in 122_000.0..124_500.0)
    }

    @Test
    fun polarAndLatLonAgreeAtLongRange() {
        val (lat, lon) = RadialGeometry.destination(35.333, -97.278, 60.0, 400_000.0)
        val a = ktlx.fromPolar(60.0, 400_000.0)
        val b = ktlx.fromLatLon(lat, lon)
        assertTrue(hypot(a.first - b.first, a.second - b.second) < 1.0)
    }

    @Test
    fun bearingDistanceInvertsDestination() {
        val (lat, lon) = RadialGeometry.destination(35.333, -97.278, 225.0, 50_000.0)
        val (brg, dist) = MercatorProjector.bearingDistance(35.333, -97.278, lat, lon)
        assertEquals(225.0, brg, 0.01)
        assertEquals(50_000.0, dist, 5.0)
    }

    @Test
    fun compactRoundTripKeepsValuesAndSentinels() {
        val r = Radial(
            "KTLX", 5, 1, 90f, 0.5f, 1, 0.5f, 1,
            mapOf(Moment.VELOCITY to MomentData(Moment.VELOCITY, 2125, 250, floatArrayOf(-31.5f, Gate.RANGE_FOLDED, Gate.BELOW_THRESHOLD, 12.25f))),
        )
        val c = CompactMoment.from(Sweep(1, 0.5f, listOf(r)), Moment.VELOCITY)!!
        assertEquals(-31.5f, c.value(0, 0), 0.01f)
        assertEquals(Gate.RANGE_FOLDED, c.value(0, 1))
        assertEquals(Gate.BELOW_THRESHOLD, c.value(0, 2))
        assertEquals(8, c.sizeBytes)

        // Storm-relative adjustment during expansion; sentinels untouched.
        val t = c.toTexture { az, v -> v - StormRelative.component(az, 270f, 10f) }
        assertEquals(12.25f - 10f, t.values[3], 0.01f)
        assertEquals(SweepTexture.FOLDED, t.values[1])
        assertEquals(SweepTexture.BELOW, t.values[2])
    }
}
