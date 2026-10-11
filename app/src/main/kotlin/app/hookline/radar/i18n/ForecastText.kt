package app.hookline.radar.i18n

import app.hookline.radar.core.data.Confidence
import app.hookline.radar.core.data.ForecastIssue
import app.hookline.radar.core.data.RainForecast
import app.hookline.radar.core.data.RainIntensity

/**
 * Words for the rain forecast. Arrival is always a range and always hedged
 * ("likely", "possible"): extrapolation can't promise a minute, and can't see
 * new showers forming.
 */
object ForecastText {

    /** One line: "Rain likely in 15–25 min", "Raining now", "Dry for the next hour". */
    fun headline(f: RainForecast, now: Long): String {
        if (f.confidence == Confidence.NONE) return tr("No current rain forecast")
        val r = f.result
        val heavy = f.peak >= RainIntensity.HEAVY
        return when {
            r.rainingNow -> if (r.heavy) tr("Heavy rain now") else tr("Raining now")
            f.arrivalWindow != null -> {
                val lo = f.minutesFromNow(f.arrivalWindow.first, now)
                val hi = f.minutesFromNow(f.arrivalWindow.last, now).coerceAtLeast(lo + 5)
                val likely = f.confidence != Confidence.LOW
                when {
                    lo <= 0 && likely -> if (heavy) tr("Heavy rain likely within %s min", hi) else tr("Rain likely within %s min", hi)
                    lo <= 0 -> if (heavy) tr("Heavy rain possible within %s min", hi) else tr("Rain possible within %s min", hi)
                    likely -> if (heavy) tr("Heavy rain likely in %s–%s min", lo, hi) else tr("Rain likely in %s–%s min", lo, hi)
                    else -> if (heavy) tr("Heavy rain possible in %s–%s min", lo, hi) else tr("Rain possible in %s–%s min", lo, hi)
                }
            }
            else -> tr("Dry for the next hour")
        }
    }

    /** Second line: what else to know, in a few words. */
    fun detail(f: RainForecast, now: Long): String {
        if (f.confidence == Confidence.NONE) return tr("The latest radar picture is too old to forecast from")
        val r = f.result
        return when {
            r.rainingNow && f.endsInMin != null -> tr("Likely easing in about %s min", f.minutesFromNow(f.endsInMin, now).coerceAtLeast(5))
            r.rainingNow && ForecastIssue.NO_MOTION in f.issues -> tr("Not enough radar history yet to tell when it stops")
            r.rainingNow -> tr("Likely to continue for the next hour")
            f.arrivalWindow != null -> intensity(f.peak)
            !f.rainNearby -> tr("No rain on radar within about 120 km")
            ForecastIssue.SHOWERY in f.issues -> tr("Showers nearby could still form overhead")
            else -> tr("Rain nearby isn't heading your way")
        }
    }

    fun confidence(c: Confidence): String = when (c) {
        Confidence.HIGH -> tr("High confidence")
        Confidence.MEDIUM -> tr("Medium confidence")
        Confidence.LOW -> tr("Low confidence")
        Confidence.NONE -> tr("No forecast")
    }

    fun intensity(i: RainIntensity): String = when (i) {
        RainIntensity.NONE -> tr("No rain")
        RainIntensity.LIGHT -> tr("Light rain")
        RainIntensity.MODERATE -> tr("Moderate rain")
        RainIntensity.HEAVY -> tr("Heavy rain")
        RainIntensity.VERY_HEAVY -> tr("Very heavy rain, hail possible")
    }

    /** Why the forecast is less certain, one sentence per reason. */
    fun issue(i: ForecastIssue, f: RainForecast, now: Long): String = when (i) {
        ForecastIssue.NO_MOTION -> tr("Only one recent radar picture, so the rain's movement isn't known yet.")
        ForecastIssue.OLD_DATA -> tr("The latest radar picture is %s min old.", f.ageMinutes(now))
        ForecastIssue.CHANGING_FAST -> tr("The rain is growing or fading quickly, which a forecast from movement can't capture.")
        ForecastIssue.SHOWERY -> tr("Showers and storms: new cells can form within minutes, with no warning on radar.")
        ForecastIssue.POOR_MATCH -> tr("The rain isn't moving as one area, so its track is uncertain.")
        ForecastIssue.FAR_AHEAD -> tr("It's more than half an hour away; timing gets less certain with distance.")
        ForecastIssue.EDGE_OF_COVERAGE -> tr("Part of the area around you isn't covered by radar.")
    }

    /** For the place lists and the radar widget: headline only. */
    fun summary(f: RainForecast, now: Long): String = headline(f, now)
}
