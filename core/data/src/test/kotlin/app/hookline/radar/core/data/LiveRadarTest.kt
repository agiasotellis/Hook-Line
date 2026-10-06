package app.hookline.radar.core.data

import app.hookline.radar.core.radar.Moment
import app.hookline.radar.core.radar.MomentData
import app.hookline.radar.core.radar.Radial
import app.hookline.radar.core.radar.RadialStatus
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveRadarTest {

    private fun cut(elevationNumber: Int, elevation: Float, moment: Moment, count: Int = 360, t0: Long = 0): List<Radial> =
        (0 until count).map { i ->
            Radial(
                radarId = "KTLX", timeMillis = t0 + i, azimuthNumber = i + 1, azimuthDeg = i + 0.5f, azimuthSpacingDeg = 1f,
                elevationNumber = elevationNumber, elevationDeg = elevation,
                radialStatus = when (i) {
                    0 -> RadialStatus.START_OF_ELEVATION
                    count - 1 -> RadialStatus.END_OF_ELEVATION
                    else -> RadialStatus.INTERMEDIATE
                },
                moments = mapOf(moment to MomentData(moment, 2125, 250, floatArrayOf(30f))),
            )
        }

    @Test
    fun snapsTiltsToStandardAngles() {
        assertEquals(0.5f, Tilts.snap(0.48f))
        assertEquals(0.9f, Tilts.snap(0.88f))
        assertEquals(19.5f, Tilts.snap(19.4f))
    }

    @Test
    fun mergesSplitCutsAndKeepsLatestCompleteSweep() {
        val a = VolumeAssembler()
        a.accept(7, cut(1, 0.48f, Moment.REFLECTIVITY))
        a.accept(7, cut(2, 0.52f, Moment.VELOCITY))
        assertEquals(listOf(0.5f), a.tilts())
        assertEquals(1, a.sweep(0.5f, Moment.REFLECTIVITY)!!.elevationNumber)
        assertEquals(2, a.sweep(0.5f, Moment.VELOCITY)!!.elevationNumber)

        // A SAILS re-scan of 0.5° later in the volume replaces the earlier one.
        a.accept(7, cut(5, 0.49f, Moment.REFLECTIVITY, t0 = 10_000))
        assertEquals(5, a.sweep(0.5f, Moment.REFLECTIVITY)!!.elevationNumber)

        // Higher tilt in progress (no end yet) doesn't show up.
        a.accept(7, cut(6, 1.3f, Moment.REFLECTIVITY).dropLast(1))
        assertNull(a.sweep(1.3f, Moment.REFLECTIVITY))
    }

    @Test
    fun ignoresPartialSweepWhenJoiningMidScan() {
        val a = VolumeAssembler()
        a.accept(3, cut(1, 0.5f, Moment.REFLECTIVITY).takeLast(90))
        assertTrue(a.tilts().isEmpty())
    }

    @Test
    fun findsNewestVolumeAcrossWraparound() {
        assertEquals(listOf(12), NexradSource.newestVolumeCandidates(listOf(10, 11, 12)))
        // Window wraps from 997 through 3: newest is 3.
        assertEquals(listOf(3), NexradSource.newestVolumeCandidates(listOf(997, 998, 999, 1, 2, 3)))
        assertEquals(1, NexradSource.nextVolume(999))
    }

    @Test
    fun streamEmitsSnapshotPerCompletedSweep() = runBlocking {
        val chunks = listOf(
            NexradSource.Chunk("KTLX/7/20261006-193000-001-S", 7, 1, 'S'),
            NexradSource.Chunk("KTLX/7/20261006-193030-002-I", 7, 2, 'I'),
        )
        val payloads = mapOf(
            chunks[0].key to cut(1, 0.48f, Moment.REFLECTIVITY),
            chunks[1].key to cut(2, 0.52f, Moment.VELOCITY),
        )
        val store = object : ChunkStore {
            override suspend fun volumes(site: String) = listOf(6, 7)
            override suspend fun chunks(site: String, volume: Int) = if (volume == 7) chunks else emptyList()
            override suspend fun fetch(chunk: NexradSource.Chunk) = chunk.key.toByteArray()
        }
        val live = LiveRadar(store, pollMillis = 1) { bytes -> payloads.getValue(String(bytes)) }

        val events = live.stream("KTLX").filterIsInstance<LiveEvent.Data>().take(2).toList()
        assertEquals(setOf(Moment.REFLECTIVITY), events[0].snapshot.moments(0.5f))
        assertEquals(setOf(Moment.REFLECTIVITY, Moment.VELOCITY), events[1].snapshot.moments(0.5f))
        assertEquals(7, events[1].volume)
    }

    @Test
    fun streamReportsTroubleWhenFeedIsDown() = runBlocking {
        val store = object : ChunkStore {
            override suspend fun volumes(site: String): List<Int> = throw java.io.IOException("offline")
            override suspend fun chunks(site: String, volume: Int) = emptyList<NexradSource.Chunk>()
            override suspend fun fetch(chunk: NexradSource.Chunk) = ByteArray(0)
        }
        val e = LiveRadar(store, pollMillis = 1).stream("KTLX").filterIsInstance<LiveEvent.Trouble>().first()
        assertEquals("offline", e.message)
    }
}
