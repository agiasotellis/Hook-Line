package app.hookline.radar.stormmode

import android.content.Context
import app.hookline.radar.core.data.LatLon
import app.hookline.radar.core.data.LocalGrid
import app.hookline.radar.core.data.RainForecast
import app.hookline.radar.core.data.RainForecaster
import app.hookline.radar.core.data.RainSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/**
 * The rain forecast for one point, on demand (the main screen's card).
 * Keeps the last radar grid on disk so the next refresh has two pictures to
 * measure motion from, and reuses the current grid until a newer radar
 * picture is published.
 */
class RainOutlook(private val context: Context, private val source: RainSource) {

    sealed interface Result {
        data class Ready(val forecast: RainForecast) : Result
        data object NoCoverage : Result
    }

    private var lastKind: RainSource.Kind? = null
    private var lastKey: String? = null
    private var lastPoint: LatLon? = null
    private var lastCur: LocalGrid? = null
    private var lastPrev: LocalGrid? = null

    suspend fun forecast(point: LatLon, now: Long = System.currentTimeMillis()): Result {
        val kind = source.kindFor(point) ?: return Result.NoCoverage
        val keys = source.latestKeys(kind)
        // Covered, but no recent picture: the feed is behind. That's an error, not "no radar here".
        val (key, time) = keys.firstOrNull() ?: throw IllegalStateException("No recent radar picture")
        val near = lastPoint?.let { abs(it.lat - point.lat) < 0.02 && abs(it.lon - point.lon) < 0.02 } == true
        if (key == lastKey && near && lastCur != null) {
            return Result.Ready(analyse(kind, lastCur!!, lastPrev, now))
        }
        val frame = source.load(kind, key, time)
        val cur = withContext(Dispatchers.Default) { frame.grid(point, AlertChecker.CELL_KM, AlertChecker.GRID) }
        // Previous picture: the grid saved last time if it's 5–25 minutes older and for the same spot.
        var prev = load()?.takeIf { g ->
            time - g.timeMillis in 300_000L..1_500_000L && g.size == cur.size &&
                abs(g.centerLat - cur.centerLat) < 1e-4 && abs(g.centerLon - cur.centerLon) < 1e-4
        }
        if (prev == null && keys.size > 1) {
            val older = source.load(kind, keys[1].first, keys[1].second)
            prev = withContext(Dispatchers.Default) { older.grid(point, AlertChecker.CELL_KM, AlertChecker.GRID) }
        }
        withContext(Dispatchers.IO) { runCatching { file().writeBytes(cur.encode()) } }
        lastKind = kind; lastKey = key; lastPoint = point; lastCur = cur; lastPrev = prev
        return Result.Ready(analyse(kind, cur, prev, now))
    }

    /** The last forecast re-judged at [now] (offline): its confidence drops as the picture ages. */
    suspend fun reanalyse(now: Long = System.currentTimeMillis()): RainForecast? {
        val kind = lastKind ?: return null
        val cur = lastCur ?: return null
        return analyse(kind, cur, lastPrev, now)
    }

    /** The motion search is ~16M comparisons: keep it off the main thread. */
    private suspend fun analyse(kind: RainSource.Kind, cur: LocalGrid, prev: LocalGrid?, now: Long) =
        withContext(Dispatchers.Default) { RainForecaster.analyse(kind, cur, prev, now) }

    private fun file() = File(File(context.filesDir, "nowcast").apply { mkdirs() }, "here.bin")
    private suspend fun load(): LocalGrid? = withContext(Dispatchers.IO) {
        file().takeIf { it.exists() }?.let { runCatching { LocalGrid.decode(it.readBytes()) }.getOrNull() }
    }
}
