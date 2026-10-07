package app.hookline.radar.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MrmsTracksTest {
    @Test fun maxPoolsAndDropsMissing() {
        val bytes = javaClass.getResourceAsStream("/grib2/tiny.grib2.gz")!!.readBytes()
        val s = MrmsTracks.decode(bytes, 2, 0L)
        assertEquals(2, s.width)
        assertEquals(1, s.height)
        assertEquals(45.5f, s.values[0], 1e-3f)
        assertEquals(60f, s.values[1], 1e-3f)
        assertEquals(55.0, s.north, 1e-9)
        assertEquals(-130.0, s.west, 1e-9)
        assertEquals(0.02, s.dLat, 1e-9)
        assertEquals(60f, s.valueAt(54.985, -129.975), 1e-3f)
        assertTrue(s.valueAt(40.0, -100.0).isNaN())
    }
}
