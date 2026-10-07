package app.hookline.radar.core.data

import app.hookline.radar.core.odim.Odim
import app.hookline.radar.core.radar.Moment
import app.hookline.radar.core.radar.OdimAdapter
import app.hookline.radar.core.radar.SiteLocation
import app.hookline.radar.core.radar.Sweep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.TreeMap
import kotlin.math.abs

/** Read access to the EUMETNET Open Radar Data 24-hour cache (or a fake in tests). */
interface OrdStore {
    /** Keys under [prefix], in order, strictly after [startAfter] when given. */
    suspend fun list(prefix: String, startAfter: String? = null): List<String>
    /** Sub-folders directly under [prefix] (with trailing slash). */
    suspend fun folders(prefix: String): List<String>
    suspend fun fetch(key: String): ByteArray
}

/**
 * The public, keyless S3 bucket behind EUMETNET's Open Radar Data API.
 * Data are CC BY 4.0 (attribution: EUMETNET OPERA and the national services).
 */
class OrdS3(
    private val http: OkHttpClient,
    private val base: String = "https://s3.waw3-1.cloudferro.com/openradar-24h",
) : OrdStore {
    override suspend fun list(prefix: String, startAfter: String?): List<String> = page(prefix, startAfter, null).first

    override suspend fun folders(prefix: String): List<String> = page(prefix, null, "/").second

    override suspend fun fetch(key: String): ByteArray = get(base.trimEnd('/') + "/" + key)

    private suspend fun page(prefix: String, startAfter: String?, delimiter: String?): Pair<List<String>, List<String>> {
        val keys = mutableListOf<String>()
        val dirs = mutableListOf<String>()
        var token: String? = null
        var pages = 0
        do {
            val url = base.toHttpUrl().newBuilder()
                .addQueryParameter("list-type", "2")
                .addQueryParameter("prefix", prefix)
                .apply {
                    startAfter?.let { addQueryParameter("start-after", it) }
                    delimiter?.let { addQueryParameter("delimiter", it) }
                    token?.let { addQueryParameter("continuation-token", it) }
                }
                .build()
            val xml = String(get(url.toString()), Charsets.UTF_8)
            keys += S3Listing.keys(xml)
            dirs += S3Listing.prefixes(xml)
            token = S3Listing.continuationToken(xml)
        } while (token != null && ++pages < 20)
        return keys to dirs
    }

    private suspend fun get(url: String): ByteArray = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} from European radar data")
            r.body?.bytes() ?: ByteArray(0)
        }
    }
}

/**
 * One file in the cache, e.g.
 * `2026/10/07/DE/deess/SCAN/deess@20261007T0005@0.5@VRADH.h5`.
 */
data class OrdKey(
    val key: String,
    val site: String,
    val kind: String,
    val stamp: String,
    val timeMillis: Long,
    /** Elevation in degrees for per-scan files; null when the file holds a whole volume. */
    val elevation: Double?,
    val quantity: String,
) {
    companion object {
        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmm")

        fun parse(key: String): OrdKey? {
            val parts = key.split('/')
            if (parts.size < 7) return null
            val name = parts.last()
            if (!name.endsWith(".h5")) return null
            val fields = name.removeSuffix(".h5").split('@')
            if (fields.size < 4) return null
            val time = runCatching { LocalDateTime.parse(fields[1], STAMP).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
                ?: return null
            val elev = fields[2].toDoubleOrNull()
            val kind = parts[parts.size - 2]
            return OrdKey(
                key = key,
                site = fields[0],
                kind = kind,
                stamp = fields[1],
                timeMillis = time,
                elevation = if (kind == "PVOL" || elev == null || (elev == 0.0 && kind != "SCAN")) null else elev,
                quantity = fields[3].uppercase(),
            )
        }

        fun stampOf(millis: Long): String = STAMP.format(Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC))

        fun dayPrefix(millis: Long): String =
            DateTimeFormatter.ofPattern("yyyy/MM/dd/").format(Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC))
    }
}

/** Quantities we download, best first. Dual-pol isn't exchanged by OPERA yet. */
internal object OrdQuantities {
    val REFLECTIVITY = listOf("DBZH", "TH")
    val VELOCITY = listOf("VRADH", "VRAD")
}

/**
 * Live single-site data for a European radar. Every OPERA member publishes a
 * little differently (per-scan files or whole volumes, 5- to 15-minute
 * cycles), so this works from the file listing rather than assumptions:
 *
 * - the lowest tilt (reflectivity + velocity) is always downloaded;
 * - other tilts are listed on the tilt ladder and downloaded when chosen
 *   ([wantedTilts]), keeping mobile data to ~0.5 MB per update.
 */
