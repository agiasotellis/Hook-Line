package app.hookline.radar.core.odim

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Large fields are kept as 16-bit codes; they must read back like the raw path. */
class CompactFieldTest {
    private val saved = Odim.compactThresholdBytes

    @After fun restore() { Odim.compactThresholdBytes = saved }

    private fun fixture(name: String): ByteArray =
        javaClass.getResourceAsStream("/odim/$name")!!.use { it.readBytes() }

    private fun compare(name: String, scan: Boolean) {
        Odim.compactThresholdBytes = Long.MAX_VALUE
        val exact = Odim.read(fixture(name))
        Odim.compactThresholdBytes = 0
        val compact = Odim.read(fixture(name))
        val pairs = if (scan) exact.scans.zip(compact.scans).flatMap { (a, b) -> a.fields.keys.map { a.fields[it]!! to b.fields[it]!! } }
        else listOf(exact.grid!!.field to compact.grid!!.field)
        assertTrue(pairs.isNotEmpty())
        for ((a, b) in pairs) {
            assertEquals(a.rows, b.rows); assertEquals(a.cols, b.cols)
            assertTrue("compact is smaller or equal", b.sizeBytes() <= a.rows * a.cols * 2)
            // Tolerance: half a code step of the field's range, in physical units.
            var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
            for (r in 0 until a.rows) for (c in 0 until a.cols) {
                val v = a.value(r, c).toDouble(); if (v.isFinite()) { lo = minOf(lo, v); hi = maxOf(hi, v) }
            }
            val tol = if (hi > lo) (hi - lo) / 65533 * 0.51 + 1e-6 else 1e-6
            for (r in 0 until a.rows) for (c in 0 until a.cols) {
                val x = a.value(r, c); val y = b.value(r, c)
                when {
                    x.isNaN() -> assertTrue("nodata at $r,$c", y.isNaN())
                    x.isInfinite() -> assertEquals(x, y)
                    else -> assertTrue("value at $r,$c: $x vs $y", abs(x - y) <= tol)
                }
            }
        }
    }

    @Test fun compositeMatchesRawPath() = compare("opera_comp_laea.h5", scan = false)

    @Test fun scansMatchRawPath() = compare("deess_scan_0.5_DBZH.h5", scan = true)

    @Test fun newLayoutChunkIndexMatches() = compare("deess_scan_0.5_VRADH_v114.h5", scan = true)
}
