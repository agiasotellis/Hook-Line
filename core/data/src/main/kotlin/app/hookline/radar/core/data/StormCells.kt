package app.hookline.radar.core.data

import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

enum class HailRisk(val label: String) {
    NONE(""),
    SMALL("Small hail possible"),
    LIKELY("Hail likely"),
    LARGE("Large hail possible"),
}

/**
 * One storm cell: a connected area of strong reflectivity.
 * Motion is null until the cell has been seen in two scans.
 */
data class StormCell(
    val id: Int,
    val lat: Double,
    val lon: Double,
    val maxDbz: Float,
    val areaKm2: Double,
    val eastKmPerMin: Double? = null,
    val northKmPerMin: Double? = null,
    /** Earlier centroids, oldest first. */
    val track: List<LatLon> = emptyList(),
) {
    val hail: HailRisk get() = when {
        maxDbz >= 62 -> HailRisk.LARGE
        maxDbz >= 57 -> HailRisk.LIKELY
        maxDbz >= 52 -> HailRisk.SMALL
        else -> HailRisk.NONE
    }

    val speedMs: Double? get() = if (eastKmPerMin == null || northKmPerMin == null) null else hypot(eastKmPerMin, northKmPerMin) * 1000 / 60
    /** Direction it's moving toward, degrees from north. */
    val headingDeg: Double? get() = if (eastKmPerMin == null || northKmPerMin == null) null
        else (Math.toDegrees(atan2(eastKmPerMin, northKmPerMin)) + 360) % 360

    /** Position after [minutes], straight-line. */
    fun ahead(minutes: Double): LatLon? {
        val e = eastKmPerMin ?: return null; val n = northKmPerMin ?: return null
        return LatLon(lat + n * minutes / 111.32, lon + e * minutes / (111.32 * kotlin.math.cos(Math.toRadians(lat))))
    }
}

/**
 * Finds storm cells in a reflectivity grid and follows them from scan to
 * scan (a simple SCIT-like approach: threshold, connected areas, nearest
 * match within a plausible travel distance).
 */
object StormCells {

    /** Cells of at least [minCells] grid cells at or above [threshold] dBZ, strongest first. */
    fun detect(g: LocalGrid, threshold: Float = 40f, minCells: Int = 4, max: Int = 40): List<StormCell> {
        val n = g.size
        val label = IntArray(n * n)
        val cells = mutableListOf<StormCell>()
        val stack = IntArray(n * n)
        var next = 1
        for (start in 0 until n * n) {
            val v0 = g.values[start]
            if (label[start] != 0 || v0.isNaN() || v0 < threshold) continue
            var sp = 0; stack[sp++] = start; label[start] = next
            var count = 0; var wSum = 0.0; var cx = 0.0; var cy = 0.0; var maxV = Float.NEGATIVE_INFINITY
            while (sp > 0) {
                val i = stack[--sp]
                val c = i % n; val r = i / n
                val v = g.values[i]
                val w = (v - threshold + 1).toDouble()
                count++; wSum += w; cx += c * w; cy += r * w
                if (v > maxV) maxV = v
                if (c > 0) push(g, label, stack, i - 1, threshold, next)?.let { stack[sp++] = it }
                if (c < n - 1) push(g, label, stack, i + 1, threshold, next)?.let { stack[sp++] = it }
                if (r > 0) push(g, label, stack, i - n, threshold, next)?.let { stack[sp++] = it }
                if (r < n - 1) push(g, label, stack, i + n, threshold, next)?.let { stack[sp++] = it }
            }
            next++
            if (count < minCells) continue
            val center = g.latLon(0, 0).let { _ ->
                val col = cx / wSum; val row = cy / wSum
                val northKm = (g.half - row) * g.cellKm; val eastKm = (col - g.half) * g.cellKm
                LatLon(g.centerLat + northKm / 111.32, g.centerLon + eastKm / (111.32 * kotlin.math.cos(Math.toRadians(g.centerLat))))
            }
            cells += StormCell(0, center.lat, center.lon, maxV, count * g.cellKm * g.cellKm)
        }
        return cells.sortedByDescending { it.maxDbz }.take(max)
    }

    private fun push(g: LocalGrid, label: IntArray, stack: IntArray, j: Int, threshold: Float, id: Int): Int? {
        if (label[j] != 0) return null
        val v = g.values[j]
        if (v.isNaN() || v < threshold) return null
        label[j] = id
        return j
    }

    /**
     * Matches [current] cells to [previous] ones seen [minutes] earlier and
     * gives each a stable id, a motion and a track. Unmatched cells get new ids.
     */
    fun track(previous: List<StormCell>, current: List<StormCell>, minutes: Double, nextId: () -> Int): List<StormCell> {
        if (minutes <= 0.5 || minutes > 30) return current.map { it.copy(id = nextId()) }
        // Up to 1.5 km/min (90 km/h) plus a few km of centroid wobble.
        val maxKm = minutes * 1.5 + 6
        val used = HashSet<Int>()
        // Strongest cells pick first.
        return current.map { c ->
            val best = previous.filter { it.id !in used }
                .map { p -> p to Sites.distanceKm(LatLon(p.lat, p.lon), LatLon(c.lat, c.lon)) }
                .filter { it.second <= maxKm }
                .minByOrNull { it.second + kotlin.math.abs(it.first.maxDbz - c.maxDbz) * 0.3 }
            if (best == null) {
                c.copy(id = nextId())
            } else {
                val p = best.first
                used += p.id
                val e = (c.lon - p.lon) * 111.32 * kotlin.math.cos(Math.toRadians(c.lat)) / minutes
                val n = (c.lat - p.lat) * 111.32 / minutes
                // Smooth with the previous motion so one jumpy centroid doesn't swing the arrow.
                val se = p.eastKmPerMin?.let { 0.5 * it + 0.5 * e } ?: e
                val sn = p.northKmPerMin?.let { 0.5 * it + 0.5 * n } ?: n
                c.copy(id = p.id, eastKmPerMin = se, northKmPerMin = sn, track = (p.track + LatLon(p.lat, p.lon)).takeLast(8))
            }
        }
    }

    /** Minutes until [cell] passes within [radiusKm] of [p], if it's heading that way within the next 2 hours. */
    fun arrivalMinutes(cell: StormCell, p: LatLon, radiusKm: Double = 8.0): Int? {
        val e = cell.eastKmPerMin ?: return null; val n = cell.northKmPerMin ?: return null
        val speed = hypot(e, n)
        if (speed < 0.08) return null
        val dx = (p.lon - cell.lon) * 111.32 * kotlin.math.cos(Math.toRadians(p.lat))
        val dy = (p.lat - cell.lat) * 111.32
        val t = (dx * e + dy * n) / (speed * speed) // minutes to closest approach
        if (t < 0 || t > 120) return null
        val miss = sqrt((dx - e * t) * (dx - e * t) + (dy - n * t) * (dy - n * t))
        return if (miss <= radiusKm) t.toInt() else null
    }
}
