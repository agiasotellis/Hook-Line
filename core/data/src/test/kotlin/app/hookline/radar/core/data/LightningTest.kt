package app.hookline.radar.core.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class LightningTest {
    @Test fun parsesPackedGlmFile() {
        val bytes = javaClass.getResourceAsStream("/glm/glm_tiny.nc")!!.readBytes()
        val flashes = GlmLightning.parse(bytes, 0L)
        assertEquals(3, flashes.size)
        assertEquals(35.2f, flashes[0].lat, 1e-4f)
        assertEquals(-95.0f, flashes[1].lon, 1e-4f)
        val start = Instant.parse("2026-10-06T12:00:00Z").toEpochMilli()
        assertEquals((start + 500).toDouble(), flashes[0].timeMillis.toDouble(), 5.0)
        assertEquals((start + 19_900).toDouble(), flashes[2].timeMillis.toDouble(), 5.0)
    }

    @Test fun keyTimesAndPrefixes() {
        val t = GlmLightning.startTime("GLM-L2-LCFA/2026/279/12/OR_GLM-L2-LCFA_G19_s20262791200200_e20262791200400_c20262791200419.nc")
        assertEquals(Instant.parse("2026-10-06T12:00:20Z").toEpochMilli(), t)
        assertEquals("GLM-L2-LCFA/2026/279/12/", GlmLightning.hourPrefix(t!!))
    }
}
