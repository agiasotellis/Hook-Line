package app.hookline.radar.core.radar

/**
 * A colour table mapping physical values to ARGB colours.
 *
 * Supports the GRLevelX / RadarScope `.pal` text format that pros already own:
 *
 * ```
 * Product: BR
 * Units: dBZ
 * Step: 5
 * Color: 5 4 233 231
 * Color: 10 1 159 244 3 0 244   ; optional second RGB = gradient end
 * SolidColor: 65 248 0 253
 * Color4: 70 152 84 198 255
 * RF: 119 0 125
 * ```
 */
class Palette(
    val units: String,
    stops: List<Stop>,
    val rangeFoldedArgb: Int = argb(255, 119, 0, 125),
) {
    data class Stop(val value: Float, val argb: Int, val endArgb: Int? = null, val solid: Boolean = false)

    val stops: List<Stop> = stops.sortedBy { it.value }

    init {
        require(this.stops.isNotEmpty()) { "Palette needs at least one colour stop" }
    }

    /** Colour for a value; transparent below the first stop or for below-threshold gates. */
    fun colorFor(v: Float): Int {
        if (v == Gate.RANGE_FOLDED) return rangeFoldedArgb
        if (!v.isFinite() || v < stops.first().value) return 0
        val idx = stops.indexOfLast { it.value <= v }
        val s = stops[idx]
        val next = stops.getOrNull(idx + 1) ?: return s.argb
        if (s.solid) return s.argb
        val to = s.endArgb ?: next.argb
        val t = ((v - s.value) / (next.value - s.value)).coerceIn(0f, 1f)
        return lerp(s.argb, to, t)
    }

    /** Pre-bake a lookup table for a GPU 1-D texture. */
    fun toLut(min: Float, max: Float, size: Int = 256): IntArray =
        IntArray(size) { i -> colorFor(min + (max - min) * i / (size - 1)) }

    companion object {
        fun parse(text: String): Palette {
            var units = ""
            var rf: Int? = null
            val stops = mutableListOf<Stop>()
            text.lineSequence()
                .map { it.substringBefore(';').trim() }
                .filter { it.isNotEmpty() && ':' in it }
                .forEach { line ->
                    val key = line.substringBefore(':').trim().lowercase()
                    val rest = line.substringAfter(':').trim()
                    val n = rest.split(Regex("\\s+")).mapNotNull { it.toFloatOrNull() }
                    when (key) {
                        "units" -> units = rest
                        "rf" -> if (n.size >= 3) rf = argb(255, n[0].toInt(), n[1].toInt(), n[2].toInt())
                        "color" -> if (n.size >= 4) stops += Stop(
                            n[0], rgb(n, 1),
                            endArgb = if (n.size >= 7) rgb(n, 4) else null,
                        )
                        "color4" -> if (n.size >= 5) stops += Stop(
                            n[0], argb(n[4].toInt(), n[1].toInt(), n[2].toInt(), n[3].toInt()),
                            endArgb = if (n.size >= 9) argb(n[8].toInt(), n[5].toInt(), n[6].toInt(), n[7].toInt()) else null,
                        )
                        "solidcolor" -> if (n.size >= 4) stops += Stop(n[0], rgb(n, 1), solid = true)
                    }
                }
            return Palette(units, stops, rf ?: argb(255, 119, 0, 125))
        }

        /** Standard NWS reflectivity colours, smoothly interpolated. */
        val NWS_REFLECTIVITY: Palette = parse(
            """
            Units: dBZ
            Color: 5 4 233 231
            Color: 10 1 159 244
            Color: 15 3 0 244
            Color: 20 2 253 2
            Color: 25 1 197 1
            Color: 30 0 142 0
            Color: 35 253 248 2
            Color: 40 229 188 0
            Color: 45 253 149 0
            Color: 50 253 0 0
            Color: 55 212 0 0
            Color: 60 188 0 0
            Color: 65 248 0 253
            Color: 70 152 84 198
            Color: 75 253 253 253
            """.trimIndent()
        )

        /** Colour-vision-friendly velocity: blue inbound, orange outbound (m/s). */
        val VELOCITY_CVD: Palette = parse(
            """
            Units: m/s
            Color: -40 170 220 255
            Color: -30 40 120 255
            Color: -15 20 60 150
            Color: -2 70 80 95
            Color: 2 95 80 70
            Color: 15 160 70 10
            Color: 30 255 140 20
            Color: 40 255 225 170
            """.trimIndent()
        )

        /** Correlation coefficient: low values (debris, hail, biologicals) pop out in warm colours. */
        val CORRELATION: Palette = parse(
            """
            Units:
            Color: 0.20 120 120 120
            Color: 0.45 20 30 160
            Color: 0.65 40 160 220
            Color: 0.80 70 200 90
            Color: 0.90 240 220 40
            Color: 0.95 240 140 30
            Color: 0.98 220 40 40
            Color: 1.00 150 20 90
            Color: 1.05 230 200 230
            """.trimIndent()
        )

        /** Differential reflectivity (dB). */
        val ZDR: Palette = parse(
            """
            Units: dB
            Color: -4 60 60 60
            Color: -1 140 140 160
            Color: 0 200 200 200
            Color: 0.5 60 80 200
            Color: 1.5 40 180 220
            Color: 2.5 60 200 80
            Color: 3.5 240 230 40
            Color: 5 240 120 30
            Color: 8 200 20 40
            """.trimIndent()
        )

        fun argb(a: Int, r: Int, g: Int, b: Int): Int =
            (a.coerceIn(0, 255) shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

        private fun rgb(n: List<Float>, at: Int) = argb(255, n[at].toInt(), n[at + 1].toInt(), n[at + 2].toInt())

        private fun lerp(a: Int, b: Int, t: Float): Int {
            fun ch(c: Int, s: Int) = (c ushr s) and 0xFF
            fun mix(s: Int) = (ch(a, s) + (ch(b, s) - ch(a, s)) * t).toInt()
            return argb(mix(24), mix(16), mix(8), mix(0))
        }
    }
}
