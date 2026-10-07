package app.hookline.radar.i18n

import app.hookline.radar.core.data.HailRisk
import app.hookline.radar.core.data.MrmsTracks
import app.hookline.radar.core.data.NowcastResult
import java.util.Locale

/** Display names for texts that come from the core modules (which stay English). */
fun trackLabel(p: MrmsTracks.Product): String = when (p) {
    MrmsTracks.Product.HAIL_24H -> tr("Hail swaths, last 24 h")
    MrmsTracks.Product.ROTATION_60M -> tr("Rotation tracks, last hour")
}

fun hailLabel(h: HailRisk): String = when (h) {
    HailRisk.NONE -> ""
    HailRisk.SMALL -> tr("Small hail possible")
    HailRisk.LIKELY -> tr("Hail likely")
    HailRisk.LARGE -> tr("Large hail possible")
}

fun nowcastSummary(r: NowcastResult): String {
    val minutes = r.startsInMin
    return when {
        r.rainingNow -> if (r.heavy) tr("Heavy rain now") else tr("Raining now")
        minutes != null -> if (r.heavy) tr("Heavy rain in about %s min", minutes) else tr("Rain in about %s min", minutes)
        else -> tr("Dry for the next hour")
    }
}

/** Compass letters ("063° ENE", "SW") in the app language: N/E/S/W only, digits untouched. */
fun compass(s: String): String {
    val map: Map<Char, Char> = when (Locale.getDefault().language) {
        "el" -> mapOf('N' to 'Β', 'E' to 'Α', 'S' to 'Ν', 'W' to 'Δ')
        "de" -> mapOf('E' to 'O')
        else -> return s
    }
    return buildString { s.forEach { append(map[it] ?: it) } }
}

/** Lower-cases a short label for use inside a sentence; German keeps its capitalised nouns. */
fun midSentence(s: String): String = if (Locale.getDefault().language == "de") s else s.lowercase()
