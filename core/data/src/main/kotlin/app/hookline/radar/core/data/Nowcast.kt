package app.hookline.radar.core.data

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A small square of radar data around a place, on a local east/north grid.
 * Row 0 is the northern edge; values are rain rate (mm/h) or reflectivity
 * (dBZ); NaN = no data.
 */
class LocalGrid(
    val centerLat: Double,
    val centerLon: Double,
    val cellKm: Double,
    val size: Int,
    val values: FloatArray,
    val timeMillis: Long,
) {
    val half: Int get() = size / 2

    operator fun get(col: Int, row: Int): Float =
        if (col in 0 until size && row in 0 until size) values[row * size + col] else Float.NaN

    /** Lat/lon of a cell centre. */
    fun latLon(col: Int, row: Int): LatLon {
        val northKm = (half - row) * cellKm
        val eastKm = (col - half) * cellKm
        return LatLon(centerLat + northKm / 111.32, centerLon + eastKm / (111.32 * cos(Math.toRadians(centerLat))))
    }

    fun encode(): ByteArray {
        val b = java.nio.ByteBuffer.allocate(40 + values.size * 4)
        b.putDouble(centerLat).putDouble(centerLon).putDouble(cellKm).putInt(size).putLong(timeMillis).putInt(0)
        values.forEach { b.putFloat(it) }
        return b.array()
    }

    companion object {
        /** Builds a grid by sampling [sample] (lat, lon → value) at each cell centre. */
        fun sample(center: LatLon, cellKm: Double, size: Int, timeMillis: Long, sample: (Double, Double) -> Float): LocalGrid {
            val g = LocalGrid(center.lat, center.lon, cellKm, size, FloatArray(size * size), timeMillis)
            for (r in 0 until size) for (c in 0 until size) {
                val p = g.latLon(c, r)
                g.values[r * size + c] = sample(p.lat, p.lon)
            }
            return g
        }

        fun decode(bytes: ByteArray): LocalGrid? = runCatching {
            val b = java.nio.ByteBuffer.wrap(bytes)
            val lat = b.double; val lon = b.double; val cell = b.double; val n = b.int; val t = b.long; b.int
            LocalGrid(lat, lon, cell, n, FloatArray(n * n) { b.float }, t)
        }.getOrNull()
    }
}

/** Storm motion estimated from two grids, in km per minute east and north. */
data class GridMotion(
    val eastKmPerMin: Double,
    val northKmPerMin: Double,
    val score: Double,
    /** Mismatch after shifting, relative to how far the rain is above the threshold: ~0.3 clean, > 0.8 the field changed shape. */
    val relError: Double = Double.NaN,
)

data class NowcastResult(
    val rainingNow: Boolean,
    /** Minutes until rain is expected at the centre, or null if none within the hour. */
    val startsInMin: Int?,
    val heavy: Boolean,
    val motion: GridMotion?,
) {
    val summary: String get() = when {
        rainingNow -> if (heavy) "Heavy rain now" else "Raining now"
        startsInMin != null -> (if (heavy) "Heavy rain" else "Rain") + " in about $startsInMin min"
        else -> "Dry for the next hour"
    }
}

/**
 * Extrapolation nowcast: estimate how the rain field moved between two
 * frames (block matching), then shift the latest frame forward along that
 * motion and look at the centre. Good to about an hour for moving rain;
 * it cannot foresee new storms forming.
 */
object Nowcast {

