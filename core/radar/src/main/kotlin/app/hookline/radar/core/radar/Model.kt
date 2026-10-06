package app.hookline.radar.core.radar

/** NEXRAD base and dual-pol moments as named in Message 31 data blocks. */
enum class Moment(val code: String, val units: String) {
    REFLECTIVITY("REF", "dBZ"),
    VELOCITY("VEL", "m/s"),
    SPECTRUM_WIDTH("SW", "m/s"),
    DIFFERENTIAL_REFLECTIVITY("ZDR", "dB"),
    DIFFERENTIAL_PHASE("PHI", "deg"),
    CORRELATION_COEFFICIENT("RHO", ""),
    CLUTTER_FILTER_POWER("CFP", "dB");

    companion object {
        fun fromCode(code: String): Moment? = entries.firstOrNull { it.code == code.trim() }
    }
}

/** Sentinel values in the decoded gate arrays. */
object Gate {
    /** Raw 0: signal below threshold. Rendered transparent. */
    const val BELOW_THRESHOLD: Float = Float.NEGATIVE_INFINITY
    /** Raw 1: range folded (ambiguous). Rendered purple. */
    const val RANGE_FOLDED: Float = Float.POSITIVE_INFINITY

    fun isData(v: Float): Boolean = v.isFinite()
}

data class MomentData(
    val moment: Moment,
    val firstGateMeters: Int,
    val gateSpacingMeters: Int,
    /** Physical values; see [Gate] for sentinels. */
    val gates: FloatArray,
) {
    val gateCount: Int get() = gates.size
    fun rangeOfGateMeters(i: Int): Int = firstGateMeters + i * gateSpacingMeters

    override fun equals(other: Any?): Boolean =
        other is MomentData && other.moment == moment && other.firstGateMeters == firstGateMeters &&
            other.gateSpacingMeters == gateSpacingMeters && other.gates.contentEquals(gates)

    override fun hashCode(): Int = (moment.hashCode() * 31 + firstGateMeters) * 31 + gates.contentHashCode()
}

data class Radial(
    val radarId: String,
    /** Milliseconds since 1970-01-01 UTC. */
    val timeMillis: Long,
    val azimuthNumber: Int,
    val azimuthDeg: Float,
    /** 0.5 or 1.0 degrees. */
    val azimuthSpacingDeg: Float,
    val elevationNumber: Int,
    val elevationDeg: Float,
    val radialStatus: Int,
    val moments: Map<Moment, MomentData>,
    val site: SiteLocation? = null,
)

data class SiteLocation(val latitude: Float, val longitude: Float, val heightMeters: Int)

/** One elevation cut: all radials sharing an elevation number. */
data class Sweep(
    val elevationNumber: Int,
    val elevationDeg: Float,
    val radials: List<Radial>,
) {
    val startMillis: Long get() = radials.minOf { it.timeMillis }
    val endMillis: Long get() = radials.maxOf { it.timeMillis }
    fun has(moment: Moment): Boolean = radials.any { moment in it.moments }
}

data class Volume(
    val radarId: String,
    val site: SiteLocation?,
    val sweeps: List<Sweep>,
)

/** Radial status values from the ICD. */
object RadialStatus {
    const val START_OF_ELEVATION = 0
    const val INTERMEDIATE = 1
    const val END_OF_ELEVATION = 2
    const val START_OF_VOLUME = 3
    const val END_OF_VOLUME = 4
    const val START_OF_ELEVATION_LAST_CUT = 5
}