class OrdRadar(
    private val store: OrdStore,
    private val pollMillis: Long = 60_000,
    private val clock: () -> Long = System::currentTimeMillis,
    private val decode: (ByteArray) -> Odim.OdimFile = Odim::read,
) {
    fun stream(site: RadarSite, wantedTilts: StateFlow<Set<Float>>): Flow<LiveEvent> = flow {
        val listing = Listing(site)
        val fetched = HashSet<String>()
        val sweeps = HashMap<Pair<Float, Moment>, Sweep>()
        val sweepStamp = HashMap<Pair<Float, Moment>, String>()
        var location: SiteLocation? = null
        var attempt = 0
        var lastEmitted = ""
        while (true) {
            var changed = false
            try {
                listing.refresh(clock() - LOOKBACK_MS)
                val latest = listing.latestComplete()
                if (latest == null) {
                    emit(LiveEvent.Trouble("No recent data from ${site.name}", ++attempt))
                } else {
                    attempt = 0
                    val files = listing.byStamp[latest].orEmpty()
                    val elevations = files.mapNotNull { it.elevation }.distinct().sorted()
                    val want = HashSet<Double>()
                    elevations.firstOrNull()?.let { want += it }
                    for (t in wantedTilts.value) {
                        elevations.minByOrNull { abs(it - t) }?.takeIf { abs(it - t) < 0.3 }?.let { want += it }
                    }
                    val toFetch = mutableListOf<OrdKey>()
                    if (elevations.isEmpty()) {
                        // Whole-volume files: one per quantity.
                        pick(files, OrdQuantities.REFLECTIVITY)?.let { toFetch += it }
                        pick(files, OrdQuantities.VELOCITY)?.let { toFetch += it }
                    } else {
                        for (e in want) {
                            val atElev = files.filter { it.elevation == e }
                            pick(atElev, OrdQuantities.REFLECTIVITY)?.let { toFetch += it }
                            pick(atElev, OrdQuantities.VELOCITY)?.let { toFetch += it }
                        }
                    }
                    for (k in toFetch) {
                        if (k.key in fetched) continue
                        val file = withContext(Dispatchers.Default) { decode(store.fetch(k.key)) }
                        fetched += k.key
                        if (!file.lat.isNaN()) location = SiteLocation(file.lat.toFloat(), file.lon.toFloat(), file.heightM.toInt())
                        for (scan in file.scans) {
                            val tilt = OdimAdapter.tiltKey(scan.elevationDeg)
                            val sweep = withContext(Dispatchers.Default) {
                                OdimAdapter.toSweep(scan, site.id, (scan.elevationDeg * 10).toInt(), location)
                            } ?: continue
                            for (m in Moment.entries) {
                                if (!sweep.has(m)) continue
                                val key = tilt to m
                                // Never let an older file replace a newer sweep.
                                if ((sweepStamp[key] ?: "") <= k.stamp) {
                                    sweeps[key] = sweep
                                    sweepStamp[key] = k.stamp
                                    changed = true
                                }
                            }
                        }
                    }
                    val listed = elevations.map { OdimAdapter.tiltKey(it) }
                    val signature = latest + listed.joinToString() + sweepStamp.entries.sortedBy { it.key.toString() }.joinToString()
                    if ((changed || signature != lastEmitted) && sweeps.isNotEmpty()) {
                        lastEmitted = signature
                        emit(LiveEvent.Data(LiveSnapshot(site.id, location, HashMap(sweeps), listed), stampNumber(latest)))
                    }
                    if (fetched.size > 400) fetched.clear()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emit(LiveEvent.Trouble(e.message ?: "Can't reach European radar data", ++attempt))
            }
            // Sleep until the next poll, or wake early when the user picks a new tilt.
            val before = wantedTilts.value
            withTimeoutOrNull(if (attempt > 0) backoff(attempt) else pollMillis) {
                wantedTilts.first { it != before }
            }
        }
    }

    /**
     * Lowest-tilt reflectivity and velocity for the [count] cycles before the
     * newest, newest first: about 0.3–1 MB per frame.
     */
    fun loopFrames(site: RadarSite, count: Int): Flow<BackfillFrame> = flow {
        val listing = Listing(site)
        listing.refresh(clock() - LOOKBACK_MS - count * 15 * 60_000L)
        val stamps = listing.byStamp.descendingKeySet().toList()
        val latest = listing.latestComplete() ?: return@flow
        var emitted = 0
        for (stamp in stamps) {
            if (stamp >= latest) continue
            if (emitted >= count) break
            val files = listing.byStamp[stamp].orEmpty()
            val lowest = files.mapNotNull { it.elevation }.minOrNull()
            val candidates = if (lowest == null) files else files.filter { it.elevation == lowest }
            var ref: Sweep? = null
            var vel: Sweep? = null
            for ((names, isRef) in listOf(OrdQuantities.REFLECTIVITY to true, OrdQuantities.VELOCITY to false)) {
                val k = pick(candidates, names) ?: continue
                val file = try {
                    withContext(Dispatchers.Default) { decode(store.fetch(k.key)) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    continue
                }
                val loc = if (file.lat.isNaN()) null else SiteLocation(file.lat.toFloat(), file.lon.toFloat(), file.heightM.toInt())
                val scan = file.scans.minByOrNull { it.elevationDeg } ?: continue
                val sweep = OdimAdapter.toSweep(scan, site.id, (scan.elevationDeg * 10).toInt(), loc) ?: continue
                if (isRef) ref = sweep else vel = sweep
            }
            if (ref != null || vel != null) {
                emit(BackfillFrame(stampNumber(stamp), ref, vel))
                emitted++
            }
        }
    }

    private fun pick(files: List<OrdKey>, names: List<String>): OrdKey? =
        names.firstNotNullOfOrNull { n -> files.firstOrNull { it.quantity == n } }

    /** Incremental listing of one site's files across UTC day folders. */
    private inner class Listing(val site: RadarSite) {
        val byStamp = TreeMap<String, MutableList<OrdKey>>()
        private val cursors = HashMap<String, String>() // folder prefix -> last key seen
        private var kinds: List<String>? = null

        suspend fun refresh(sinceMillis: Long) {
            val now = clock()
            val days = linkedSetOf(OrdKey.dayPrefix(sinceMillis), OrdKey.dayPrefix(now))
            for (day in days) {
                val sitePrefix = "$day${site.country}/${site.id}/"
                val folders = kinds ?: store.folders(sitePrefix).map { it.removePrefix(sitePrefix).trimEnd('/') }
                    .filter { it.isNotEmpty() }
                    .also { if (it.isNotEmpty()) kinds = it }
                for (kind in folders.ifEmpty { listOf("SCAN", "PVOL") }) {
                    val folder = "$sitePrefix$kind/"
                    val startAfter = cursors[folder] ?: (folder + site.id + "@" + OrdKey.stampOf(sinceMillis))
                    val keys = store.list(folder, startAfter)
                    for (k in keys) {
                        val parsed = OrdKey.parse(k) ?: continue
                        byStamp.getOrPut(parsed.stamp) { mutableListOf() }.add(parsed)
                    }
                    keys.lastOrNull()?.let { cursors[folder] = it }
                }
            }
            val oldest = OrdKey.stampOf(sinceMillis)
            while (byStamp.isNotEmpty() && byStamp.firstKey() < oldest) byStamp.pollFirstEntry()
        }

        /** Newest cycle whose lowest tilt (or whole volume) has reflectivity. */
        fun latestComplete(): String? {
            val lowestEver = byStamp.values.flatten().mapNotNull { it.elevation }.minOrNull()
            for (stamp in byStamp.descendingKeySet()) {
                val files = byStamp[stamp].orEmpty()
                val ok = if (lowestEver == null) {
                    files.any { it.quantity in OrdQuantities.REFLECTIVITY }
                } else {
                    files.any { it.elevation != null && abs(it.elevation - lowestEver) < 0.05 && it.quantity in OrdQuantities.REFLECTIVITY }
                }
                if (ok) return stamp
            }
            return null
        }
    }

    private fun backoff(attempt: Int): Long = (10_000L * (1 shl (attempt - 1).coerceIn(0, 3))).coerceAtMost(120_000L)

    companion object {
        /** How far back to look for the newest complete cycle. */
        const val LOOKBACK_MS = 40 * 60_000L

        /** A stable int for a "yyyyMMddTHHmm" stamp (minutes since epoch). */
        fun stampNumber(stamp: String): Int =
            OrdKey.parse("0/0/0/0/x/SCAN/x@$stamp@0.5@DBZH.h5")?.let { (it.timeMillis / 60_000L).toInt() } ?: 0
    }
}

/** The OPERA pan-European maximum-reflectivity composite (5-minute, 1 km). */
class OperaComposite(
    private val store: OrdStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Newest DBZH composite keys, newest first. */
    suspend fun latestKeys(count: Int): List<OrdKey> {
        val now = clock()
        val since = now - (count * 5 + 45) * 60_000L
        val out = mutableListOf<OrdKey>()
        for (day in linkedSetOf(OrdKey.dayPrefix(since), OrdKey.dayPrefix(now))) {
            val folder = "${day}OPERA/COMP/"
            store.list(folder, folder + "OPERA@" + OrdKey.stampOf(since))
                .filter { it.endsWith("@DBZH.h5") }
                .mapNotNullTo(out) { compositeKey(it) }
        }
        return out.sortedByDescending { it.timeMillis }.take(count)
    }

    suspend fun load(key: OrdKey): Odim.Grid {
        val bytes = store.fetch(key.key)
        return withContext(Dispatchers.Default) {
            Odim.read(bytes).grid ?: throw IOException("No composite grid in ${key.key}")
        }
    }

    suspend fun fetchBytes(key: OrdKey): ByteArray = store.fetch(key.key)

    companion object {
        /** `2026/10/07/OPERA/COMP/OPERA@20261007T0005@0@DBZH.h5` */
        fun compositeKey(key: String): OrdKey? {
            val name = key.substringAfterLast('/')
            val f = name.removeSuffix(".h5").split('@')
            if (f.size < 4) return null
            val t = runCatching {
                LocalDateTime.parse(f[1], DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmm")).toInstant(ZoneOffset.UTC).toEpochMilli()
            }.getOrNull() ?: return null
            return OrdKey(key, "OPERA", "COMP", f[1], t, null, f[3].uppercase())
        }

        fun decode(bytes: ByteArray): Odim.Grid = Odim.read(bytes).grid ?: throw IOException("No composite grid")
    }
}
