package app.hookline.radar.core.data

import app.hookline.radar.core.radar.Level2Decoder
import app.hookline.radar.core.radar.Moment
import app.hookline.radar.core.radar.Radial
import app.hookline.radar.core.radar.RadialStatus
import app.hookline.radar.core.radar.SiteLocation
import app.hookline.radar.core.radar.Sweep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.math.abs

/** Standard WSR-88D elevation angles; measured angles snap to the nearest. */
object Tilts {
    val STANDARD = floatArrayOf(0.5f, 0.9f, 1.3f, 1.8f, 2.4f, 3.1f, 4.0f, 5.1f, 6.4f, 8.0f, 10.0f, 12.5f, 15.6f, 19.5f)

    fun snap(elevationDeg: Float): Float = STANDARD.minBy { abs(it - elevationDeg) }
}

/**
 * Builds sweeps from radials as chunks arrive, and keeps the newest
 * *completed* sweep for every (tilt, moment) — across volumes, so the 0.5°
 * view stays on screen while the radar is scanning higher tilts, and SAILS
 * re-scans of 0.5° replace it as soon as they finish.
 *
 * Low tilts come as split cuts (a reflectivity-only surveillance cut and a
 * Doppler cut); keying by moment merges them naturally.
 */
class VolumeAssembler {
    private var volume: Int? = null
    private val building = LinkedHashMap<Int, MutableList<Radial>>() // by elevation number
    private val complete = HashMap<Pair<Float, Moment>, Sweep>()
    var site: SiteLocation? = null
        private set
    var radarId: String? = null
        private set

    /** Feed one decoded chunk. Returns true when a sweep completed. */
    fun accept(volumeNumber: Int, radials: List<Radial>): Boolean {
        if (volume != volumeNumber) {
            volume = volumeNumber
            building.clear()
        }
        var completed = false
        for (r in radials) {
            site = r.site ?: site
            radarId = r.radarId
            if (r.radialStatus == RadialStatus.START_OF_ELEVATION || r.radialStatus == RadialStatus.START_OF_VOLUME ||
                r.radialStatus == RadialStatus.START_OF_ELEVATION_LAST_CUT
            ) {
                building[r.elevationNumber] = mutableListOf()
            }
            building.getOrPut(r.elevationNumber) { mutableListOf() } += r
            if (r.radialStatus == RadialStatus.END_OF_ELEVATION || r.radialStatus == RadialStatus.END_OF_VOLUME) {
                finish(r.elevationNumber)
                completed = true
            }
        }
        return completed
    }

    private fun finish(elevationNumber: Int) {
        val rs = building.remove(elevationNumber) ?: return
        if (rs.size < 180) return // partial cut (joined mid-sweep); wait for a full one
        val elev = rs.map { it.elevationDeg }.average().toFloat()
        val sweep = Sweep(elevationNumber, elev, rs.sortedBy { it.azimuthDeg })
        val tilt = Tilts.snap(elev)
        Moment.entries.filter { sweep.has(it) }.forEach { complete[tilt to it] = sweep }
    }

    fun tilts(): List<Float> = complete.keys.map { it.first }.distinct().sorted()

    fun sweep(tilt: Float, moment: Moment): Sweep? = complete[tilt to moment]

    fun snapshot(): LiveSnapshot = LiveSnapshot(radarId, site, HashMap(complete))
}

data class LiveSnapshot(
    val radarId: String?,
    val site: SiteLocation?,
    val sweeps: Map<Pair<Float, Moment>, Sweep>,
    /** Tilts the radar has published but that aren't downloaded yet (loaded on demand). */
    val listedTilts: List<Float> = emptyList(),
) {
    val tilts: List<Float> get() = (sweeps.keys.map { it.first } + listedTilts).distinct().sorted()
    fun sweep(tilt: Float, moment: Moment): Sweep? = sweeps[tilt to moment]
    fun moments(tilt: Float): Set<Moment> = sweeps.keys.filter { it.first == tilt }.map { it.second }.toSet()
}

sealed interface LiveEvent {
    data class Data(val snapshot: LiveSnapshot, val volume: Int) : LiveEvent
    /** Network or decode trouble; [lastGood] is kept on screen with its age shown. */
    data class Trouble(val message: String, val attempt: Int) : LiveEvent
}

/**
 * Polls the real-time chunk bucket for one site and emits a snapshot every
 * time a sweep completes. Cancel the collecting coroutine to stop.
 */
