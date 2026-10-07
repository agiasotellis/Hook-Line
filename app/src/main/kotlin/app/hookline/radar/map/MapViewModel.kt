package app.hookline.radar.map

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.hookline.radar.BuildConfig
import app.hookline.radar.core.data.Alert
import app.hookline.radar.core.data.Coverage
import app.hookline.radar.core.data.DwdDualPol
import app.hookline.radar.core.data.EumetsatLayers
import app.hookline.radar.core.data.Fmt
import app.hookline.radar.core.data.UnitSystem
import app.hookline.radar.core.data.Imagery
import app.hookline.radar.core.data.ImagerySource
import app.hookline.radar.core.data.MeteoAlarm
import app.hookline.radar.core.data.WarningItem
import app.hookline.radar.core.data.WarningLevel
import app.hookline.radar.core.data.WarningRegions
import app.hookline.radar.core.data.toWarningItem
import app.hookline.radar.core.data.Hazard
import app.hookline.radar.core.data.LatLon
import app.hookline.radar.core.data.LiveEvent
import app.hookline.radar.core.data.LiveRadar
import app.hookline.radar.core.data.LiveSnapshot
import app.hookline.radar.core.data.LoopBackfill
import app.hookline.radar.core.data.Network
import app.hookline.radar.core.data.NexradSource
import app.hookline.radar.core.data.NwsAlerts
import app.hookline.radar.core.data.OperaComposite
import app.hookline.radar.core.data.OrdKey
import app.hookline.radar.core.data.OrdRadar
import app.hookline.radar.core.data.OrdS3
import app.hookline.radar.core.data.RadarSite
import app.hookline.radar.core.data.Sites
import app.hookline.radar.core.data.StormMotion
import app.hookline.radar.core.odim.CompositeTile
import app.hookline.radar.core.odim.Odim
import app.hookline.radar.core.radar.CompactMoment
import app.hookline.radar.core.radar.Gate
import app.hookline.radar.core.radar.MercatorProjector
import app.hookline.radar.core.radar.Moment
import app.hookline.radar.core.radar.MomentData
import app.hookline.radar.core.radar.Palette
import app.hookline.radar.core.radar.Radial
import app.hookline.radar.core.radar.RadialGeometry
import app.hookline.radar.core.radar.StormRelative
import app.hookline.radar.core.radar.SweepTexture
import app.hookline.radar.render.Outline
import app.hookline.radar.render.RasterLayer
import app.hookline.radar.stormmode.AlertChecker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone
import java.util.TreeMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

enum class Product(val label: String, val moment: Moment, val palette: Palette, val min: Float, val max: Float) {
    REF("REF", Moment.REFLECTIVITY, Palette.NWS_REFLECTIVITY, -10f, 80f),
    VEL("VEL", Moment.VELOCITY, Palette.VELOCITY_CVD, -40f, 40f),
    SRV("SRV", Moment.VELOCITY, Palette.VELOCITY_CVD, -40f, 40f),
    CC("CC", Moment.CORRELATION_COEFFICIENT, Palette.CORRELATION, 0.2f, 1.05f),
    ZDR("ZDR", Moment.DIFFERENTIAL_REFLECTIVITY, Palette.ZDR, -4f, 8f),
}

sealed interface FeedStatus {
    data object Connecting : FeedStatus
    data object Live : FeedStatus
    /** Showing the last good scan; [message] says why it's not updating. */
    data class Stale(val message: String) : FeedStatus
    /** Nothing open to show for this place. */
    data class NoCoverage(val message: String) : FeedStatus
}

/**
 * What the map is showing: one radar's raw data, the European composite,
 * the US mosaic image (zoomed out in North America), or nothing (satellite only).
 */
enum class Mode { SITE, COMPOSITE, MOSAIC, NONE }

/** Storm motion in use for SRV and where it came from. */
data class MotionInfo(val fromDeg: Int, val speedKt: Int, val source: String)

data class LoopUi(
    /** End times of the frames for the current product, oldest first. */
    val frames: List<Long> = emptyList(),
    /** Frame on screen; null = latest (live). */
    val index: Int? = null,
    val playing: Boolean = false,
    val available: Boolean = true,
    val loading: String? = null,
)

/** A one-shot request for the map camera (seq changes each time). */
data class CameraTarget(val lat: Double, val lon: Double, val zoom: Double, val seq: Int)

data class MapUiState(
    val mode: Mode = Mode.NONE,
    val site: RadarSite? = null,
    val autoSite: Boolean = true,
    val nearbySites: List<Pair<RadarSite, Int>> = emptyList(),
    val product: Product = Product.REF,
    val tilts: List<Float> = emptyList(),
    val tilt: Float = 0.5f,
    val availableProducts: Set<Product> = setOf(Product.REF),
    val frameEndMillis: Long? = null,
    val nowMillis: Long = System.currentTimeMillis(),
    val status: FeedStatus = FeedStatus.Connecting,
    val siteLayer: RasterLayer? = null,
    val compositeLayer: RasterLayer? = null,
    val origin: MercatorProjector = MercatorProjector(0.0, 0.0),
    val outlines: List<Outline> = emptyList(),
    val loop: LoopUi = LoopUi(),
    val motion: MotionInfo = DEFAULT_MOTION,
    val inspector: Inspection? = null,
    val homeWarning: WarningItem? = null,
    /** Storm Mode switched on and a home place known. */
    val stormModeOn: Boolean = false,
    val stormModeEnabled: Boolean = true,
    /** Where Storm Mode watches, e.g. "Attiki, GR"; null until the location is known. */
    val homeLabel: String? = null,
    /** Lowest MeteoAlarm level that notifies in Europe. */
    val euMinLevel: WarningLevel = WarningLevel.ORANGE,
    /** Active warnings touching the visible map, most severe first. */
    val warnings: List<WarningItem> = emptyList(),
    val showWarnings: Boolean = true,
    val satellite: Boolean = true,
    /** Raster imagery for the base map (source, opacity). */
    val imagery: List<Pair<ImagerySource, Float>> = emptyList(),
    val units: UnitSystem = UnitSystem.METRIC,
    val redNight: Boolean = false,
    /** Non-null while the measure tool is open. */
    val measure: Measure? = null,
    val intensity: MapIntensity = MapIntensity(),
    val loopLength: Int = 12,
    val camera: CameraTarget? = null,
    val attribution: String = "",
) {
    val frameAgeSeconds: Int? get() = frameEndMillis?.let { ((nowMillis - it) / 1000).toInt().coerceAtLeast(0) }
    val showingLatest: Boolean get() = loop.index == null

    companion object {
        val DEFAULT_MOTION = MotionInfo(240, 30, "Default motion")
    }
}

data class Inspection(val value: String, val meaning: String, val height: String)

