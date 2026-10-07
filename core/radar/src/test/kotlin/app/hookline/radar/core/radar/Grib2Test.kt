package app.hookline.radar.core.radar

import org.junit.Assert.assertEquals
import org.junit.Test

class Grib2Test {
    @Test fun decodesPngPackedGrid() {
        val bytes = javaClass.getResourceAsStream("/grib2/tiny.grib2.gz")!!.readBytes()
        val rows = mutableListOf<FloatArray>()
        val h = Grib2.decode(bytes) { _, r -> rows += r.copyOf() }
        assertEquals(4, h.grid.ni)
        assertEquals(3, h.grid.nj)
        assertEquals(209, h.discipline)
        assertEquals(3, h.category)
        assertEquals(34, h.parameter)
        assertEquals(54.995, h.grid.lat1, 1e-9)
        assertEquals(-129.995, h.grid.lon1, 1e-9)
        assertEquals(-0.01, h.grid.dLat, 1e-9)
        assertEquals(0.01, h.grid.dLon, 1e-9)
        assertEquals(3, rows.size)
        assertEquals(-99.9f, rows[0][0], 1e-3f)
        assertEquals(10.5f, rows[0][2], 1e-3f)
        assertEquals(45.5f, rows[1][1], 1e-3f)
        assertEquals(12.3f, rows[2][2], 1e-3f)
        assertEquals(70.0f, rows[2][3], 1e-3f)
        assertEquals(java.time.Instant.parse("2026-10-07T12:30:00Z").toEpochMilli(), h.refTimeMillis)
    }
}