class LiveRadar(
    private val store: ChunkStore,
    private val pollMillis: Long = 10_000,
    private val decode: (ByteArray) -> List<Radial> = Level2Decoder::decodeRadials,
) {
    fun stream(site: String): Flow<LiveEvent> = flow {
        val assembler = VolumeAssembler()
        val seen = HashSet<String>()
        var volume: Int? = null
        var volumeEnded = false
        var idlePolls = 0
        var attempt = 0
        while (true) {
            try {
                if (volume == null) {
                    volume = findNewestVolume(site)
                    volumeEnded = false
                    idlePolls = 0
                    if (volume == null) {
                        emit(LiveEvent.Trouble("No live data for $site right now", ++attempt))
                        delay(backoff(attempt))
                        continue
                    }
                }
                val v = volume ?: continue

                val chunks = store.chunks(site, v).filter { it.key !in seen }
                for (c in chunks) {
                    val radials = decode(store.fetch(c))
                    seen += c.key
                    if (c.isEnd) volumeEnded = true
                    if (assembler.accept(c.volume, radials)) emit(LiveEvent.Data(assembler.snapshot(), c.volume))
                }
                attempt = 0
                idlePolls = if (chunks.isEmpty()) idlePolls + 1 else 0

                // Volume finished: move to the next number once it appears.
                val next = NexradSource.nextVolume(v)
                if (volumeEnded && store.chunks(site, next).isNotEmpty()) {
                    volume = next
                    volumeEnded = false
                }
                // Nothing new for ~10 minutes (radar restart, skipped number): rediscover.
                if (idlePolls * pollMillis > 600_000) volume = null

                if (seen.size > 2_000) seen.clear() // keys are unique per volume; cheap reset
                delay(pollMillis)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emit(LiveEvent.Trouble(e.message ?: "Can't reach the radar feed", ++attempt))
                delay(backoff(attempt))
            }
        }
    }

    private suspend fun findNewestVolume(site: String): Int? {
        val candidates = NexradSource.newestVolumeCandidates(store.volumes(site))
        if (candidates.size <= 1) return candidates.firstOrNull()
        // Ambiguous window: pick the candidate whose last chunk is newest.
        return candidates.maxByOrNull { v -> store.chunks(site, v).maxOfOrNull { it.stamp } ?: "" }
    }

    private fun backoff(attempt: Int): Long = (5_000L * (1 shl (attempt - 1).coerceIn(0, 4))).coerceAtMost(60_000L)
}

/** The lowest-tilt sweeps recovered from one earlier volume. */
data class BackfillFrame(val volume: Int, val reflectivity: Sweep?, val velocity: Sweep?)

/**
 * Fills the loop with the 0.5° sweeps of earlier volumes still held in the
 * real-time bucket. Only the first few chunks of each volume are fetched —
 * the lowest tilt is scanned first — so a frame costs roughly 1–2 MB instead
 * of a 10–20 MB full volume. Newest first, so the loop fills from "now" back.
 */
class LoopBackfill(
    private val store: ChunkStore,
    private val decode: (ByteArray) -> List<Radial> = Level2Decoder::decodeRadials,
    private val maxChunksPerVolume: Int = 6,
) {
    fun frames(site: String, latestVolume: Int, count: Int): Flow<BackfillFrame> = flow {
        var v = latestVolume
        repeat(count) {
            v = previousVolume(v)
            val chunks = try { store.chunks(site, v) } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
            if (chunks.isEmpty()) return@flow // reached the end of what the bucket keeps
            val asm = VolumeAssembler()
            for (c in chunks.take(maxChunksPerVolume)) {
                try {
                    asm.accept(v, decode(store.fetch(c)))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    break
                }
                if (asm.sweep(LOOP_TILT, Moment.REFLECTIVITY) != null && asm.sweep(LOOP_TILT, Moment.VELOCITY) != null) break
            }
            val ref = asm.sweep(LOOP_TILT, Moment.REFLECTIVITY)
            val vel = asm.sweep(LOOP_TILT, Moment.VELOCITY)
            if (ref != null || vel != null) emit(BackfillFrame(v, ref, vel))
        }
    }

    companion object {
        const val LOOP_TILT = 0.5f
        fun previousVolume(v: Int): Int = if (v <= 1) NexradSource.MAX_VOLUME else v - 1
    }
}
