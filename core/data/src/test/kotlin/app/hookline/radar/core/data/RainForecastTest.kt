package app.hookline.radar.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RainForecastTest {
    private val n = 61
    private val kind = RainSource.Kind.OPERA_RATE
    private val t0 = 1_700_000_000_000L

    /** A rain band [width] cells wide whose west edge is at [westCol], rain rate [rate] mm/h. */
    private fun band(westCol: Int, t: Long, width: Int = 10, rate: Float = 5f) =
        LocalGrid(40.0, 23.0, 2.0, n, FloatArray(n * n) { i -> if (i % n in westCol until westCol + width) rate else 0f }, t)

    private val dry = LocalGrid(40.0, 23.0, 2.0, n, FloatArray(n * n), t0)

    @Test fun steadyBandIsConfidentWithAWindowAroundArrival() {
        val prev = band(2, t0)
        val cur = band(6, t0 + 600_000)
        val f = RainForecaster.analyse(kind, cur, prev, cur.timeMillis + 60_000)
        assertNotNull(f.result.startsInMin)
        val t = f.result.startsInMin!!
        val w = f.arrivalWindow!!
        assertTrue("window $w contains $t", t in w)
        assertTrue(w.first < t && w.last > t)
        assertEquals(35, t)
        assertEquals(25..45, w)
        assertTrue(f.issues.isEmpty())
        assertEquals(Confidence.HIGH, f.confidence)
        assertEquals(RainIntensity.MODERATE, f.peak)
    }

    @Test fun nearbyRainWithoutASecondPictureIsLowConfidence() {
        val cur = band(6, t0)
        val f = RainForecaster.analyse(kind, cur, null, t0)
        assertTrue(ForecastIssue.NO_MOTION in f.issues)
        assertEquals(Confidence.LOW, f.confidence)
        assertNull(f.result.startsInMin)
    }

    @Test fun nothingAroundIsADryHighConfidenceHour() {
        val f = RainForecaster.analyse(kind, dry, dry, t0)
        assertFalse(f.rainNearby)
        assertEquals(Confidence.HIGH, f.confidence)
        assertNull(f.arrivalWindow)
        assertEquals(RainIntensity.NONE, f.peak)
    }

    @Test fun oldDataLowersAndThenRemovesConfidence() {
        assertTrue(ForecastIssue.OLD_DATA in RainForecaster.analyse(kind, dry, dry, t0 + 30 * 60_000).issues)
        assertEquals(Confidence.NONE, RainForecaster.analyse(kind, dry, dry, t0 + 50 * 60_000).confidence)
    }

    @Test fun growingRainIsFlagged() {
        val prev = band(20, t0, width = 4)
        val cur = band(20, t0 + 600_000, width = 12)
        assertTrue(ForecastIssue.CHANGING_FAST in RainForecaster.analyse(kind, cur, prev, cur.timeMillis).issues)
    }

    @Test fun rainingNowWithEndTime() {
        // Band over the centre (cols 26..35) moving east 4 cells / 10 min: the trailing edge passes in ~25-30 min.
        val prev = band(22, t0)
        val cur = band(26, t0 + 600_000)
        val f = RainForecaster.analyse(kind, cur, prev, cur.timeMillis)
        assertTrue(f.result.rainingNow)
        assertNotNull(f.endsInMin)
        assertTrue(f.endsInMin!! in 5..30)
    }

    @Test fun minutesFromNowSubtractsDataAge() {
        val f = RainForecaster.analyse(kind, dry, dry, t0)
        assertEquals(20, f.minutesFromNow(30, t0 + 10 * 60_000))
        assertEquals(0, f.minutesFromNow(5, t0 + 10 * 60_000))
    }

    @Test fun intensityClasses() {
        assertEquals(RainIntensity.NONE, RainIntensity.of(RainSource.Kind.MRMS_DBZ, 10f))
        assertEquals(RainIntensity.LIGHT, RainIntensity.of(RainSource.Kind.MRMS_DBZ, 25f))
        assertEquals(RainIntensity.HEAVY, RainIntensity.of(RainSource.Kind.MRMS_DBZ, 45f))
        assertEquals(RainIntensity.VERY_HEAVY, RainIntensity.of(RainSource.Kind.OPERA_RATE, 60f))
    }
}
