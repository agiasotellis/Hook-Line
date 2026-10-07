package app.hookline.radar.core.data

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Display units. Radar physics stays in SI internally; only text changes. */
enum class UnitSystem(val label: String) {
    METRIC("Metric (km, m, km/h)"),
    IMPERIAL("Imperial (mi, ft, mph)");

    companion object {
        /** Countries that use miles on road signs. */
        private val IMPERIAL_COUNTRIES = setOf("US", "LR", "MM", "GB", "PR", "GU", "VI", "AS", "MP")

        fun forCountry(country: String): UnitSystem = if (country.uppercase() in IMPERIAL_COUNTRIES) IMPERIAL else METRIC
    }
}

object Fmt {
    private val L = Locale.US

    /** Ground distance: one decimal under 10. */
    fun distance(meters: Double, u: UnitSystem): String = when (u) {
        UnitSystem.METRIC -> {
            val km = meters / 1000.0
            if (km < 10) "%.1f km".format(L, km) else "%,d km".format(L, km.roundToLong())
        }
        UnitSystem.IMPERIAL -> {
            val mi = meters / 1609.344
            if (mi < 10) "%.1f mi".format(L, mi) else "%,d mi".format(L, mi.roundToLong())
        }
    }

    /** Heights rounded to 10 m / 100 ft (beam heights aren't more precise than that). */
    fun height(meters: Double, u: UnitSystem): String = when (u) {
        UnitSystem.METRIC -> "%,d m".format(L, (meters / 10).roundToLong() * 10)
        UnitSystem.IMPERIAL -> "%,d ft".format(L, (meters * 3.28084 / 100).roundToLong() * 100)
    }

    fun speed(metersPerSecond: Double, u: UnitSystem): String = when (u) {
        UnitSystem.METRIC -> "${(metersPerSecond * 3.6).roundToInt()} km/h"
        UnitSystem.IMPERIAL -> "${(metersPerSecond * 2.236936).roundToInt()} mph"
    }

    /** "063° ENE". */
    fun bearing(deg: Double): String {
        val d = ((deg % 360) + 360) % 360
        val names = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
        return "%03d° %s".format(L, d.roundToInt() % 360, names[((d + 11.25) / 22.5).toInt() % 16])
    }

    /** "17 min", "1 h 05 min". */
    fun duration(seconds: Double): String {
        val m = (abs(seconds) / 60).roundToInt()
        return if (m < 60) "$m min" else "%d h %02d min".format(L, m / 60, m % 60)
    }
}