/** Measuring between two taps: [a] first, then [b]. */
data class Measure(val a: LatLon? = null, val b: LatLon? = null)

class MapViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("map", Context.MODE_PRIVATE)

    private val http = OkHttpClient.Builder().callTimeout(45, TimeUnit.SECONDS).build()
    private val nexrad = NexradSource(http)
    private val live = LiveRadar(nexrad)
    private val backfill = LoopBackfill(nexrad)
    private val ordStore = OrdS3(http)
    private val ord = OrdRadar(ordStore)
    private val opera = OperaComposite(ordStore)
    private val nws = NwsAlerts(http, BuildConfig.NWS_USER_AGENT)
    private val checker = AlertChecker(app)
    private val meteoAlarm = MeteoAlarm(http)
    private val dwd = DwdDualPol(http)

    val offline = OfflineMaps(app)

    private val startCamera: CameraTarget = initialCamera()

    private val _state = MutableStateFlow(
        MapUiState(
            intensity = MapIntensity(prefs.getFloat("roads", 0.7f), prefs.getFloat("labels", 0.8f), prefs.getFloat("borders", 0.6f)),
            loopLength = prefs.getInt("loop_length", 12),
            autoSite = prefs.getBoolean("auto_site", true),
            // One render origin for the whole session: every layer is placed relative to it.
            origin = MercatorProjector(startCamera.lat, startCamera.lon),
            camera = startCamera,
            stormModeOn = checker.enabled && checker.places().isNotEmpty(),
            stormModeEnabled = checker.enabled,
            homeLabel = checker.homeLabel(),
            euMinLevel = checker.euMinLevel,
            showWarnings = prefs.getBoolean("show_warnings", true),
            satellite = prefs.getBoolean("satellite", true),
            units = prefs.getString("units", null)?.let { runCatching { UnitSystem.valueOf(it) }.getOrNull() }
                ?: UnitSystem.forCountry(Locale.getDefault().country),
            redNight = prefs.getBoolean("red_night", false),
        ),
    )
    val state: StateFlow<MapUiState> = _state.asStateFlow()

    // live single-site
    private var snapshot: LiveSnapshot? = null
    private var siteLocation: LatLon? = null
    private val wantedTilts = MutableStateFlow(emptySet<Float>())
    private var streamJob: Job? = null
    private var backfillJob: Job? = null
    private var currentTexture: SweepTexture? = null
    private val refLoop = TreeMap<Long, CompactMoment>()
    private val velLoop = TreeMap<Long, CompactMoment>()

    // composite
    private var compositeJob: Job? = null
    private var compositeGrid: Odim.Grid? = null
    private var compositeLocator: CompositeTile.Locator? = null
    private var compositeKey: OrdKey? = null
    private var compositeBounds: DoubleArray? = null // s, w, n, e of the current tile
    private var compositeZoom = 0.0
    private val compositeBytes = TreeMap<Long, ByteArray>() // loop frames, compressed
    private var compositeLoopLayers: Map<Long, RasterLayer> = emptyMap()
    private var compositeKeys: List<OrdKey> = emptyList()

    // map
    private var mapCenter: LatLon = LatLon(startCamera.lat, startCamera.lon)
    private var mapZoom = startCamera.zoom
    private var visible: LatLngBounds? = null
    private var alerts: List<Alert> = emptyList()
    private val euFeeds = HashMap<String, Pair<Long, List<WarningItem>>>() // country -> (fetched at, items)
    private var regions: WarningRegions? = null
    private var eumetsat: EumetsatLayers? = null

    // German dual-pol (DWD)
    private val dualPol = HashMap<Moment, DwdDualPol.Result>()
    private var dualPolJob: Job? = null
    private var playJob: Job? = null
    private var frameJob: Job? = null
    private var compositeTileJob: Job? = null
    private var cameraSeq = 1

    init {
        viewModelScope.launch { while (isActive) { _state.update { it.copy(nowMillis = System.currentTimeMillis()) }; delay(1_000) } }
        viewModelScope.launch {
            regions = withContext(Dispatchers.Default) { runCatching { WarningRegions.bundled() }.getOrNull() }
            while (isActive) {
                refreshAlerts()
                delay(60_000)
            }
        }
        viewModelScope.launch { eumetsat = loadEumetsatLayers(); updateImagery() }
        // Imagery URLs carry the time: refresh them every 5 minutes.
        viewModelScope.launch { while (isActive) { delay(300_000); updateImagery() } }
    }

    // ---- camera and location ------------------------------------------------------

    private fun initialCamera(): CameraTarget {
        if (prefs.contains("cam_lat")) {
            return CameraTarget(prefs.getFloat("cam_lat", 0f).toDouble(), prefs.getFloat("cam_lon", 0f).toDouble(), prefs.getFloat("cam_zoom", 5f).toDouble(), 0)
        }
        val tz = TimeZone.getDefault().id
        return when {
            tz.startsWith("Europe/") -> CameraTarget(48.0, 12.0, 3.8, 0)
            tz.startsWith("America/") || tz.startsWith("US/") || tz.startsWith("Pacific/Honolulu") -> CameraTarget(35.33, -97.28, 6.5, 0)
            else -> CameraTarget(30.0, 10.0, 2.5, 0)
        }
    }

    /** Device location, once permission is granted. Recentres only the first time. */
    fun onLocation(lat: Double, lon: Double) {
        val p = LatLon(lat, lon)
        viewModelScope.launch {
            val region = if (Coverage.inUs(p)) null else withContext(Dispatchers.Default) {
                (regions ?: runCatching { WarningRegions.bundled() }.getOrNull())?.regionAt(p)
            }
            when {
                Coverage.inUs(p) -> checker.setHome(p, null, null)
                region != null -> checker.setHome(p, region.code, region.name.lowercase().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } } + ", " + region.country)
                else -> checker.setHome(null, null, null)
            }
            syncStormMode()
            refreshAlerts()
        }
        if (!prefs.getBoolean("located_once", false)) {
            prefs.edit().putBoolean("located_once", true).apply()
            val zoom = if (Sites.nearest(p) != null) 7.0 else 5.5
            _state.update { it.copy(camera = CameraTarget(lat, lon, zoom, ++cameraSeq)) }
        }
    }

    fun onCameraIdle(center: LatLng, zoom: Double, bounds: LatLngBounds?) {
        mapCenter = LatLon(center.latitude, center.longitude)
        mapZoom = zoom
        visible = bounds
        prefs.edit().putFloat("cam_lat", center.latitude.toFloat()).putFloat("cam_lon", center.longitude.toFloat())
            .putFloat("cam_zoom", zoom.toFloat()).apply()
        _state.update { it.copy(nearbySites = Sites.byDistance(mapCenter).take(25).map { s -> s to Sites.distanceKm(mapCenter, s.location).roundToInt() }) }
        decideSource()
        if (_state.value.mode == Mode.COMPOSITE) refreshCompositeTile(force = false)
        updateVisibleWarnings()
        if (_state.value.measure != null) rebuildOverlays()
        // New countries in view: fetch their warnings soon rather than at the next minute.
        regions?.let { r ->
            bounds?.let { b ->
                val inView = r.countriesIn(b.latitudeSouth, b.longitudeWest, b.latitudeNorth, b.longitudeEast)
                if (inView.size <= 6 && inView.any { it in WarningRegions.FEEDS && it !in euFeeds }) {
                    viewModelScope.launch { refreshAlerts() }
                }
            }
        }
        val before = _state.value.motion
        chooseMotion()
        if (_state.value.motion != before && _state.value.product == Product.SRV) showCurrent()
    }

    /**
     * Picks what to show for the current view:
     * - zoomed in near an open radar: that radar (auto mode), or the user's pick;
     * - otherwise inside Europe: the OPERA composite;
     * - otherwise: nothing, and say so.
     */
    private fun decideSource() {
        val s = _state.value
        val inEurope = Coverage.inOperaComposite(mapCenter)
        if (!s.autoSite && s.site != null) {
            if (s.mode != Mode.SITE) setMode(Mode.SITE)
            return
        }
        val nearest = if (mapZoom >= SITE_ZOOM) Sites.nearest(mapCenter, 230.0) else null
        when {
            nearest != null -> {
                if (nearest.id != s.site?.id) startSite(nearest)
                if (s.mode != Mode.SITE) setMode(Mode.SITE)
            }
            inEurope -> {
                if (s.site != null) stopSite()
                if (s.mode != Mode.COMPOSITE) setMode(Mode.COMPOSITE)
            }
            Imagery.inMrms(mapCenter) -> {
                if (s.site != null) stopSite()
                if (s.mode != Mode.MOSAIC) setMode(Mode.MOSAIC)
            }
            s.site != null && Sites.distanceKm(mapCenter, s.site.location) < 600 -> Unit // keep the last radar nearby
            else -> {
                if (s.site != null) stopSite()
                setMode(Mode.NONE)
            }
        }
    }

    private fun setMode(mode: Mode) {
        stopLoop()
        _state.update {
            it.copy(
                mode = mode,
                loop = LoopUi(),
                inspector = null,
                compositeLayer = if (mode == Mode.COMPOSITE) it.compositeLayer else null,
                siteLayer = if (mode == Mode.SITE) it.siteLayer else null,
                status = when (mode) {
                    Mode.NONE -> FeedStatus.NoCoverage(
                        if (it.satellite) "No open radar here. Showing infrared satellite" else "No open radar data for this area",
                    )
                    Mode.MOSAIC -> FeedStatus.Live
                    else -> it.status
                },
                frameEndMillis = if (mode == Mode.MOSAIC || mode == Mode.NONE) null else it.frameEndMillis,
                availableProducts = if (mode == Mode.SITE) it.availableProducts else setOf(Product.REF),
                attribution = attributionFor(mode, it.site),
            )
        }
        if (mode == Mode.COMPOSITE) startComposite() else stopComposite()
        if (mode == Mode.COMPOSITE) refreshLoopUi()
        if (mode == Mode.MOSAIC || mode == Mode.NONE) _state.update { it.copy(loop = LoopUi(available = false)) }
        updateImagery()
    }

    /** Satellite where there's no radar; the NWS mosaic in MOSAIC mode. */
    private fun updateImagery() {
        val s = _state.value
        val list = mutableListOf<Pair<ImagerySource, Float>>()
        if (s.satellite && s.mode == Mode.NONE) list += Imagery.satellites(eumetsat).map { it to 0.75f }
        if (s.mode == Mode.MOSAIC) list += Imagery.mrms(System.currentTimeMillis() / 300_000) to 0.9f
        _state.update { it.copy(imagery = list, attribution = attributionFor(it.mode, it.site)) }
    }

    /** EUMETView layer names, cached for a week. */
    private suspend fun loadEumetsatLayers(): EumetsatLayers? {
        val age = System.currentTimeMillis() - prefs.getLong("eum_time", 0)
        if (age < 7 * 86_400_000L && prefs.contains("eum_zero")) {
            return EumetsatLayers(prefs.getString("eum_zero", null)?.ifEmpty { null }, prefs.getString("eum_iodc", null)?.ifEmpty { null })
        }
        return runCatching { EumetsatLayers.discover(http) }.getOrNull()?.also {
            prefs.edit().putString("eum_zero", it.zeroDegree.orEmpty()).putString("eum_iodc", it.indianOcean.orEmpty())
                .putLong("eum_time", System.currentTimeMillis()).apply()
        }
    }

    private fun attributionFor(mode: Mode, site: RadarSite?): String {
        val radar = when {
            mode == Mode.COMPOSITE -> "Radar: EUMETNET OPERA (CC BY 4.0)"
            mode == Mode.MOSAIC -> Imagery.MRMS_ATTRIBUTION + ", warnings NOAA / NWS"
            site?.network == Network.OPERA && DwdDualPol.supports(site.id) ->
                "Radar: EUMETNET OPERA, ${site.name.substringAfterLast(", ")} (CC BY 4.0); CC/ZDR: Source: Deutscher Wetterdienst"
            site?.network == Network.OPERA -> "Radar: EUMETNET OPERA, ${site.name.substringAfterLast(", ")} (CC BY 4.0)"
            mode == Mode.SITE -> "Radar and warnings: NOAA / NWS"
            _state.value.satellite -> Imagery.GIBS_ATTRIBUTION + "; " + Imagery.eumetsatAttribution(java.time.Year.now().value)
            else -> ""
        }
        val eu = if (euFeeds.isNotEmpty() && mode != Mode.MOSAIC && site?.network != Network.NEXRAD) "Warnings: MeteoAlarm / EUMETNET" else ""
        return listOf(radar, eu).filter { it.isNotEmpty() }.joinToString(". ")
    }

    // ---- user actions ---------------------------------------------------------------

    /** Pick a radar from the list (manual mode) and fly to it. */
    fun selectSite(site: RadarSite) {
        prefs.edit().putBoolean("auto_site", false).apply()
        _state.update { it.copy(autoSite = false, camera = CameraTarget(site.location.lat, site.location.lon, 7.0, ++cameraSeq)) }
        if (site.id != _state.value.site?.id) startSite(site)
        setMode(Mode.SITE)
    }

    fun setAutoSite() {
        prefs.edit().putBoolean("auto_site", true).apply()
        _state.update { it.copy(autoSite = true) }
        decideSource()
    }

    fun selectProduct(p: Product) {
        _state.update { it.copy(product = p, inspector = null) }
        refreshLoopUi()
        showCurrent()
    }

    fun selectTilt(t: Float) {
        _state.update { it.copy(tilt = t, inspector = null) }
        wantedTilts.value = setOf(t)
        refreshLoopUi()
        showCurrent()
    }

    fun dismissInspector() = _state.update { it.copy(inspector = null) }

    fun togglePlay() {
        val l = _state.value.loop
        if (l.playing) stopLoop() else if (l.available && l.frames.size >= 2) startLoop()
    }

    /** Scrub to a frame (pauses playback). Index past the end = latest. */
    fun scrubTo(index: Int) {
        stopLoop()
        val frames = _state.value.loop.frames
        val i = index.coerceIn(0, (frames.size - 1).coerceAtLeast(0))
        _state.update { it.copy(loop = it.loop.copy(index = if (i >= frames.size - 1) null else i)) }
        showCurrent()
    }

    fun setIntensity(i: MapIntensity) {
        prefs.edit().putFloat("roads", i.roads).putFloat("labels", i.labels).putFloat("borders", i.borders).apply()
        _state.update { it.copy(intensity = i) }
    }

    fun setUnits(u: UnitSystem) {
        prefs.edit().putString("units", u.name).apply()
        _state.update { it.copy(units = u, inspector = null) }
    }

    fun setRedNight(on: Boolean) {
        prefs.edit().putBoolean("red_night", on).apply()
        _state.update { it.copy(redNight = on) }
    }

    // ---- measure tool ------------------------------------------------------------------

    fun startMeasure() {
        _state.update { it.copy(measure = Measure(), inspector = null) }
        rebuildOverlays()
    }

    fun closeMeasure() {
        _state.update { it.copy(measure = null) }
        rebuildOverlays()
    }

    /** Map tap while measuring: first point, second point, then start over. */
    fun measureTap(p: LatLon): Boolean {
        val m = _state.value.measure ?: return false
        val next = when {
            m.a == null -> Measure(p, null)
            m.b == null -> m.copy(b = p)
            else -> Measure(p, null)
        }
        _state.update { it.copy(measure = next) }
        rebuildOverlays()
        return true
    }

    private fun measureOutlines(o: MercatorProjector): List<Outline> {
        val m = _state.value.measure ?: return emptyList()
        val out = mutableListOf<Outline>()
        val ink = 0xFFECE8DF.toInt()
        fun mark(p: LatLon) {
            val pts = FloatArray(24)
            for (i in 0 until 12) {
                val (la, lo) = RadialGeometry.destination(p.lat, p.lon, i * 30.0, 2_500.0 * (1 + mapZoomScale()))
                val (x, y) = o.fromLatLon(la, lo)
                pts[i * 2] = x.toFloat(); pts[i * 2 + 1] = y.toFloat()
            }
            out += Outline(pts, ink, 2.5f)
        }
        m.a?.let(::mark)
        m.b?.let(::mark)
        val a = m.a; val b = m.b
        if (a != null && b != null) {
            // Great-circle path, sampled so it stays straight-looking on long lines.
            val (brg, dist) = MercatorProjector.bearingDistance(a.lat, a.lon, b.lat, b.lon)
            val n = 32
            val pts = FloatArray((n + 1) * 2)
            for (i in 0..n) {
                val (la, lo) = RadialGeometry.destination(a.lat, a.lon, brg, dist * i / n)
                val (x, y) = o.fromLatLon(la, lo)
                pts[i * 2] = x.toFloat(); pts[i * 2 + 1] = y.toFloat()
            }
            out += Outline(pts, ink, 3f, closed = false)
        }
        return out
    }

    /** Marker size grows when zoomed out so it stays visible. */
    private fun mapZoomScale(): Double = Math.pow(2.0, (8.0 - mapZoom).coerceIn(0.0, 6.0)) - 1

    fun setSatellite(on: Boolean) {
        prefs.edit().putBoolean("satellite", on).apply()
        _state.update { it.copy(satellite = on) }
        if (_state.value.mode == Mode.NONE) setMode(Mode.NONE) else updateImagery()
    }

    fun setShowWarnings(on: Boolean) {
        prefs.edit().putBoolean("show_warnings", on).apply()
        _state.update { it.copy(showWarnings = on) }
        rebuildOverlays()
    }

    fun setStormMode(on: Boolean) {
        checker.enabled = on
        syncStormMode()
    }

    fun setEuMinLevel(level: WarningLevel) {
        checker.euMinLevel = level
        _state.update { it.copy(euMinLevel = level) }
    }

    private fun syncStormMode() {
        val on = checker.enabled
        if (on) app.hookline.radar.stormmode.StormModeWorker.schedule(getApplication())
        else app.hookline.radar.stormmode.StormModeWorker.cancel(getApplication())
        _state.update {
            it.copy(stormModeEnabled = on, stormModeOn = on && checker.places().isNotEmpty(), homeLabel = checker.homeLabel())
        }
    }

    /** Fly to a warning's area. */
    fun showWarning(w: WarningItem) {
        val b = w.box ?: return
        val lat = (b[0] + b[2]) / 2; val lon = (b[1] + b[3]) / 2
        val span = maxOf(b[2] - b[0], (b[3] - b[1]) * 0.6).coerceAtLeast(0.05)
        val zoom = (Math.log(180.0 / span) / Math.log(2.0) + 0.5).coerceIn(4.0, 10.0)
        _state.update { it.copy(camera = CameraTarget(lat, lon, zoom, ++cameraSeq)) }
    }

    /** Fly somewhere (e.g. a search result) without changing the radar choice. */
    fun flyTo(lat: Double, lon: Double, zoom: Double) {
        _state.update { it.copy(camera = CameraTarget(lat, lon, zoom, ++cameraSeq)) }
    }

    fun setLoopLength(n: Int) {
        prefs.edit().putInt("loop_length", n).apply()
        _state.update { it.copy(loopLength = n) }
    }

    fun inspect(at: LatLng) {
        val s = _state.value
        if (s.mode == Mode.SITE) {
            val tex = currentTexture ?: return
            val loc = siteLocation ?: return
            val (brg, dist) = MercatorProjector.bearingDistance(loc.lat, loc.lon, at.latitude, at.longitude)
            val v = tex.valueAtPolar(brg, dist)
            val u = s.units
            val height = Fmt.height(RadialGeometry.beamHeightMeters(dist, tex.elevationDeg.toDouble()), u) +
                " above the radar, " + Fmt.distance(dist, u) + " out"
            _state.update { it.copy(inspector = describe(it.product, v, height)) }
        } else if (s.mode == Mode.COMPOSITE) {
            val g = compositeGrid ?: return
            val loc = compositeLocator ?: return
            val v = CompositeTile.valueAt(g, loc, at.latitude, at.longitude)
            val inspection = if (v.isNaN()) {
                Inspection("No data", "This spot isn't covered by the European radar network.", "Europe composite")
            } else {
                describe(Product.REF, if (v.isInfinite()) null else v, "Highest echo above this spot, from all nearby radars")
            }
            _state.update { it.copy(inspector = inspection) }
        } else if (s.mode == Mode.MOSAIC) {
            _state.update {
                it.copy(
                    inspector = Inspection(
                        "Zoom in for values",
                        "Zoomed out, you see NOAA's national mosaic picture. Zoom in and Hookline switches to the nearest radar's own data.",
                        "US radar mosaic",
                    ),
                )
            }
        } else {
            _state.update {
                it.copy(
                    inspector = Inspection(
                        "No radar here",
                        if (it.satellite) "No open radar covers this spot. The grey and white shapes are clouds seen in infrared by satellite: the whiter, the colder and higher the cloud tops."
                        else "No open radar covers this spot.",
                        "Satellite infrared",
                    ),
                )
            }
        }
    }

    fun downloadVisibleMap(bounds: LatLngBounds, pixelRatio: Float) {
        offline.download(bounds, _state.value.site?.name?.let { "$it area" } ?: "Saved area", pixelRatio)
    }

    // ---- single-site radar ------------------------------------------------------------------

    private fun stopSite() {
        streamJob?.cancel(); backfillJob?.cancel(); dualPolJob?.cancel()
        dualPol.clear()
        snapshot = null
        currentTexture = null
        refLoop.clear(); velLoop.clear()
        _state.update { it.copy(site = null, siteLayer = null, tilts = emptyList(), outlines = emptyList()) }
    }

    private fun startSite(site: RadarSite) {
        stopLoop()
        streamJob?.cancel(); backfillJob?.cancel(); dualPolJob?.cancel()
        dualPol.clear()
        snapshot = null
        currentTexture = null
        refLoop.clear(); velLoop.clear()
        siteLocation = site.location
        wantedTilts.value = emptySet()
        _state.update {
            it.copy(
                site = site, siteLayer = null, tilts = emptyList(), tilt = 0f, loop = LoopUi(),
                status = FeedStatus.Connecting, frameEndMillis = null, inspector = null,
                attribution = attributionFor(Mode.SITE, site),
            )
        }
        rebuildOverlays()
        if (DwdDualPol.supports(site.id)) startDualPol(site)
        streamJob = viewModelScope.launch {
            val events = if (site.network == Network.OPERA) ord.stream(site, wantedTilts) else live.stream(site.id)
            events.collect { e ->
                when (e) {
                    is LiveEvent.Data -> onLiveData(site, e)
                    is LiveEvent.Trouble -> _state.update { s ->
                        s.copy(status = if (snapshot == null) FeedStatus.Stale("${site.name}: ${e.message}") else FeedStatus.Stale(e.message))
                    }
                }
            }
        }
    }

    private suspend fun onLiveData(site: RadarSite, e: LiveEvent.Data) {
        val first = snapshot == null
        snapshot = e.snapshot
        e.snapshot.site?.let { siteLocation = LatLon(it.latitude.toDouble(), it.longitude.toDouble()) }
        _state.update { s ->
            val tilts = e.snapshot.tilts
            s.copy(
                status = FeedStatus.Live,
                tilts = tilts,
                tilt = if (s.tilt in tilts) s.tilt else tilts.firstOrNull() ?: s.tilt,
            )
        }
        if (first) rebuildOverlays()
        val lt = loopTilt()
        addToLoop(e.snapshot.sweep(lt, Moment.REFLECTIVITY), e.snapshot.sweep(lt, Moment.VELOCITY))
        if (first) startBackfill(site, e.volume)
        if (_state.value.showingLatest) showCurrent()
    }

    /**
     * CC and ZDR for German radars from DWD: fetched once on opening the
     * radar, then every 5 minutes while one of them is on screen.
     */
    private fun startDualPol(site: RadarSite) {
        dualPolJob = viewModelScope.launch {
            var first = true
            while (isActive) {
                val p = _state.value.product
                if (first || p == Product.CC || p == Product.ZDR) {
                    for (m in listOf(Moment.CORRELATION_COEFFICIENT, Moment.DIFFERENTIAL_REFLECTIVITY)) {
                        try {
                            dwd.latest(site.id, m)?.let { dualPol[m] = it }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            // Dual-pol is extra; keep the last one (and never let a bad file take the app down).
                        }
                    }
                    first = false
                    if (_state.value.showingLatest) showCurrent()
                }
                delay(300_000)
            }
        }
    }

    /** DWD dual-pol sweep for the lowest tilt, used when OPERA has no CC/ZDR. */
    private fun dualPolSweep(tilt: Float, moment: Moment): app.hookline.radar.core.radar.Sweep? =
        if (tilt == snapshot?.tilts?.firstOrNull()) dualPol[moment]?.sweep else null

    /** Loops use the lowest tilt (0.5 deg for NEXRAD; it varies in Europe). */
    private fun loopTilt(): Float = snapshot?.tilts?.firstOrNull() ?: LoopBackfill.LOOP_TILT

    private fun startBackfill(site: RadarSite, volume: Int) {
        backfillJob?.cancel()
        val wanted = _state.value.loopLength
        if (wanted <= 1) return
        backfillJob = viewModelScope.launch {
            var got = 0
            setLoopLoading("Loading loop…")
            try {
                val frames = if (site.network == Network.OPERA) ord.loopFrames(site, wanted - 1) else backfill.frames(site.id, volume, wanted - 1)
                frames.collect { f ->
                    got++
                    addToLoop(f.reflectivity, f.velocity)
                    setLoopLoading("Loading loop $got/${wanted - 1}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Loops are a nice-to-have; the live view carries on.
            }
            setLoopLoading(null)
        }
    }

    private fun setLoopLoading(text: String?) = _state.update { it.copy(loop = it.loop.copy(loading = text)) }

    private suspend fun addToLoop(ref: app.hookline.radar.core.radar.Sweep?, vel: app.hookline.radar.core.radar.Sweep?) {
        val (r, v) = withContext(Dispatchers.Default) {
            ref?.let { CompactMoment.from(it, Moment.REFLECTIVITY) } to vel?.let { CompactMoment.from(it, Moment.VELOCITY) }
        }
        val cap = _state.value.loopLength.coerceAtLeast(2) + 4
        r?.let { refLoop[it.endMillis] = it; while (refLoop.size > cap) refLoop.pollFirstEntry() }
        v?.let { velLoop[it.endMillis] = it; while (velLoop.size > cap) velLoop.pollFirstEntry() }
        refreshLoopUi()
    }

    private fun loopFor(p: Product): TreeMap<Long, CompactMoment>? = when (p) {
        Product.REF -> refLoop
        Product.VEL, Product.SRV -> velLoop
        else -> null
    }

    // ---- composite ------------------------------------------------------------------------------

    private fun startComposite() {
        if (compositeJob?.isActive == true) return
        compositeJob = viewModelScope.launch {
            var attempt = 0
            while (isActive) {
                try {
                    val keys = opera.latestKeys(_state.value.loopLength.coerceIn(2, 12))
                    val newest = keys.firstOrNull()
                    if (newest == null) {
                        if (compositeGrid == null) _state.update { it.copy(status = FeedStatus.Stale("No recent European composite")) }
                    } else if (newest.key != compositeKey?.key) {
                        val bytes = opera.fetchBytes(newest)
                        val grid = withContext(Dispatchers.Default) { OperaComposite.decode(bytes) }
                        compositeGrid = grid
                        compositeLocator = withContext(Dispatchers.Default) { CompositeTile.Locator(grid) }
                        compositeKey = newest
                        compositeBytes[newest.timeMillis] = bytes
                        // Remember the other recent frames' keys for loops (downloaded on play).
                        compositeKeys = keys
                        while (compositeBytes.size > 12) compositeBytes.pollFirstEntry()
                        _state.update { it.copy(status = FeedStatus.Live) }
                        refreshCompositeTile(force = true)
                        refreshLoopUi()
                    }
                    attempt = 0
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    attempt++
                    _state.update { it.copy(status = FeedStatus.Stale(e.message ?: "Can't reach European radar data")) }
                }
                delay(if (attempt == 0) 120_000L else (15_000L * attempt).coerceAtMost(120_000L))
            }
        }
    }

    private fun stopComposite() {
        compositeJob?.cancel()
        compositeJob = null
        compositeLoopLayers = emptyMap()
    }

    /** Rebuilds the composite texture for the visible area when the view moved off it or zoomed a lot. */
    private fun refreshCompositeTile(force: Boolean) {
        val grid = compositeGrid ?: return
        val loc = compositeLocator ?: return
        val b = visible ?: return
        val s = b.latitudeSouth; val n = b.latitudeNorth; val w = b.longitudeWest; val e = b.longitudeEast
        val cur = compositeBounds
        val covered = cur != null && s >= cur[0] && w >= cur[1] && n <= cur[2] && e <= cur[3]
        if (!force && covered && abs(mapZoom - compositeZoom) < 1.5) return
        if (_state.value.loop.playing) {
            // Loop frames were cut for the old view; stop rather than show a stale area.
            playJob?.cancel(); playJob = null
            _state.update { it.copy(loop = it.loop.copy(playing = false, index = null)) }
        }
        // Pad by half a screen each way so small pans don't trigger rebuilds.
        val dLat = (n - s) * 0.5; val dLon = (e - w) * 0.5
        val box = doubleArrayOf((s - dLat).coerceAtLeast(-85.0), w - dLon, (n + dLat).coerceAtMost(85.0), e + dLon)
        val origin = _state.value.origin
        compositeTileJob?.cancel()
        compositeTileJob = viewModelScope.launch {
            val layer = withContext(Dispatchers.Default) {
                CompositeTile.build(grid, loc, box[0], box[1], box[2], box[3], 2048, origin.siteLat, origin.siteLon, true)
                    ?.let { compositeLayer(it) }
            }
            compositeBounds = box
            compositeZoom = mapZoom
            compositeLoopLayers = emptyMap()
            if (_state.value.mode == Mode.COMPOSITE && _state.value.showingLatest) {
                _state.update { it.copy(compositeLayer = layer, frameEndMillis = grid.endMillis) }
            }
        }
    }

    private fun compositeLayer(t: CompositeTile) = RasterLayer(
        t.texWidth, t.texHeight, t.values, t.mesh, Product.REF.palette, Product.REF.min, Product.REF.max,
    )

    /** Download and prepare the composite loop for the current view. */
    private suspend fun prepareCompositeLoop(): Boolean {
        val loc = compositeLocator ?: return false
        val box = compositeBounds ?: return false
        val origin = _state.value.origin
        val keys = compositeKeys.sortedBy { it.timeMillis }
        if (keys.size < 2) return false
        val layers = HashMap<Long, RasterLayer>()
        for ((i, k) in keys.withIndex()) {
            setLoopLoading("Loading loop ${i + 1}/${keys.size}")
            val bytes = compositeBytes[k.timeMillis] ?: opera.fetchBytes(k).also { compositeBytes[k.timeMillis] = it }
            val layer = withContext(Dispatchers.Default) {
                val g = OperaComposite.decode(bytes)
                CompositeTile.build(g, loc, box[0], box[1], box[2], box[3], 1024, origin.siteLat, origin.siteLon, true)?.let { compositeLayer(it) }
            } ?: continue
            layers[k.timeMillis] = layer
        }
        setLoopLoading(null)
        compositeLoopLayers = layers
        _state.update { it.copy(loop = it.loop.copy(frames = layers.keys.sorted())) }
        return layers.size >= 2
    }

    // ---- loops (both modes) -------------------------------------------------------------------------

    private fun refreshLoopUi() {
        val s = _state.value
        if (s.mode == Mode.COMPOSITE) {
            val frames = compositeKeys.map { it.timeMillis }.sorted()
            _state.update { it.copy(loop = it.loop.copy(frames = frames, available = frames.size >= 2), availableProducts = setOf(Product.REF)) }
            return
        }
        val buffer = if (s.tilt == loopTilt()) loopFor(s.product) else null
        val frames = buffer?.keys?.toList().orEmpty()
        _state.update {
            val idx = it.loop.index?.takeIf { i -> i < frames.size - 1 }
            it.copy(loop = it.loop.copy(frames = frames, index = idx, available = buffer != null && snapshot != null))
        }
        if (buffer == null) stopLoop()
    }

    private fun startLoop() {
        playJob?.cancel()
        _state.update { it.copy(loop = it.loop.copy(playing = true)) }
        playJob = viewModelScope.launch {
            if (_state.value.mode == Mode.COMPOSITE && compositeLoopLayers.size < 2) {
                if (!prepareCompositeLoop()) { stopLoop(); return@launch }
            }
            _state.update { it.copy(loop = it.loop.copy(index = 0)) }
            showCurrent()
            while (isActive) {
                val l = _state.value.loop
                val n = l.frames.size
                if (n < 2) break
                val cur = l.index ?: (n - 1)
                // Dwell on the newest frame so the eye can find "now".
                delay(if (l.index == null) 1_500 else 300)
                val next = (cur + 1) % n
                _state.update { it.copy(loop = it.loop.copy(index = if (next == n - 1) null else next)) }
                showCurrent()
            }
        }
    }

    private fun stopLoop() {
        val wasPlaying = _state.value.loop.playing
        playJob?.cancel(); playJob = null
        _state.update { it.copy(loop = it.loop.copy(playing = false)) }
        // Back on "latest" in composite mode: swap the loop's lower-resolution frame for full resolution.
        if (wasPlaying && _state.value.mode == Mode.COMPOSITE && _state.value.showingLatest) refreshCompositeTile(force = true)
    }

    // ---- building what's on screen -----------------------------------------------------------------

    private fun showCurrent() {
        val s = _state.value
        if (s.mode == Mode.COMPOSITE) {
            val idx = s.loop.index
            if (idx == null) {
                val lastKey = s.loop.frames.lastOrNull()
                val last = lastKey?.let { compositeLoopLayers[it] }
                if (s.loop.playing && last != null) _state.update { it.copy(compositeLayer = last, frameEndMillis = lastKey) }
                else refreshCompositeTile(force = true)
            } else {
                val key = s.loop.frames.getOrNull(idx) ?: return
                val layer = compositeLoopLayers[key] ?: return
                _state.update { it.copy(compositeLayer = layer, frameEndMillis = key) }
            }
            return
        }
        if (s.mode != Mode.SITE) return
        val loc = siteLocation ?: return
        val idx = s.loop.index
        val buffer = loopFor(s.product)
        if (idx != null && s.loop.available && buffer != null) {
            val key = s.loop.frames.getOrNull(idx) ?: return
            val compact = buffer[key] ?: return
            render(s.product, s.origin, loc) {
                if (s.product == Product.SRV) {
                    val m = s.motion
                    compact.toTexture { az, v -> v - StormRelative.component(az, m.fromDeg.toFloat(), m.speedKt * 0.514444f) }
                } else compact.toTexture()
            }
            return
        }
        val snap = snapshot ?: return
        val available = Product.entries.filter { snap.sweep(s.tilt, it.moment) != null || dualPolSweep(s.tilt, it.moment) != null }.toSet()
        val product = if (s.product in available || available.isEmpty()) s.product else Product.REF
        if (product != s.product || available != s.availableProducts) {
            _state.update { it.copy(product = product, availableProducts = available.ifEmpty { setOf(Product.REF) }) }
        }
        val sweep = snap.sweep(s.tilt, product.moment) ?: dualPolSweep(s.tilt, product.moment) ?: return // tilt still downloading
        val motion = s.motion
        render(product, s.origin, loc) {
            val srv: ((Radial, MomentData) -> MomentData)? = if (product == Product.SRV) {
                { r, md -> StormRelative.apply(r, motion.fromDeg.toFloat(), motion.speedKt * 0.514444f) ?: md }
            } else null
            SweepTexture.from(sweep, product.moment, srv)
        }
    }

    private fun render(product: Product, origin: MercatorProjector, radar: LatLon, build: () -> SweepTexture?) {
        frameJob?.cancel()
        frameJob = viewModelScope.launch {
            val built = withContext(Dispatchers.Default) {
                val tex = build() ?: return@withContext null
                tex to RasterLayer(
                    tex.gateCount, tex.radialCount, tex.values, tex.wedgeMesh(origin, radar.lat, radar.lon),
                    product.palette, product.min, product.max,
                )
            } ?: return@launch
            currentTexture = built.first
            _state.update { it.copy(siteLayer = built.second, frameEndMillis = built.first.endMillis) }
        }
    }

    // ---- warnings, storm motion, tracks, rings ------------------------------------------------------

    private suspend fun refreshAlerts() {
        val home = checker.places().firstOrNull()?.second
        val homeCode = checker.homeRegion()
        if (Coverage.inUs(mapCenter) || (home != null && Coverage.inUs(home))) {
            runCatching {
                nws.activeEvents(listOf("Tornado Warning", "Severe Thunderstorm Warning", "Flash Flood Warning"))
            }.onSuccess { alerts = it }
        } else {
            alerts = emptyList()
        }
        refreshEuWarnings(homeCode)
        val now = Instant.now()
        val eu = euFeeds.values.flatMap { it.second }.filter { it.isActive(now) }
        val homeWarning = home?.let { h ->
            alerts.firstOrNull { it.covers(h) }?.toWarningItem()
                ?: eu.filter { w -> homeCode != null && homeCode in w.codes }.maxByOrNull { it.level.ordinal }
        }
        _state.update { s -> s.copy(homeWarning = homeWarning) }
        updateVisibleWarnings()
        chooseMotion()
        rebuildOverlays()
        if (_state.value.product == Product.SRV) showCurrent()
    }

    /** MeteoAlarm feeds for the countries in view (and home), each refreshed at most every 10 minutes. */
    private suspend fun refreshEuWarnings(homeCode: String?) {
        val r = regions ?: return
        val b = visible
        val wanted = LinkedHashSet<String>()
        homeCode?.let { wanted += WarningRegions.countryOf(it) }
        if (b != null) {
            val inView = withContext(Dispatchers.Default) {
                r.countriesIn(b.latitudeSouth, b.longitudeWest, b.latitudeNorth, b.longitudeEast)
            }
            if (inView.size <= 6) wanted += inView
            else r.regionAt(mapCenter)?.let { wanted += it.country }
        }
        val now = System.currentTimeMillis()
        for (c in wanted) {
            if (c !in WarningRegions.FEEDS) continue
            val cached = euFeeds[c]
            if (cached != null && now - cached.first < 600_000) continue
            runCatching { meteoAlarm.warnings(c) }.onSuccess { euFeeds[c] = now to it }
        }
        // Forget countries far from view after an hour.
        euFeeds.entries.removeIf { (c, v) -> c !in wanted && now - v.first > 3_600_000 }
    }

    private fun updateVisibleWarnings() {
        val b = visible
        val now = Instant.now()
        val all = alerts.map { it.toWarningItem() } + euFeeds.values.flatMap { it.second }.filter { it.isActive(now) }
        val shown = if (b == null) all else all.filter { it.intersects(b.latitudeSouth, b.longitudeWest, b.latitudeNorth, b.longitudeEast) }
        _state.update {
            it.copy(
                warnings = shown.sortedWith(compareByDescending<WarningItem> { w -> w.level.ordinal }.thenBy { w -> w.onset }),
                attribution = attributionFor(it.mode, it.site),
            )
        }
    }

    /**
     * SRV needs the storm's own motion. Use the NWS warning closest to where
     * the user is looking (within the radar's range); else a default, and say so.
     */
    private fun chooseMotion() {
        val site = siteLocation ?: mapCenter
        val focus = mapCenter
        val best = alerts.mapNotNull { a -> a.motion?.takeIf { it.positions.isNotEmpty() }?.let { a to it } }
            .map { (a, m) ->
                val pos = m.positions.first()
                val fromSite = MercatorProjector.bearingDistance(site.lat, site.lon, pos.lat, pos.lon).second
                val fromFocus = MercatorProjector.bearingDistance(focus.lat, focus.lon, pos.lat, pos.lon).second
                Triple(a, m, fromSite to fromFocus)
            }
            .filter { it.third.first < 230_000 }
            .minByOrNull { it.third.second }
        _state.update {
            it.copy(motion = best?.let { (a, m, _) -> MotionInfo(m.fromDeg, m.speedKt, "From the ${a.event.lowercase()}") } ?: MapUiState.DEFAULT_MOTION)
        }
    }

    private fun rebuildOverlays() {
        val o = _state.value.origin
        val list = mutableListOf<Outline>()
        // Range rings every 50 km around the radar.
        if (_state.value.site != null) {
            siteLocation?.let { r ->
                for (k in 1..5) {
                    val pts = FloatArray(RING_SEGMENTS * 2)
                    for (i in 0 until RING_SEGMENTS) {
                        val (x, y) = o.fromPolarAt(r.lat, r.lon, 360.0 * i / RING_SEGMENTS, k * 50_000.0)
                        pts[i * 2] = x.toFloat(); pts[i * 2 + 1] = y.toFloat()
                    }
                    list += Outline(pts, 0x553A4652, 1.5f)
                }
            }
        }
        if (_state.value.showWarnings) list += euOutlines(o)
        list += measureOutlines(o)
        for (a in alerts) {
            if (!_state.value.showWarnings) break
            val (argb, width) = when (a.hazard) {
                Hazard.TORNADO_EMERGENCY -> 0xFFB000B0.toInt() to 4f
                Hazard.TORNADO_WARNING -> 0xFFE0245E.toInt() to 3.5f
                Hazard.SEVERE_WARNING -> 0xFFF5A623.toInt() to 2.5f
                Hazard.FLASH_FLOOD_WARNING -> 0xFF2E9E5B.toInt() to 2.5f
                else -> continue
            }
            for (ring in a.polygons) {
                val pts = FloatArray(ring.size * 2)
                ring.forEachIndexed { i, ll ->
                    val (x, y) = o.fromLatLon(ll.lat, ll.lon)
                    pts[i * 2] = x.toFloat(); pts[i * 2 + 1] = y.toFloat()
                }
                list += Outline(pts, argb, width)
            }
            a.motion?.let { list += stormTrack(it, o) }
        }
        _state.update { it.copy(outlines = list) }
    }

    /** One outline per MeteoAlarm region, in the colour of its highest active level. */
    private fun euOutlines(o: MercatorProjector): List<Outline> {
        val r = regions ?: return emptyList()
        val now = Instant.now()
        val levels = HashMap<String, WarningLevel>()
        for (w in euFeeds.values.flatMap { it.second }) {
            if (!w.isActive(now)) continue
            for (code in w.codes) if ((levels[code]?.ordinal ?: -1) < w.level.ordinal) levels[code] = w.level
        }
        val out = mutableListOf<Outline>()
        for ((code, level) in levels.entries.sortedBy { it.value.ordinal }) {
            val region = r.regions[code] ?: continue
            for (ring in region.polygons) {
                if (ring.size < 3) continue
                val pts = FloatArray(ring.size * 2)
                ring.forEachIndexed { i, ll ->
                    val (x, y) = o.fromLatLon(ll.lat, ll.lon)
                    pts[i * 2] = x.toFloat(); pts[i * 2 + 1] = y.toFloat()
                }
                out += Outline(pts, (level.argb and 0x00FFFFFF) or (0xD9 shl 24), if (level == WarningLevel.RED) 3f else 2f)
            }
        }
        return out
    }

    /** Projected path: a line to +45 min with marks at 15, 30 and 45 minutes. */
    private fun stormTrack(m: StormMotion, p: MercatorProjector): List<Outline> {
        val out = mutableListOf<Outline>()
        val toward = (m.fromDeg + 180) % 360.0
        val ink = 0xE6ECE8DF.toInt()
        for (pos in m.positions) {
            val path = FloatArray(8)
            for ((k, minutes) in listOf(0, 15, 30, 45).withIndex()) {
                val (lat, lon) = RadialGeometry.destination(pos.lat, pos.lon, toward, m.speedMs * 60.0 * minutes)
                val (x, y) = p.fromLatLon(lat, lon)
                path[k * 2] = x.toFloat(); path[k * 2 + 1] = y.toFloat()
                if (minutes > 0) {
                    val mark = FloatArray(8)
                    for ((j, b) in listOf(0.0, 90.0, 180.0, 270.0).withIndex()) {
                        val (la, lo) = RadialGeometry.destination(lat, lon, b, 1_200.0)
                        val (mx, my) = p.fromLatLon(la, lo)
                        mark[j * 2] = mx.toFloat(); mark[j * 2 + 1] = my.toFloat()
                    }
                    out += Outline(mark, ink, 2f)
                }
            }
            out += Outline(path, ink, 2f, closed = false)
        }
        return out
    }

    // ---- inspector wording -----------------------------------------------------------------------------

    private fun describe(p: Product, v: Float?, height: String): Inspection {
        if (v == null) return Inspection("No echo", "Nothing above the radar's detection threshold here.", height)
        if (v == Gate.RANGE_FOLDED) return Inspection(
            "Range folded", "The radar can't tell how far away this echo is. Try another tilt.", height,
        )
        return when (p) {
            Product.REF -> Inspection(
                "${v.roundToInt()} dBZ",
                when {
                    v < 20 -> "Light rain, drizzle or snow."
                    v < 40 -> "Moderate rain."
                    v < 50 -> "Heavy rain."
                    v < 60 -> "Very heavy rain; hail possible."
                    else -> "Hail likely."
                },
                height,
            )
            Product.VEL, Product.SRV -> {
                val dir = if (v < 0) "toward the radar" else "away from the radar"
                Inspection(
                    Fmt.speed(abs(v).toDouble(), _state.value.units) + " " + dir,
                    if (abs(v) >= 25.7f) "Strong wind. Look for an opposite colour right next to it: that pair means rotation." else "Wind moving $dir.",
                    height,
                )
            }
            Product.CC -> Inspection(
                "%.2f".format(Locale.US, v),
                when {
                    v < 0.8f -> "Non-rain targets: debris, large hail, birds or insects. A round drop inside a hook is a debris signature."
                    v < 0.95f -> "Mixed precipitation or hail."
                    else -> "Uniform rain or snow."
                },
                height,
            )
            Product.ZDR -> Inspection(
                "%.1f dB".format(Locale.US, v),
                when {
                    v > 2.5f -> "Large, flattened raindrops."
                    v > 0.5f -> "Rain."
                    v > -0.5f -> "Round targets: hail, snow or drizzle."
                    else -> "Vertically aligned ice crystals."
                },
                height,
            )
        }
    }

    companion object {
        /** Zoom at which the map switches from the composite to the nearest single radar. */
        const val SITE_ZOOM = 6.0
        private const val RING_SEGMENTS = 120
        private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
        fun clock(millis: Long): String = TIME.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
    }
}
