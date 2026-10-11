package app.hookline.radar.core.data

import kotlin.math.ln
import kotlin.math.max

/** How much to trust a rain forecast. NONE: no usable forecast at all. */
enum class Confidence { HIGH, MEDIUM, LOW, NONE }

/** Why a forecast is less certain than usual; shown to the user in plain words. */
enum class ForecastIssue {
    /** Only one recent radar picture (or the rain didn't move recognisably): its track is unknown. */
    NO_MOTION,
    /** The latest radar picture is more than 20 minutes old. */
    OLD_DATA,
    /** The rain area grew or shrank a lot between the two pictures. */
    CHANGING_FAST,
    /** Showers and thunderstorms: new cells form within minutes. */
    SHOWERY,
    /** The two pictures don't match well after shifting: the rain isn't moving as one area. */
    POOR_MATCH,
    /** Arrival more than ~35 minutes away: timing errors grow with distance. */
    FAR_AHEAD,
    /** Part of the area around the place has no radar data. */
    EDGE_OF_COVERAGE,
    /** No radar data at the place itself or for most of the area: no forecast. */
    NO_DATA,
}

/** Rain intensity in words, the same scale for dBZ and mm/h sources. */
enum class RainIntensity { NONE, LIGHT, MODERATE, HEAVY, VERY_HEAVY;

    companion object {
        fun of(kind: RainSource.Kind, v: Float): RainIntensity {
            if (v.isNaN()) return NONE
            val (thr, _) = RainSource.thresholdsFor(kind)
            if (v < thr) return NONE
            return when (kind) {
                // Reflectivity: 18–30 light, 30–40 moderate, 40–50 heavy, 50+ very heavy (hail possible from ~55).
                RainSource.Kind.MRMS_DBZ -> when { v < 30f -> LIGHT; v < 40f -> MODERATE; v < 50f -> HEAVY; else -> VERY_HEAVY }
                // Rain rate (mm/h), WMO-style classes.
                RainSource.Kind.OPERA_RATE -> when { v < 2.5f -> LIGHT; v < 7.5f -> MODERATE; v < 50f -> HEAVY; else -> VERY_HEAVY }
            }
        }

        /** 0 at the rain threshold, 1 at very heavy rain; logarithmic in rain rate. */
        fun level(kind: RainSource.Kind, v: Float): Float = when (kind) {
            RainSource.Kind.MRMS_DBZ -> (v - 18f) / (55f - 18f)
            RainSource.Kind.OPERA_RATE -> (ln(v / 0.2f) / ln(60f / 0.2f))
        }.coerceIn(0f, 1f)
    }
}

/**
 * The rain outlook for one place: the 5-minute series, when rain starts or
 * stops, and how far to trust it. Minutes are counted from the radar
 * picture's time ([observedMillis]); use [minutesFromNow] to shift them.
 */
data class RainForecast(
    val kind: RainSource.Kind,
    val observedMillis: Long,
    /** Rain at the place every 5 minutes from the radar time, +0..+60 min; NaN = unknown. */
    val series: FloatArray,
    val result: NowcastResult,
    /** Minutes after the radar time when rain is expected to stop (raining now), if within the hour. */
    val endsInMin: Int?,
    val confidence: Confidence,
    val issues: Set<ForecastIssue>,
    /** Earliest..latest arrival, minutes after the radar time; null if no rain is expected. */
    val arrivalWindow: IntRange?,
    /** Any rain within the grid (about 120 km). */
    val rainNearby: Boolean,
    /** Strongest rain expected in the next hour. */
    val peak: RainIntensity,
) {
    fun minutesFromNow(minutesAfterRadar: Int, nowMillis: Long): Int =
        (minutesAfterRadar - ((nowMillis - observedMillis) / 60_000L).toInt()).coerceAtLeast(0)

    fun ageMinutes(nowMillis: Long): Int = ((nowMillis - observedMillis) / 60_000L).toInt().coerceAtLeast(0)

    override fun equals(other: Any?) = other is RainForecast && other.observedMillis == observedMillis &&
        other.kind == kind && other.series.contentEquals(series) && other.result == result
    override fun hashCode() = observedMillis.hashCode() * 31 + series.contentHashCode()
}

