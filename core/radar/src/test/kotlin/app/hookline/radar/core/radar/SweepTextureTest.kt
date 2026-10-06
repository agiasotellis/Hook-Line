package app.hookline.radar.core.radar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SweepTextureTest {

    private fun radial(az: Float, ref: FloatArray, vel: FloatArray? = null) = Radial(
        "KTLX", 1_000L + az.toLong(), 0, az, 1f, 1, 0.5f, 1,
        buildMap {
            put(Moment.REFLECTIVITY, MomentData(Moment.REFLECTIVITY, 1000, 250, ref))
            vel?.let { put(Moment.VELOCITY, MomentData(Moment.VELOCITY, 1000, 250, it)) }
        },
    )

    private val sweep = Sweep(1, 0.5f, listOf(
        radial(90.5f, floatArrayOf(10f, 20f, Gate.BELOW_THRESHOLD)),
        radial(0.5f, floatArrayOf(30f, Gate.RANGE_FOLDED)),
    ))

    @Test
    fun packsRowsSortedByAzimuthAndPadsShortRadials() {
        val t = SweepTexture.from(sweep, Moment.REFLECTIVITY)!!
        assertEquals(2, t.radialCount)
        assertEquals(3, t.gateCount)
        assertEquals(0.5f, t.azimuths[0])
        // row 0 = az 0.5: 30, folded, padding
        assertEquals(30f, t.values[0])
        assertEquals(SweepTexture.FOLDED, t.values[1])
        assertEquals(SweepTexture.BELOW, t.values[2])
        // row 1 = az 90.5: below-threshold gate encoded
        assertEquals(SweepTexture.BELOW, t.values[5])
        assertEquals(1_000L, t.startMillis)
    }

    @Test
    fun returnsNullForMissingMoment() {
        assertNull(SweepTexture.from(sweep, Moment.CORRELATION_COEFFICIENT))
    }

    @Test
    fun wedgeMeshCoversGateRangeWithFullUSpan() {
        val t = SweepTexture.from(sweep, Moment.REFLECTIVITY)!!
        val m = t.wedgeMesh()
        assertEquals(2 * 6 * 4, m.size)
        // First radial points north: outer-left vertex y ≈ far edge (1000 - 125 + 3*250 = 1625 m)
        assertEquals(1625f, m[5], 2f)
        assertEquals(1f, m[6])
        assertEquals(0.25f, m[3]) // v = (0 + 0.5) / 2
    }

    @Test
    fun inspectorReadsTheRightGate() {
        val t = SweepTexture.from(sweep, Moment.REFLECTIVITY)!!
        assertEquals(30f, t.valueAt(0.0, 1000.0)!!)      // north, gate 0
        assertEquals(20f, t.valueAt(1250.0, 0.0)!!)      // east, gate 1
        assertNull(t.valueAt(-1000.0, 0.0))              // west: no radial
        assertNull(t.valueAt(1500.0, 0.0))               // east gate 2 is below threshold
        assertEquals(Gate.RANGE_FOLDED, t.valueAt(0.0, 1250.0))
    }

    @Test
    fun viewportRoundTripsAndZoomKeepsFocusFixed() {
        val vp = Viewport(1000, 2000, centerEastM = 5000.0, centerNorthM = -3000.0, metersPerPixel = 100.0)
        val (e, n) = vp.toMeters(250f, 400f)
        val (x, y) = vp.toScreen(e, n)
        assertEquals(250f, x, 1e-3f); assertEquals(400f, y, 1e-3f)

        val z = vp.zoomBy(2f, 250f, 400f)
        assertEquals(50.0, z.metersPerPixel, 1e-9)
        val (e2, n2) = z.toMeters(250f, 400f)
        assertTrue(abs(e2 - e) < 1e-6 && abs(n2 - n) < 1e-6)

        // Dragging the map right moves the centre west.
        assertTrue(vp.panByPixels(10f, 0f).centerEastM < vp.centerEastM)
    }

    @Test
    fun thickLinesEmitTwoTrianglesPerSegment() {
        val open = ThickLines.build(floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f), closed = false, halfWidth = 1f)
        assertEquals(2 * 12, open.size)
        val closed = ThickLines.build(ThickLines.circle(100f, 16), closed = true, halfWidth = 2f)
        assertEquals(16 * 12, closed.size)
        // First segment's first vertex is offset by the half width perpendicular to the segment.
        assertEquals(1f, open[1], 1e-6f)
    }
}
