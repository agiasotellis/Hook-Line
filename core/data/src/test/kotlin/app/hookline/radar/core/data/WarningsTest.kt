package app.hookline.radar.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class WarningsTest {
    private val regions = WarningRegions.bundled()

    @Test fun bundledRegionsLoad() {
        assertTrue(regions.regions.size > 1900)
    }

    @Test fun athensIsInAttica() {
        val r = regions.regionAt(LatLon(37.98, 23.73))
        assertNotNull(r)
        assertEquals("GR", r!!.country)
    }

    @Test fun openOceanHasNoRegion() {
        assertNull(regions.regionAt(LatLon(40.0, -40.0)))
    }

    @Test fun countriesInBox() {
        val c = regions.countriesIn(46.0, 5.0, 48.0, 11.0)
        assertTrue("CH" in c)
        assertTrue(c.all { it in WarningRegions.FEEDS || it.length == 2 })
    }

    @Test fun parsesFeed() {
        val code = regions.regionAt(LatLon(37.98, 23.73))!!.code
        val body = """
        {"warnings":[
          {"alert":{"identifier":"2.49.0.0.300.0.GR.1","info":[
            {"language":"el-GR","event":"Άνεμος","headline":"Κίτρινη","parameter":[]},
            {"language":"en-GB","event":"Wind","headline":"Yellow wind warning","description":"Gusts to 70 km/h",
             "onset":"2026-10-07T06:00:00+00:00","expires":"2099-10-08T06:00:00+00:00","web":"https://meteoalarm.org",
             "parameter":[{"valueName":"awareness_level","value":"2; Yellow; Moderate"},{"valueName":"awareness_type","value":"1; Wind"}],
             "area":[{"areaDesc":"Attiki","geocode":[{"valueName":"EMMA_ID","value":"$code"}]}]}]}},
          {"alert":{"identifier":"green","info":[{"language":"en-GB","event":"none",
             "parameter":[{"valueName":"awareness_level","value":"1; Green; Minor"}],"area":[]}]}}
        ]}
        """.trimIndent()
        val items = MeteoAlarm.parse(body, regions)
        assertEquals(1, items.size)
        val w = items[0]
        assertEquals(WarningLevel.YELLOW, w.level)
        assertEquals("Yellow warning: Wind", w.event)
        assertEquals("Yellow wind warning", w.headline)
        assertEquals("Attiki", w.area)
        assertTrue(w.polygons.isNotEmpty())
        assertTrue(w.covers(LatLon(37.98, 23.73)))
        assertTrue(w.isActive(Instant.parse("2026-10-07T12:00:00Z")))
    }
}
