package app.hookline.radar.core.radar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteAndGeometryTest {

    @Test
    fun parsesGrStylePaletteWithCommentsAndGradients() {
        val p = Palette.parse(
            """
            ; my chase palette
            Product: BR
            Units: dBZ
            Color: 10 0 0 0 0 0 200   ; gradient to blue
            SolidColor: 20 255 0 0
            Color4: 30 0 255 0 128
            RF: 1 2 3
            """.trimIndent()
        )
        assertEquals("dBZ", p.units)
        assertEquals(3, p.stops.size)
        assertEquals(0, p.colorFor(5f)) // below first stop: transparent
        // Halfway through the 10->20 gradient toward the explicit end colour (0,0,200)
        val mid = p.colorFor(15f)
        assertEquals(100, mid and 0xFF)
        // Solid band stays red across its range
        assertEquals(Palette.argb(255, 255, 0, 0), p.colorFor(29.9f))
        assertEquals(Palette.argb(128, 0, 255, 0), p.colorFor(40f))
        assertEquals(Palette.argb(255, 1, 2, 3), p.colorFor(Gate.RANGE_FOLDED))
        assertEquals(0, p.colorFor(Gate.BELOW_THRESHOLD))
    }

    @Test
    fun nwsReflectivityLutIsOpaqueAboveFiveDbz() {
        val lut = Palette.NWS_REFLECTIVITY.toLut(-10f, 80f)
        assertEquals(256, lut.size)
        assertEquals(0, lut.first())
        assertTrue((lut.last() ushr 24) == 255)
    }

    @Test
    fun beamHeightMatchesStandardRefractionModel() {
        // 0.5 deg beam at 100 km is roughly 1.46 km above radar level.
        val h = RadialGeometry.beamHeightMeters(100_000.0, 0.5)
        assertEquals(1460.0, h, 25.0)
        val g = RadialGeometry.groundRangeMeters(100_000.0, 0.5)
        assertTrue(g < 100_000.0 && g > 99_900.0)
    }

    @Test
    fun radialMeshEmitsSixVerticesPerGateClockwiseFromNorth() {
        val data = MomentData(Moment.REFLECTIVITY, 1000, 250, floatArrayOf(10f, 20f))
        val mesh = RadialGeometry.radialMesh(azimuthDeg = 90f, spacingDeg = 1f, data = data, elevationDeg = 0.5f)
        assertEquals(2 * 12, mesh.size)
        // Due east: x positive, y close to zero.
        assertTrue(mesh[0] > 800f)
        assertTrue(kotlin.math.abs(mesh[1]) < 20f)
    }

    @Test
    fun stormRelativeRemovesMotionComponent() {
        val vel = MomentData(Moment.VELOCITY, 0, 250, floatArrayOf(20f, Gate.BELOW_THRESHOLD))
        // Radial pointing NE (45 deg); storm moving from SW (225) at 10 m/s => fully along radial, away.
        val radial = Radial("KTLX", 0, 1, 45f, 0.5f, 1, 0.5f, 1, mapOf(Moment.VELOCITY to vel))
        val srv = StormRelative.apply(radial, motionFromDeg = 225f, motionSpeedMs = 10f)!!
        assertEquals(10f, srv.gates[0], 1e-3f)
        assertEquals(Gate.BELOW_THRESHOLD, srv.gates[1])
    }
}
