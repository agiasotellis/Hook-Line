package app.hookline.radar.core.data

import app.hookline.radar.core.radar.Moment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrdRadarTest {
    private fun fixture(name: String): ByteArray =
        javaClass.getResourceAsStream("/odim/$name")!!.use { it.readBytes() }

    private val essen = Sites.byId("deess")!!

    /** 06:30 UTC on 7 Oct 2026. */
    private val now = 1791354600000L

    /** A fake bucket: two 5-minute cycles of per-scan files at two tilts. */
    private inner class FakeStore : OrdStore {
        val keys = listOf("0610", "0615").flatMap { hm ->
            listOf("0.5@DBZH", "0.5@VRADH", "0.5@TH", "1.5@DBZH", "1.5@VRADH").map { "2026/10/07/DE/deess/SCAN/deess@20261007T$hm@$it.h5" }
        }.sorted()
        val fetched = mutableListOf<String>()

        override suspend fun list(prefix: String, startAfter: String?): List<String> =
            keys.filter { it.startsWith(prefix) && (startAfter == null || it > startAfter) }

        override suspend fun folders(prefix: String): List<String> =
            if (keys.any { it.startsWith(prefix) }) listOf(prefix + "SCAN/") else emptyList()

        override suspend fun fetch(key: String): ByteArray {
            fetched += key
            return when {
                "@0.5@VRADH" in key -> fixture("deess_scan_0.5_VRADH_v114.h5")
                "@1.5@" in key -> fixture("deess_scan_1.5_DBZH.h5")
                else -> fixture("deess_scan_0.5_DBZH.h5")
            }
        }
    }

    @Test
    fun catalogueHasEuropeanSitesWithCountryFolders() {
        assertEquals("DE", essen.country)
        assertEquals(Network.OPERA, essen.network)
        assertTrue(Sites.ALL.count { it.network == Network.OPERA } > 100)
        assertTrue(Sites.ALL.count { it.network == Network.NEXRAD } > 150)
        // Nearest radar to Athens with open single-site data is far away: Greece shares only via the composite.
        assertNull(Sites.nearest(LatLon(37.98, 23.73)))
        assertTrue(Coverage.inOperaComposite(LatLon(37.98, 23.73)))
        assertEquals("deess", Sites.nearest(LatLon(51.45, 7.0))!!.id)
    }

    @Test
    fun parsesKeys() {
        val k = OrdKey.parse("2026/10/07/DE/deess/SCAN/deess@20261007T0615@1.5@VRADH.h5")!!
        assertEquals("deess", k.site)
        assertEquals("SCAN", k.kind)
        assertEquals(1.5, k.elevation!!, 1e-9)
        assertEquals("VRADH", k.quantity)
        assertEquals(1791353700000L, k.timeMillis)
        assertNull(OrdKey.parse("2026/10/07/DE/deess/SCAN/readme.txt"))
        val c = OperaComposite.compositeKey("2026/10/07/OPERA/COMP/OPERA@20261007T0005@0@DBZH.h5")!!
        assertEquals("DBZH", c.quantity)
        assertEquals("20261007T0005", c.stamp)
    }

    @Test
    fun streamsLowestTiltAndLoadsOtherTiltsOnDemand() = runBlocking {
        val store = FakeStore()
        val wanted = MutableStateFlow(emptySet<Float>())
        val radar = OrdRadar(store, pollMillis = 50, clock = { now })

        val first = radar.stream(essen, wanted).filterIsInstance<LiveEvent.Data>().first()
        val snap = first.snapshot
        // Newest cycle, lowest tilt: reflectivity (DBZH preferred over TH) and velocity.
        assertEquals(setOf(Moment.REFLECTIVITY, Moment.VELOCITY), snap.moments(0.5f))
        assertEquals(listOf(0.5f, 1.5f), snap.tilts)
        assertNull(snap.sweep(1.5f, Moment.REFLECTIVITY))
        assertTrue(store.fetched.all { "T0615" in it })
        assertTrue(store.fetched.none { "@TH" in it })
        assertNotNull(snap.site)

        // Asking for 1.5 deg downloads it.
        wanted.value = setOf(1.5f)
        val withTilt = radar.stream(essen, wanted).filterIsInstance<LiveEvent.Data>()
            .first { it.snapshot.sweep(1.5f, Moment.REFLECTIVITY) != null }
        assertEquals(1.5f, withTilt.snapshot.sweep(1.5f, Moment.REFLECTIVITY)!!.elevationDeg, 1e-6f)
    }

    @Test
    fun loopFramesComeFromEarlierCycles() = runBlocking {
        val store = FakeStore()
        val frames = OrdRadar(store, clock = { now }).loopFrames(essen, 5).toList()
        assertEquals(1, frames.size) // only 06:10 is older than the newest cycle
        assertNotNull(frames[0].reflectivity)
        assertNotNull(frames[0].velocity)
        assertTrue(store.fetched.all { "T0610@0.5" in it })
    }

    @Test
    fun reportsTroubleWhenNothingIsPublished() = runBlocking {
        val empty = object : OrdStore {
            override suspend fun list(prefix: String, startAfter: String?) = emptyList<String>()
            override suspend fun folders(prefix: String) = emptyList<String>()
            override suspend fun fetch(key: String) = ByteArray(0)
        }
        val e = OrdRadar(empty, pollMillis = 10, clock = { now })
            .stream(essen, MutableStateFlow(emptySet())).filterIsInstance<LiveEvent.Trouble>().take(1).toList()
        assertTrue(e.single().message.contains("Essen"))
    }

    @Test
    fun compositeKeysNewestFirst() = runBlocking {
        val store = object : OrdStore {
            val keys = listOf("0555", "0600", "0605", "0610", "0615", "0620", "0625").flatMap { hm ->
                listOf("DBZH.h5", "DBZH.tiff", "RATE.h5").map { "2026/10/07/OPERA/COMP/OPERA@20261007T$hm@0@$it" }
            }.sorted()
            override suspend fun list(prefix: String, startAfter: String?) =
                keys.filter { it.startsWith(prefix) && (startAfter == null || it > startAfter) }
            override suspend fun folders(prefix: String) = emptyList<String>()
            override suspend fun fetch(key: String) = fixture("opera_comp_laea.h5")
        }
        val c = OperaComposite(store, clock = { now })
        val keys = c.latestKeys(3)
        assertEquals(listOf("20261007T0625", "20261007T0620", "20261007T0615"), keys.map { it.stamp })
        val grid = c.load(keys.first())
        assertEquals(380, grid.xsize)
    }
}
