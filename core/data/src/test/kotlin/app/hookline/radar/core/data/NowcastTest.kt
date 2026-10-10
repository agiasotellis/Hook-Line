package app.hookline.radar.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NowcastTest {
    private val n = 61

    /** A rain band 10 cells wide whose west edge is at [westCol]. */
    private fun band(westCol: Int, t: Long) = LocalGrid(40.0, 23.0, 2.0, n, FloatArray(n * n) { i ->
        val c = i % n
        if (c in westCol until westCol + 10) 5f else 0f
    }, t)

    @Test fun findsEastwardMotionAndArrivalTime() {
        // Moves 4 cells (8 km) east in 10 minutes = 0.8 km/min.
        val prev = band(2, 0)
        val cur = band(6, 600_000)
        val m = Nowcast.motion(prev, cur, 0.2f)
        assertNotNull(m)
        assertEquals(0.8, m!!.eastKmPerMin, 0.01)
        assertEquals(0.0, m.northKmPerMin, 0.01)
        // East edge of the band is at col 15; centre is col 30: 15 cells = 30 km away,
        // the 3x3 neighbourhood reaches one cell further, so about 29 km / 0.8 = 36 min -> first 5-min step at 35-40.
        val f = Nowcast.forecast(cur, m, 0.2f, 10f)
        assertFalse(f.rainingNow)
        assertNotNull(f.startsInMin)
        assertTrue(f.startsInMin!! in 30..40)
    }

    @Test fun rainingNowAtCentre() {
        val g = band(25, 0)
        val f = Nowcast.forecast(g, null, 0.2f, 10f)
        assertTrue(f.rainingNow)
    }

    @Test fun movingAwayStaysDry() {
        val prev = band(44, 0)
        val cur = band(48, 600_000)
        val f = Nowcast.forecast(cur, Nowcast.motion(prev, cur, 0.2f), 0.2f, 10f)
        assertFalse(f.rainingNow)
        assertNull(f.startsInMin)
    }

    @Test fun seriesFollowsTheBand() {
        val prev = band(2, 0)
        val cur = band(6, 600_000)
        val s = Nowcast.series(cur, Nowcast.motion(prev, cur, 0.2f))
        assertEquals(13, s.size)
        assertEquals(0f, s[0], 0f)           // dry now
        assertEquals(5f, s[8], 0f)           // band overhead at +40 min
        assertTrue(s.indexOfFirst { it >= 0.2f } * 5 in 30..40)
        val still = Nowcast.series(cur, null)
        assertTrue(still.drop(1).all { it.isNaN() })
    }

    @Test fun roundTrip() {
        val g = band(6, 123)
        val back = LocalGrid.decode(g.encode())!!
        assertEquals(g.size, back.size)
        assertEquals(g.timeMillis, back.timeMillis)
        assertEquals(g[7, 3], back[7, 3])
    }
}
