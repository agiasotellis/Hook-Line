package app.hookline.radar.core.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.LocalDate
import java.util.Locale

/** What the live loop needs from a chunk store; faked in tests. */
interface ChunkStore {
    /** Volume directory numbers currently present for a site (1..999, wrapping). */
    suspend fun volumes(site: String): List<Int>
    /** Chunks in one volume directory, oldest first. */
    suspend fun chunks(site: String, volume: Int): List<NexradSource.Chunk>
    suspend fun fetch(chunk: NexradSource.Chunk): ByteArray
}

/**
 * Reads NEXRAD Level II straight from NOAA's public AWS Open Data buckets.
 * No account, no keys, no Hookline server in between — which is why a
 * one-time price is sustainable.
 *
 * - Real-time: `unidata-nexrad-level2-chunks/<SITE>/<volume#>/<yyyyMMdd-HHmmss>-<seq>-<S|I|E>`
 * - Archive:   `unidata-nexrad-level2/<yyyy>/<MM>/<dd>/<SITE>/<SITE><yyyyMMdd_HHmmss>_V06`
 */
class NexradSource(
    private val http: OkHttpClient,
    private val realtimeBase: String = "https://unidata-nexrad-level2-chunks.s3.amazonaws.com",
    private val archiveBase: String = "https://unidata-nexrad-level2.s3.amazonaws.com",
) : ChunkStore {
    /** A chunk of the volume currently being scanned. */
    data class Chunk(val key: String, val volume: Int, val sequence: Int, val kind: Char) {
        val isStart get() = kind == 'S'
        val isEnd get() = kind == 'E'
        /** "yyyyMMdd-HHmmss" from the key; sortable. */
        val stamp: String get() = key.substringAfterLast('/').take(15)
    }

    override suspend fun volumes(site: String): List<Int> =
        listPrefixes(realtimeBase, "$site/").mapNotNull { it.removePrefix("$site/").trimEnd('/').toIntOrNull() }

    override suspend fun chunks(site: String, volume: Int): List<Chunk> =
        listKeys(realtimeBase, "$site/$volume/").mapNotNull(::parseChunkKey).sortedBy { it.sequence }

    override suspend fun fetch(chunk: Chunk): ByteArray = get("$realtimeBase/${chunk.key}")

    /** Archived volume keys for a site on a UTC date. */
    suspend fun archiveKeys(site: String, date: LocalDate): List<String> {
        val prefix = "%04d/%02d/%02d/%s/".format(Locale.US, date.year, date.monthValue, date.dayOfMonth, site)
        return listKeys(archiveBase, prefix).filterNot { it.endsWith("_MDM") }.sorted()
    }

    suspend fun fetchArchive(key: String): ByteArray = get("$archiveBase/$key")

    private suspend fun listKeys(base: String, prefix: String): List<String> = list(base, prefix, null).first

    private suspend fun listPrefixes(base: String, prefix: String): List<String> = list(base, prefix, "/").second

    private suspend fun list(base: String, prefix: String, delimiter: String?): Pair<List<String>, List<String>> {
        val keys = mutableListOf<String>()
        val prefixes = mutableListOf<String>()
        var token: String? = null
        do {
            val url = base.toHttpUrl().newBuilder()
                .addQueryParameter("list-type", "2")
                .addQueryParameter("prefix", prefix)
                .apply {
                    delimiter?.let { addQueryParameter("delimiter", it) }
                    token?.let { addQueryParameter("continuation-token", it) }
                }
                .build()
            val xml = String(get(url.toString()), Charsets.UTF_8)
            keys += S3Listing.keys(xml)
            prefixes += S3Listing.prefixes(xml)
            token = S3Listing.continuationToken(xml)
        } while (token != null)
        return keys to prefixes
    }

    private suspend fun get(url: String): ByteArray = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} for $url")
            r.body?.bytes() ?: ByteArray(0)
        }
    }

    companion object {
        private val CHUNK = Regex("""^([A-Z0-9]{4})/(\d+)/\d{8}-\d{6}-(\d+)-([SIE])$""")
        const val MAX_VOLUME = 999

        fun parseChunkKey(key: String): Chunk? = CHUNK.matchEntire(key)?.let { m ->
            Chunk(key, m.groupValues[2].toInt(), m.groupValues[3].toInt(), m.groupValues[4][0])
        }

        fun nextVolume(v: Int): Int = v % MAX_VOLUME + 1

        /**
         * Volume numbers run 1..999 and wrap, and the bucket keeps a recent
         * window of them. The newest is the end of that circular run: a number
         * whose successor is absent. Returns all such candidates (usually one).
         */
        fun newestVolumeCandidates(present: Collection<Int>): List<Int> {
            val set = present.toSet()
            if (set.isEmpty()) return emptyList()
            val ends = set.filter { nextVolume(it) !in set }
            return ends.ifEmpty { set.toList() }.sorted()
        }
    }
}

/** Minimal S3 ListObjectsV2 XML reader; avoids an XML dependency. */
internal object S3Listing {
    private val KEY = Regex("<Key>([^<]+)</Key>")
    private val PREFIX = Regex("<CommonPrefixes>\\s*<Prefix>([^<]+)</Prefix>")
    private val TOKEN = Regex("<NextContinuationToken>([^<]+)</NextContinuationToken>")

    fun keys(xml: String): List<String> = KEY.findAll(xml).map { unescape(it.groupValues[1]) }.toList()
    fun prefixes(xml: String): List<String> = PREFIX.findAll(xml).map { unescape(it.groupValues[1]) }.toList()
    fun continuationToken(xml: String): String? = TOKEN.find(xml)?.groupValues?.get(1)?.let(::unescape)

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'")
}