object RainForecaster {
    const val OLD_DATA_MIN = 20
    const val UNUSABLE_DATA_MIN = 45

    /**
     * Extrapolation nowcast plus an honest confidence: block-matching motion
     * between [prev] and [cur], then checks for the things extrapolation
     * can't handle (growth and decay, showers, old data, missing data).
     */
    fun analyse(kind: RainSource.Kind, cur: LocalGrid, prev: LocalGrid?, nowMillis: Long): RainForecast {
        val (thr, heavy) = RainSource.thresholdsFor(kind)
        val motion = prev?.let { Nowcast.motion(it, cur, thr) }
        val result = Nowcast.forecast(cur, motion, thr, heavy)
        val series = Nowcast.series(cur, motion)

        var rainCells = 0; var heavyCells = 0; var missing = 0
        for (v in cur.values) {
            if (v.isNaN()) { missing++; continue }
            if (v >= thr) rainCells++
            if (v >= heavy) heavyCells++
        }
        val prevRain = prev?.values?.count { !it.isNaN() && it >= thr } ?: 0
        val rainNearby = rainCells >= 3
        val ageMin = ((nowMillis - cur.timeMillis) / 60_000L).toInt()

        val issues = mutableSetOf<ForecastIssue>()
        if (ageMin > OLD_DATA_MIN) issues += ForecastIssue.OLD_DATA
        if (missing > cur.values.size / 4) issues += ForecastIssue.EDGE_OF_COVERAGE
        if (rainNearby) {
            if (motion == null) issues += ForecastIssue.NO_MOTION
            if (prev != null && rainCells >= 12 && prevRain >= 12) {
                val ratio = rainCells.toDouble() / prevRain
                if (ratio > 1.6 || ratio < 0.6) issues += ForecastIssue.CHANGING_FAST
            }
            if (rainCells >= 12 && heavyCells.toDouble() / rainCells > 0.15) issues += ForecastIssue.SHOWERY
            if (motion != null && !motion.relError.isNaN() && motion.relError > 0.8) issues += ForecastIssue.POOR_MATCH
        }
        result.startsInMin?.let { if (it > 35) issues += ForecastIssue.FAR_AHEAD }

        // Rain stopping: the first dry step after now (needs a motion estimate).
        val endsIn = if (result.rainingNow && motion != null) {
            (1 until series.size).firstOrNull { !series[it].isNaN() && series[it] < thr }?.let { it * 5 }
        } else null

        // Nothing measured at the place itself, or most of the area missing (an outage): don't guess.
        if (series[0].isNaN() || missing > cur.values.size * 6 / 10) issues += ForecastIssue.NO_DATA

        val confidence = when {
            ageMin > UNUSABLE_DATA_MIN || ForecastIssue.NO_DATA in issues -> Confidence.NONE
            else -> {
                var score = 3
                for (i in issues) score -= when (i) {
                    ForecastIssue.NO_MOTION -> 2
                    else -> 1
                }
                when { score >= 3 -> Confidence.HIGH; score == 2 -> Confidence.MEDIUM; else -> Confidence.LOW }
            }
        }

        val window = result.startsInMin?.let { t ->
            val spread = when { t <= 15 -> 5; t <= 35 -> 10; else -> 15 } + if (confidence == Confidence.LOW) 5 else 0
            max(0, t - spread)..(t + spread)
        }

        var peak = RainIntensity.NONE
        for (v in series) { val i = RainIntensity.of(kind, v); if (i > peak) peak = i }

        return RainForecast(kind, cur.timeMillis, series, result, endsIn, confidence, issues, window, rainNearby, peak)
    }
}
