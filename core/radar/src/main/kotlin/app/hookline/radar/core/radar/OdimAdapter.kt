package app.hookline.radar.core.radar

import app.hookline.radar.core.odim.Odim

/**
 * Converts ODIM polar scans (European radars) into the same [Sweep] model as
 * NEXRAD, so loops, storm-relative velocity, the inspector and the GPU
 * renderer work unchanged.
 */
object OdimAdapter {

    /** ODIM quantity names in order of preference for each moment. */
    private val QUANTITIES: Map<Moment, List<String>> = mapOf(
        Moment.REFLECTIVITY to listOf("DBZH", "DBZ", "TH", "T"),
        Moment.VELOCITY to listOf("VRADH", "VRAD", "VRADDH", "VRADV"),
        Moment.SPECTRUM_WIDTH to listOf("WRADH", "WRAD"),
        Moment.DIFFERENTIAL_REFLECTIVITY to listOf("ZDR"),
        Moment.CORRELATION_COEFFICIENT to listOf("RHOHV"),
        Moment.DIFFERENTIAL_PHASE to listOf("PHIDP"),
    )

    fun momentFor(quantity: String): Moment? =
        QUANTITIES.entries.firstOrNull { quantity.uppercase() in it.value }?.key

    /**
     * @param elevationNumber a stable index for the sweep (ODIM has none; use
     *   the order of elevations within the volume)
     */
    fun toSweep(scan: Odim.Scan, radarId: String, elevationNumber: Int, site: SiteLocation?): Sweep? {
        // Pick the best available field per moment.
        val chosen = LinkedHashMap<Moment, Odim.Field>()
        for ((moment, names) in QUANTITIES) {
            val field = names.firstNotNullOfOrNull { scan.fields[it] } ?: continue
            chosen[moment] = field
        }
        if (chosen.isEmpty()) return null
        val spacing = (360.0 / scan.nrays).toFloat()
        val firstCenter = scan.firstBinCenterM().toInt()
        val binSpacing = scan.binSpacingM.toInt().coerceAtLeast(1)
        val duration = (scan.endMillis - scan.startMillis).coerceAtLeast(0)
        val row = FloatArray(chosen.values.maxOf { it.cols })
        val perRay = Array(scan.nrays) { LinkedHashMap<Moment, MomentData>() }
        for ((moment, field) in chosen) {
            val rays = minOf(field.rows, scan.nrays)
            for (i in 0 until rays) {
                field.row(i, row)
                perRay[i][moment] = MomentData(moment, firstCenter, binSpacing, row.copyOf(field.cols))
            }
        }
        val radials = (0 until scan.nrays).map { i ->
            Radial(
                radarId = radarId,
                timeMillis = scan.startMillis + duration * i / scan.nrays.coerceAtLeast(1),
                azimuthNumber = i + 1,
                azimuthDeg = scan.azimuths[i],
                azimuthSpacingDeg = spacing,
                elevationNumber = elevationNumber,
                elevationDeg = scan.elevationDeg.toFloat(),
                radialStatus = when (i) {
                    0 -> RadialStatus.START_OF_ELEVATION
                    scan.nrays - 1 -> RadialStatus.END_OF_ELEVATION
                    else -> RadialStatus.INTERMEDIATE
                },
                moments = perRay[i],
                site = site,
            )
        }
        return Sweep(elevationNumber, scan.elevationDeg.toFloat(), radials)
    }

    /** Elevation rounded to 0.1°, used as the tilt key for European radars. */
    fun tiltKey(elevationDeg: Double): Float = Math.round(elevationDeg * 10.0) / 10.0f
}