    /**
     * @param threshold value counted as rain (0.2 mm/h, or about 18 dBZ)
     * @param maxShift largest displacement searched, in cells
     */
    fun motion(prev: LocalGrid, cur: LocalGrid, threshold: Float, maxShift: Int = 16): GridMotion? {
        require(prev.size == cur.size)
        val minutes = (cur.timeMillis - prev.timeMillis) / 60_000.0
        if (minutes < 3 || minutes > 45) return null
        val n = cur.size
        val m = maxShift
        var rainCells = 0
        for (v in cur.values) if (v >= threshold) rainCells++
        if (rainCells < 12) return null
        var best = Double.MAX_VALUE; var bestDx = 0; var bestDy = 0
        // Compare cur(c, r) with prev(c - dx, r - dy): rain moved by (dx, dy) cells.
        for (dy in -m..m) for (dx in -m..m) {
            var sum = 0.0; var count = 0
            // Cells whose source falls outside the previous grid read NaN and are skipped.
            var r = 0
            while (r < n) {
                var c = 0
                while (c < n) {
                    val a = cur[c, r]; val b = prev[c - dx, r - dy]
                    if (!a.isNaN() && !b.isNaN() && (a >= threshold || b >= threshold)) {
                        sum += abs(a.coerceAtLeast(0f) - b.coerceAtLeast(0f)); count++
                    }
                    c += 1
                }
                r += 1
            }
            if (count < 8) continue
            // Small penalty for large shifts keeps ties near zero motion.
            val score = sum / count + 0.002 * (dx * dx + dy * dy)
            if (score < best) { best = score; bestDx = dx; bestDy = dy }
        }
        if (best == Double.MAX_VALUE) return null
        // How well the shifted field matches: mean mismatch over mean rain value at the best shift.
        var diff = 0.0; var mag = 0.0
        for (r in 0 until n) for (c in 0 until n) {
            val a = cur[c, r]; val b = prev[c - bestDx, r - bestDy]
            if (!a.isNaN() && !b.isNaN() && (a >= threshold || b >= threshold)) {
                diff += abs(a.coerceAtLeast(0f) - b.coerceAtLeast(0f)); mag += (max(a, b) - threshold).coerceAtLeast(0f).toDouble()
            }
        }
        val rel = if (mag > 0) diff / mag else Double.NaN
        // Columns increase eastward, rows southward.
        return GridMotion(bestDx * cur.cellKm / minutes, -bestDy * cur.cellKm / minutes, best, rel)
    }

    /** Looks ahead 5..60 minutes at the grid centre. */
    fun forecast(cur: LocalGrid, motion: GridMotion?, threshold: Float, heavyThreshold: Float): NowcastResult {
        val c = cur.half; val r = cur.half
        val here = neighbourhoodMax(cur, c.toDouble(), r.toDouble())
        if (here >= threshold) return NowcastResult(true, null, here >= heavyThreshold, motion)
        if (motion == null) return NowcastResult(false, null, false, null)
        for (t in 5..60 step 5) {
            // What arrives at the centre after t minutes is now upstream by motion * t.
            val col = c - motion.eastKmPerMin * t / cur.cellKm
            val row = r + motion.northKmPerMin * t / cur.cellKm
            val v = neighbourhoodMax(cur, col, row)
            if (v >= threshold) return NowcastResult(false, t, v >= heavyThreshold, motion)
        }
        return NowcastResult(false, null, false, motion)
    }

    /**
     * Rain expected at the grid centre every 5 minutes from now to +60 min
     * (13 values, in the grid's units). Without a motion estimate only "now"
     * is known; the later steps are NaN.
     */
    fun series(cur: LocalGrid, motion: GridMotion?): FloatArray {
        val c = cur.half.toDouble(); val r = cur.half.toDouble()
        return FloatArray(13) { i ->
            val t = i * 5
            val v = when {
                t == 0 -> neighbourhoodMax(cur, c, r)
                motion == null -> Float.NaN
                else -> neighbourhoodMax(cur, c - motion.eastKmPerMin * t / cur.cellKm, r + motion.northKmPerMin * t / cur.cellKm)
            }
            if (v == Float.NEGATIVE_INFINITY) Float.NaN else v
        }
    }

    /** Max over a 3×3 neighbourhood: allows for small errors in the motion. */
    private fun neighbourhoodMax(g: LocalGrid, col: Double, row: Double): Float {
        val c0 = col.roundToInt(); val r0 = row.roundToInt()
        var m = Float.NEGATIVE_INFINITY
        for (dr in -1..1) for (dc in -1..1) {
            val v = g[c0 + dc, r0 + dr]
            if (!v.isNaN()) m = max(m, v)
        }
        return m
    }
}
