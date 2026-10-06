package app.hookline.radar.core.radar

/**
 * One moment of one sweep stored as 16-bit integers for the loop buffer:
 * about 2.6 MB for a super-res reflectivity sweep instead of ~5.3 MB of
 * floats (and ~20 MB for the full multi-moment radials).
 *
 * Raw 0 = below threshold, 1 = range folded, otherwise
 * value = (raw - OFFSET) / SCALE, which covers ±327 at 0.01 resolution —
 * enough for every moment (dBZ, m/s, dB, CC).
 */
class CompactMoment private constructor(
    val moment: Moment,
    val elevationDeg: Float,
    val startMillis: Long,
    val endMillis: Long,
    val azimuths: FloatArray,
    val spacings: FloatArray,
    val firstGateMeters: Int,
    val gateSpacingMeters: Int,
    val gateCount: Int,
    private val raw: ShortArray,
) {
    val sizeBytes: Int get() = raw.size * 2

    fun value(radial: Int, gate: Int): Float = decode(raw[radial * gateCount + gate].toInt() and 0xFFFF)

    /**
     * Expand to a GPU-ready texture. [adjust] can rewrite each value by
     * azimuth, e.g. storm-relative velocity.
     */
    fun toTexture(adjust: ((azimuthDeg: Float, value: Float) -> Float)? = null): SweepTexture {
        val values = FloatArray(raw.size)
        for (i in azimuths.indices) {
            val az = azimuths[i]
            for (g in 0 until gateCount) {
                val k = i * gateCount + g
                val v = decode(raw[k].toInt() and 0xFFFF)
                values[k] = SweepTexture.encode(if (adjust != null && v.isFinite()) adjust(az, v) else v)
            }
        }
        return SweepTexture.of(
            moment, elevationDeg, azimuths.size, gateCount, firstGateMeters, gateSpacingMeters,
            azimuths, spacings, values, startMillis, endMillis,
        )
    }

    companion object {
        private const val SCALE = 100f
        private const val OFFSET = 32768f

        private fun decode(r: Int): Float = when (r) {
            0 -> Gate.BELOW_THRESHOLD
            1 -> Gate.RANGE_FOLDED
            else -> (r - OFFSET) / SCALE
        }

        private fun encode(v: Float): Short = when {
            v == Gate.RANGE_FOLDED -> 1
            !v.isFinite() -> 0
            else -> Math.round(v * SCALE + OFFSET).coerceIn(2, 65535).toShort()
        }

        fun from(sweep: Sweep, moment: Moment): CompactMoment? {
            val rows = sweep.radials.mapNotNull { r -> r.moments[moment]?.let { r to it } }.sortedBy { it.first.azimuthDeg }
            if (rows.isEmpty()) return null
            val gates = rows.maxOf { it.second.gateCount }
            val raw = ShortArray(rows.size * gates) // 0 = below threshold padding
            rows.forEachIndexed { i, (_, md) -> for (g in 0 until md.gateCount) raw[i * gates + g] = encode(md.gates[g]) }
            val ref = rows.first().second
            return CompactMoment(
                moment = moment,
                elevationDeg = sweep.elevationDeg,
                startMillis = rows.minOf { it.first.timeMillis },
                endMillis = rows.maxOf { it.first.timeMillis },
                azimuths = FloatArray(rows.size) { rows[it].first.azimuthDeg },
                spacings = FloatArray(rows.size) { rows[it].first.azimuthSpacingDeg },
                firstGateMeters = ref.firstGateMeters,
                gateSpacingMeters = ref.gateSpacingMeters,
                gateCount = gates,
                raw = raw,
            )
        }
    }
}
