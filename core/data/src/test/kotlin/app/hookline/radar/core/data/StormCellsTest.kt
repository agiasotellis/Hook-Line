package app.hookline.radar.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StormCellsTest {
    private val n = 101

    /** Background 20 dBZ with a 5×5 cell of [dbz] centred at (col, row). */
    private fun grid(col: Int, row: Int, dbz: Float, t: Long) = LocalGrid(35.0, -97.0, 1.0, n, FloatArray(n * n) { i ->
        val c = i % n; val r = i / n
        if (kotlin.math.abs(c - col) <= 2 && kotlin.math.abs(r - row) <= 2) dbz else 20f
    }, t)

    @Test fun detectsOneCell() {
        val cells = StormCells.detect(grid(60, 50, 58f, 0))
        assertEquals(1, cells.size)
        val c = cells[0]
        assertEquals(58f, c.maxDbz, 0f)
        assertEquals(25.0, c.areaKm2, 1e-9)
        assertEquals(HailRisk.LIKELY, c.hail)
        // 10 km east of centre.
        assertEquals(-97.0 + 10 / (111.32 * kotlin.math.cos(Math.toRadians(35.0))), c.lon, 1e-6)
        assertEquals(35.0, c.lat, 1e-6)
    }

    @Test fun tracksMotionAndArrival() {
        var id = 0
        val first = StormCells.track(emptyList(), StormCells.detect(grid(30, 50, 55f, 0)), 5.0) { ++id }
        // 5 km east in 5 minutes = 1 km/min.
        val second = StormCells.track(first, StormCells.detect(grid(35, 50, 56f, 300_000)), 5.0) { ++id }
        val c = second.single()
        assertEquals(first.single().id, c.id)
        assertEquals(1.0, c.eastKmPerMin!!, 0.02)
        assertEquals(0.0, c.northKmPerMin!!, 0.02)
        assertEquals(90.0, c.headingDeg!!, 1.0)
        assertEquals(1, c.track.size)
        // A place 20 km further east: about 20 minutes away.
        val p = LatLon(35.0, c.lon + 20 / (111.32 * kotlin.math.cos(Math.toRadians(35.0))))
        val eta = StormCells.arrivalMinutes(c, p)
        assertNotNull(eta)
        assertTrue(eta!! in 18..21)
        // A place to the west: it's moving away.
        assertNull(StormCells.arrivalMinutes(c, LatLon(35.0, c.lon - 0.3)))
    }
}
