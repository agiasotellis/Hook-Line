package app.couplet.radar.core.radar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SweepRasterizerTest {

    private fun radial(az: Float, value: Float) = Radial(
        radarId = "KTLX", timeMillis = 0, azimuthNumber = 0, azimuthDeg = az, azimuthSpacingDeg = 1f,
        elevationNumber = 1, elevationDeg = 0.5f, radialStatus = 1,
        moments = mapOf(Moment.REFLECTIVITY to MomentData(Moment.REFLECTIVITY, 0, 1000, FloatArray(10) { value })),
    )

    @Test
    fun paintsOnlyTheSectorThatHasData() {
        // Strong echo due north (az 0), nothing elsewhere.
        val sweep = Sweep(1, 0.5f, listOf(radial(0.5f, 50f)))
        val px = SweepRasterizer.rasterize(
            sweep, Moment.REFLECTIVITY, Palette.NWS_REFLECTIVITY,
            width = 21, height = 21, centerX = 10.5f, centerY = 20.5f, metersPerPixel = 400f,
        )
        // Straight up from the site (north) is painted...
        assertNotEquals(0, px[5 * 21 + 10])
        // ...due east of the site is not.
        assertEquals(0, px[20 * 21 + 20])
    }
}
