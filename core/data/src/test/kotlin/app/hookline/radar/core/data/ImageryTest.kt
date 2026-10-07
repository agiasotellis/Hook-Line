package app.hookline.radar.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ImageryTest {
    @Test fun picksMtgOverMsg() {
        val l = EumetsatLayers.pick(listOf("msg_fes:ir108", "mtg_fd:ir105_hrfi", "msg_iodc:ir108", "msg_fes:rgb_natural"))
        assertEquals("mtg_fd:ir105_hrfi", l.zeroDegree)
        assertEquals("msg_iodc:ir108", l.indianOcean)
    }

    @Test fun parsesCapabilities() {
        val xml = "<Layer><Name>msg_fes:ir108</Name></Layer><Layer><Name>\n msg_iodc:ir108 </Name></Layer><Name>WMS</Name>"
        val l = EumetsatLayers.parse(xml)
        assertEquals("msg_fes:ir108", l.zeroDegree)
        assertEquals("msg_iodc:ir108", l.indianOcean)
        assertNull(EumetsatLayers.parse("<Name>other:thing</Name>").zeroDegree)
    }

    @Test fun eumetsatUsesLastFullHour() {
        assertEquals("2026-10-07T11:00:00Z", Imagery.eumetsatTime(Instant.parse("2026-10-07T12:10:00Z")))
        assertEquals("2026-10-07T12:00:00Z", Imagery.eumetsatTime(Instant.parse("2026-10-07T12:40:00Z")))
    }

    @Test fun satelliteBandsCoverTheGlobeOnce() {
        val s = Imagery.satellites(EumetsatLayers("a:b", "c:d")).sortedBy { it.west }
        assertEquals(-180.0, s.first().west, 0.0)
        assertEquals(180.0, s.last().east, 0.0)
        s.zipWithNext().forEach { (a, b) -> assertEquals(a.east, b.west, 0.0) }
        assertTrue(s.first { it.id == "sat-msg-0" }.url.contains("TIME="))
    }

    @Test fun mrmsCoverage() {
        assertTrue(Imagery.inMrms(LatLon(35.3, -97.3)))
        assertTrue(Imagery.inMrms(LatLon(61.2, -149.9)))
        assertFalse(Imagery.inMrms(LatLon(37.98, 23.73)))
        assertTrue(Imagery.mrms(5).url.contains("{bbox-epsg-3857}"))
    }

    @Test fun dwdListing() {
        val html = """
            <a href="ras07-pcpng01_sweeph5onem_rhohv_00-2026100712000000-ess-10410-hd5">x</a>
            <a href="ras07-pcpng01_sweeph5onem_rhohv_00-2026100712050000-ess-10410-hd5">x</a>
            <a href="ras07-pcpng01_sweeph5onem_rhohv_00-2026100711550000-ess-10410-hd5">x</a>
        """
        assertEquals("ras07-pcpng01_sweeph5onem_rhohv_00-2026100712050000-ess-10410-hd5", DwdDualPol.newest(html, "rhohv", "ess"))
        assertNull(DwdDualPol.newest(html, "zdr", "ess"))
        assertEquals("ess", DwdDualPol.dwdCode("deess"))
        assertNull(DwdDualPol.dwdCode("nlhrw"))
    }
}
