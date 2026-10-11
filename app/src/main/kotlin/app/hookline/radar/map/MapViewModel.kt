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
import app.hookline.radar.core.data.Flash
import app.hookline.radar.core.data.GlmLightning
import app.hookline.radar.stormmode.Place
import app.hookline.radar.core.data.LocalGrid
import app.hookline.radar.core.data.MrmsTracks
import app.hookline.radar.core.data.StormCell
import app.hookline.radar.core.data.StormCells
import app.hookline.radar.core.data.VolumeAssembler
import java.time.LocalDate
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
import app.hookline.radar.stormmode.RainOutlook
import app.hookline.radar.core.data.RainSource
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
import app.hookline.radar.i18n.tr
import app.hookline.radar.i18n.trackLabel
import app.hookline.radar.i18n.midSentence

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
    val places: List<Place> = emptyList(),
    val rainWifiOnly: Boolean = false,
    val lightning: Boolean = false,
    /** Lightning status line, e.g. "124 flashes in the last 15 min". */
    val lightningInfo: String? = null,
    /** Second radar pane (velocity next to reflectivity). */
    val dualPane: Boolean = false,
    val siteLayer2: RasterLayer? = null,
    val cells: List<StormCell> = emptyList(),
    val showCells: Boolean = true,
    val trackOverlay: MrmsTracks.Product? = null,
    val overlayLayer: RasterLayer? = null,
    val overlayInfo: String? = null,
    val crossSection: CrossSection? = null,
    val crossSectionNote: String? = null,
    val archive: ArchiveUi? = null,
    /** Device location, once known. */
    val me: LatLon? = null,
    val intensity: MapIntensity = MapIntensity(),
    val loopLength: Int = 12,
    val camera: CameraTarget? = null,
    val attribution: String = "",
    /** Rain forecast for where you are: the headline card. */
    val rain: RainCard = RainCard(),
    /** Simple mode: plain rain legend, advanced radar controls hidden. */
    val simple: Boolean = false,
    /** Whether the simple/advanced choice was offered yet. */
    val styleAsked: Boolean = true,
    /** When the latest radar picture arrived on this phone (vs. when it was observed: frameEndMillis). */
    val receivedMillis: Long? = null,
    /** When the mosaic / satellite pictures were last requested. */
    val imageryMillis: Long? = null,
) {
    val frameAgeSeconds: Int? get() = frameEndMillis?.let { ((nowMillis - it) / 1000).toInt().coerceAtLeast(0) }
    /** Minutes after which the latest picture counts as delayed: single radars scan every ~5 min, the composite every 5–15. */
    val staleAfterMinutes: Int get() = if (mode == Mode.COMPOSITE) 25 else 12
    /** The latest picture is older than it should be. */
    val dataStale: Boolean get() = showingLatest && archive?.index == null && (frameAgeSeconds ?: 0) > staleAfterMinutes * 60
    val showingLatest: Boolean get() = loop.index == null
    /** The product shown in the second pane. */
    val product2: Product get() = if (product == Product.REF) Product.VEL else Product.REF

    companion object {
        val DEFAULT_MOTION = MotionInfo(240, 30, tr("Default motion"))
    }
}

data class Inspection(val value: String, val meaning: String, val height: String)

enum class RainCardStatus { NEED_LOCATION, LOADING, READY, NO_COVERAGE, ERROR }

/** The headline rain forecast and where it's for. */
data class RainCard(
    val status: RainCardStatus = RainCardStatus.NEED_LOCATION,
    val place: String? = null,
    val forecast: app.hookline.radar.core.data.RainForecast? = null,
    /** When the forecast was last refreshed on this phone. */
    val checkedMillis: Long = 0,
)

/** A vertical slice through the radar volume along the measure line. Row 0 is the top. */
class CrossSection(val width: Int, val height: Int, val values: FloatArray, val distanceM: Double, val topM: Double, val tilts: Int)

/** Browsing NOAA's NEXRAD archive for one radar and day (UTC). */
data class ArchiveUi(
    val site: RadarSite,
    val date: LocalDate,
    val keys: List<String> = emptyList(),
    /** Loaded scan, or null while choosing. */
    val index: Int? = null,
    val loading: String? = null,
    val error: String? = null,
) {
    fun label(i: Int): String = keys.getOrNull(i)?.substringAfterLast('/')?.let { n ->
        Regex("""(\d{8})_(\d{2})(\d{2})(\d{2})""").find(n)?.let { "${it.groupValues[2]}:${it.groupValues[3]}" }
    } ?: "?"
}

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
    private val glm = GlmLightning(http)
    private val mrmsTracks = MrmsTracks(http)

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
            stormModeOn = checker.enabled && checker.allPlaces().isNotEmpty(),
            stormModeEnabled = checker.enabled,
            homeLabel = checker.homeLabel(),
            euMinLevel = checker.euMinLevel,
            showWarnings = prefs.getBoolean("show_warnings", true),
            satellite = prefs.getBoolean("satellite", true),
            units = prefs.getString("units", null)?.let { runCatching { UnitSystem.valueOf(it) }.getOrNull() }
                ?: UnitSystem.forCountry(Locale.getDefault().country),
            redNight = prefs.getBoolean("red_night", false),
            places = checker.allPlaces(),
            rainWifiOnly = checker.store.rainOnWifiOnly,
            lightning = prefs.getBoolean("lightning", false),
            showCells = prefs.getBoolean("cells", true),
            trackOverlay = prefs.getString("track_overlay", null)?.let { runCatching { MrmsTracks.Product.valueOf(it) }.getOrNull() },
            simple = prefs.getBoolean("simple_mode", false),
            styleAsked = prefs.getBoolean("style_asked", false),
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
    private var loopLoadJob: Job? = null
    private var compositeKeys: List<OrdKey> = emptyList()

    // map
    private var mapCenter: LatLon = LatLon(startCamera.lat, startCamera.lon)
    private var mapZoom = startCamera.zoom
    private var visible: LatLngBounds? = null
    private var alerts: List<Alert> = emptyList()
    private val euFeeds = HashMap<String, Pair<Long, List<WarningItem>>>() // country -> (fetched at, items)
    private var regions: WarningRegions? = null
    private var eumetsat: EumetsatLayers? = null

    // storm cells
    private var cellsTime = 0L
    private var cellSeq = 0

    // hail / rotation swaths
    private var overlayJob: Job? = null
    private var overlaySwath: MrmsTracks.Swath? = null

    // second pane
    private var frameJob2: Job? = null

    // archive
    private var archiveJob: Job? = null

    // lightning (GOES GLM, Americas)
    private var flashes: List<Flash> = emptyList()
    private val glmKnown = LinkedHashSet<String>()
    private var lightningJob: Job? = null

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
        if (_state.value.lightning) startLightning()
        _state.value.trackOverlay?.let { startTracks(it) }
        // Imagery URLs carry the time: refresh them every 5 minutes.
        viewModelScope.launch { while (isActive) { delay(300_000); updateImagery() } }
        // Note when each new latest picture arrives, to show observed vs. received times.
        viewModelScope.launch {
            var lastEnd: Long? = null
            state.collect { st ->
                val end = st.frameEndMillis
                if (end == null) {
                    if (lastEnd != null) { lastEnd = null; _state.update { it.copy(receivedMillis = null) } }
                } else if (st.showingLatest && (lastEnd == null || end > lastEnd!!)) {
                    lastEnd = end
                    _state.update { it.copy(receivedMillis = System.currentTimeMillis()) }
                }
            }
        }
        // Rain forecast for here: every 2 minutes while the app is on screen.
        viewModelScope.launch { while (isActive) { if (foreground) refreshRain(); delay(120_000) } }
    }

    // ---- rain forecast -------------------------------------------------------------

    private val rainOutlook = RainOutlook(app, RainSource(http))
    private var rainJob: Job? = null
    @Volatile private var foreground = true

    /** The app came to the screen or left it: forecasts only refresh while visible. */
    fun setForeground(on: Boolean) {
        foreground = on
        if (on) refreshRain()
    }

    /** Where the headline forecast is for: your device, else Storm Mode's place, else your first saved place. */
    private fun rainTarget(): Pair<String, LatLon>? {
        _state.value.me?.let { return tr("Your location") to it }
        checker.places().firstOrNull()?.let { (name, p) -> return (checker.homeLabel() ?: name) to p }
        checker.store.saved().firstOrNull()?.let { return it.name to LatLon(it.lat, it.lon) }
        return null
    }

    fun refreshRain() {
        if (rainJob?.isActive == true) return
        val target = rainTarget()
        if (target == null) {
            _state.update { it.copy(rain = RainCard(RainCardStatus.NEED_LOCATION)) }
            return
        }
        val (label, point) = target
        rainJob = viewModelScope.launch {
            if (_state.value.rain.forecast == null || _state.value.rain.place != label) {
                _state.update { it.copy(rain = RainCard(RainCardStatus.LOADING, label)) }
            }
            val card = try {
                when (val r = rainOutlook.forecast(point)) {
                    is RainOutlook.Result.Ready ->
                        RainCard(RainCardStatus.READY, label, r.forecast, System.currentTimeMillis())
                    RainOutlook.Result.NoCoverage -> RainCard(RainCardStatus.NO_COVERAGE, label)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Network or memory trouble: re-judge the last forecast at the current time (its
                // confidence falls as it ages, to none after 45 min), else say it's unavailable.
                val last = _state.value.rain.takeIf { it.forecast != null && it.place == label }
                val again = last?.let { runCatching { rainOutlook.reanalyse() }.getOrNull() }
                if (last != null && again != null) last.copy(forecast = again) else RainCard(RainCardStatus.ERROR, label)
            }
            _state.update { it.copy(rain = card) }
        }
    }

    // ---- simple mode -----------------------------------------------------------------

    /** Simple mode: rain only, plain words; advanced radar controls hidden but one switch away. */
    fun setSimple(on: Boolean) {
        prefs.edit().putBoolean("simple_mode", on).putBoolean("style_asked", true).apply()
        _state.update { it.copy(simple = on, styleAsked = true) }
        if (on) {
            if (_state.value.product != Product.REF) selectProduct(Product.REF)
            if (_state.value.dualPane) setDualPane(false)
        }
        rebuildOverlays()
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
        _state.update { it.copy(me = p) }
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
        refreshRain()
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
        if (_state.value.measure != null || _state.value.lightning || _state.value.places.isNotEmpty()) rebuildOverlays()
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
        if (s.archive?.index != null) return // browsing the archive: stay on that radar
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
                        if (it.satellite) tr("No open radar here. Showing infrared satellite") else tr("No open radar data for this area"),
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
        if (mode != Mode.SITE && mode != Mode.COMPOSITE) { cellsTime = 0; _state.update { it.copy(cells = emptyList()) } }
        if (mode != Mode.SITE) _state.update { it.copy(dualPane = false, siteLayer2 = null) }
        updateImagery()
    }

    /** Satellite where there's no radar; the NWS mosaic in MOSAIC mode. */
    private fun updateImagery() {
        val s = _state.value
        val list = mutableListOf<Pair<ImagerySource, Float>>()
        if (s.satellite && s.mode == Mode.NONE) list += Imagery.satellites(eumetsat).map { it to 0.75f }
        if (s.mode == Mode.MOSAIC) list += Imagery.mrms(System.currentTimeMillis() / 300_000) to 0.9f
        if (s.lightning) list += Imagery.mtgLightning() to 0.95f
        _state.update { it.copy(imagery = list, attribution = attributionFor(it.mode, it.site), imageryMillis = System.currentTimeMillis()) }
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
        val lightning = if (_state.value.lightning) "Lightning: NOAA GOES GLM; contains modified EUMETSAT Meteosat data" else ""
        val eu = if (euFeeds.isNotEmpty() && mode != Mode.MOSAIC && site?.network != Network.NEXRAD) "Warnings: MeteoAlarm / EUMETNET" else ""
        return listOf(radar, eu, lightning).filter { it.isNotEmpty() }.joinToString(". ")
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

    // ---- places ----------------------------------------------------------------------

    private fun refreshPlaces() = _state.update { it.copy(places = checker.allPlaces(), rainWifiOnly = checker.store.rainOnWifiOnly) }

    /** Saves the map centre as a place and checks it straight away. */
    fun addPlace(name: String) {
        val c = mapCenter
        viewModelScope.launch {
            val region = if (Coverage.inUs(c)) null else withContext(Dispatchers.Default) { regions?.regionAt(c)?.code }
            checker.store.add(name.trim(), c.lat, c.lon, region)
            refreshPlaces()
            rebuildOverlays()
            runCheckSoon()
        }
    }

    fun removePlace(id: Long) {
        checker.store.remove(id)
        refreshPlaces(); rebuildOverlays()
    }

    fun setPlaceWarnings(id: Long, on: Boolean) {
        if (id == 0L) checker.store.currentWarnings = on else checker.store.update(id) { it.copy(warnings = on) }
        refreshPlaces()
    }

    fun setPlaceRain(id: Long, on: Boolean) {
        if (id == 0L) checker.store.currentRain = on else checker.store.update(id) { it.copy(rain = on) }
        refreshPlaces()
        if (on) runCheckSoon()
    }

    fun setRainWifiOnly(on: Boolean) {
        checker.store.rainOnWifiOnly = on
        refreshPlaces()
    }

    fun showPlace(p: Place) = flyTo(p.lat, p.lon, 8.0)

    /** Runs the Storm Mode check now (warnings, rain outlook, widget) and refreshes the list after. */
    private fun runCheckSoon() {
        androidx.work.WorkManager.getInstance(getApplication())
            .enqueue(androidx.work.OneTimeWorkRequestBuilder<app.hookline.radar.stormmode.StormModeWorker>().build())
        viewModelScope.launch { delay(20_000); refreshPlaces(); delay(40_000); refreshPlaces() }
    }

    private fun placeOutlines(o: MercatorProjector): List<Outline> = _state.value.places.map { p ->
        val pts = FloatArray(8)
        for ((i, b) in listOf(0.0, 90.0, 180.0, 270.0).withIndex()) {
            val (la, lo) = RadialGeometry.destination(p.lat, p.lon, b, 1_800.0 * (1 + mapZoomScale()))
            val (x, y) = o.fromLatLon(la, lo)
            pts[i * 2] = x.toFloat(); pts[i * 2 + 1] = y.toFloat()
        }
        Outline(pts, 0xFFE8E4DA.toInt(), 2.5f)
    }

    // ---- lightning ------------------------------------------------------------------------

    fun setLightning(on: Boolean) {
        prefs.edit().putBoolean("lightning", on).apply()
        _state.update { it.copy(lightning = on, lightningInfo = null) }
        if (on) startLightning() else {
            lightningJob?.cancel(); lightningJob = null
            flashes = emptyList(); glmKnown.clear()
            rebuildOverlays()
        }
        updateImagery()
    }

    private fun startLightning() {
        if (lightningJob?.isActive == true) return
        lightningJob = viewModelScope.launch {
            var bucket: String? = null
            while (isActive) {
                val b = glm.bucketFor(mapCenter)
                if (b != bucket) { flashes = emptyList(); glmKnown.clear(); bucket = b }
                if (b != null) {
                    val now = System.currentTimeMillis()
                    try {
                        val since = maxOf(now - LIGHTNING_WINDOW, (flashes.maxOfOrNull { it.timeMillis } ?: 0L) - 20_000)
                        val (fresh, keys) = glm.fetchNew(b, since, glmKnown, maxFiles = if (glmKnown.isEmpty()) 15 else 6)
                        glmKnown += keys
                        while (glmKnown.size > 200) glmKnown.remove(glmKnown.first())
                        flashes = (flashes + fresh).filter { now - it.timeMillis < LIGHTNING_WINDOW }.takeLast(4_000)
                        _state.update { it.copy(lightningInfo = if (b.endsWith("18")) tr("%s flashes in the last 15 min (GOES West)", flashes.size) else tr("%s flashes in the last 15 min (GOES East)", flashes.size)) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        _state.update { it.copy(lightningInfo = tr("Lightning not updating: %s", e.message)) }
                    }
                } else {
                    _state.update {
                        it.copy(lightningInfo = if (Coverage.inOperaComposite(mapCenter) || mapCenter.lon in -20.0..60.0) tr("Lightning from Meteosat (may lag a few minutes)") else tr("No lightning data here"))
                    }
                }
                rebuildOverlays()
                delay(60_000)
            }
        }
    }

    /** "+" marks, brightest for the newest flashes. */
    private fun lightningOutlines(o: MercatorProjector): List<Outline> {
        if (flashes.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        val r = 1_500.0 * (1 + mapZoomScale())
        val out = ArrayList<Outline>(flashes.size * 2)
        for (f in flashes) {
            val age = now - f.timeMillis
            val argb = when {
                age < 2 * 60_000 -> 0xFFFFFFFF.toInt()
                age < 5 * 60_000 -> 0xFFFFE14D.toInt()
                age < 10 * 60_000 -> 0xFFF2902B.toInt()
                else -> 0xB3C0392B.toInt()
            }
            val lat = f.lat.toDouble(); val lon = f.lon.toDouble()
            val (n0, n1) = o.fromLatLon(RadialGeometry.destination(lat, lon, 0.0, r).first, lon)
            val (s0, s1) = o.fromLatLon(RadialGeometry.destination(lat, lon, 180.0, r).first, lon)
            val e = RadialGeometry.destination(lat, lon, 90.0, r); val w = RadialGeometry.destination(lat, lon, 270.0, r)
            val (e0, e1) = o.fromLatLon(e.first, e.second); val (w0, w1) = o.fromLatLon(w.first, w.second)
            out += Outline(floatArrayOf(n0.toFloat(), n1.toFloat(), s0.toFloat(), s1.toFloat()), argb, 2f, closed = false)
            out += Outline(floatArrayOf(e0.toFloat(), e1.toFloat(), w0.toFloat(), w1.toFloat()), argb, 2f, closed = false)
        }
        return out
    }

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
            it.copy(
                stormModeEnabled = on, stormModeOn = on && checker.allPlaces().isNotEmpty(), homeLabel = checker.homeLabel(),
                places = checker.allPlaces(),
            )
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
        inspectBase(at)
        overlayText(at.latitude, at.longitude)?.let { extra ->
            _state.update { st -> st.copy(inspector = st.inspector?.let { it.copy(meaning = it.meaning + "\n" + extra) } ?: Inspection(tr("Swath"), extra, "")) }
        }
    }

    private fun inspectBase(at: LatLng) {
        val s = _state.value
        if (s.mode == Mode.SITE) {
            val tex = currentTexture ?: return
            val loc = siteLocation ?: return
            val (brg, dist) = MercatorProjector.bearingDistance(loc.lat, loc.lon, at.latitude, at.longitude)
            val v = tex.valueAtPolar(brg, dist)
            val u = s.units
            val height = tr("%s above the radar, %s out", Fmt.height(RadialGeometry.beamHeightMeters(dist, tex.elevationDeg.toDouble()), u), Fmt.distance(dist, u))
            _state.update { it.copy(inspector = describe(it.product, v, height)) }
        } else if (s.mode == Mode.COMPOSITE) {
            val g = compositeGrid ?: return
            val loc = compositeLocator ?: return
            val v = CompositeTile.valueAt(g, loc, at.latitude, at.longitude)
            val inspection = if (v.isNaN()) {
                Inspection(tr("No data"), tr("This spot isn't covered by the European radar network."), tr("Europe composite"))
            } else {
                describe(Product.REF, if (v.isInfinite()) null else v, tr("Highest echo above this spot, from all nearby radars"))
            }
            _state.update { it.copy(inspector = inspection) }
        } else if (s.mode == Mode.MOSAIC) {
            _state.update {
                it.copy(
                    inspector = Inspection(
                        tr("Zoom in for values"),
                        tr("Zoomed out, you see NOAA's national mosaic picture. Zoom in and Hookline switches to the nearest radar's own data."),
                        tr("US radar mosaic"),
                    ),
                )
            }
        } else {
            _state.update {
                it.copy(
                    inspector = Inspection(
                        tr("No radar here"),
                        if (it.satellite) tr("No open radar covers this spot. The grey and white shapes are clouds seen in infrared by satellite: the whiter, the colder and higher the cloud tops.")
                        else tr("No open radar covers this spot."),
                        tr("Satellite infrared"),
                    ),
                )
            }
        }
    }

    fun downloadVisibleMap(bounds: LatLngBounds, pixelRatio: Float) {
        offline.download(bounds, _state.value.site?.name?.let { tr("%s area", it) } ?: tr("Saved area"), pixelRatio)
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
        cellsTime = 0
        _state.update { it.copy(cells = emptyList(), siteLayer2 = null, crossSection = null) }
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
        e.snapshot.sweep(lt, Moment.REFLECTIVITY)?.let { cellsFromSweep(it) }
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
            setLoopLoading(tr("Loading loop…"))
            try {
                val frames = if (site.network == Network.OPERA) ord.loopFrames(site, wanted - 1) else backfill.frames(site.id, volume, wanted - 1)
                frames.collect { f ->
                    got++
                    addToLoop(f.reflectivity, f.velocity)
                    setLoopLoading(tr("Loading loop %s/%s", got, wanted - 1))
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
                        if (compositeGrid == null) _state.update { it.copy(status = FeedStatus.Stale(tr("No recent European composite"))) }
                    } else if (newest.key != compositeKey?.key) {
                        val bytes = opera.fetchBytes(newest)
                        val grid = withContext(Dispatchers.Default) { OperaComposite.decode(bytes) }
                        compositeGrid = grid
                        compositeLocator = withContext(Dispatchers.Default) { CompositeTile.Locator(grid) }
                        compositeKey = newest
                        compositeBytes.clear()
                        // Remember the other recent frames' keys for loops (downloaded on play, or ahead of time on Wi-Fi).
                        compositeKeys = keys
                        _state.update { it.copy(status = FeedStatus.Live) }
                        cellsFromComposite(grid)
                        refreshCompositeTile(force = true)
                        refreshLoopUi()
                        maybePrefetchLoop()
                    }
                    attempt = 0
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    attempt++
                    _state.update { it.copy(status = FeedStatus.Stale(e.message ?: tr("Can't reach European radar data"))) }
                } catch (e: OutOfMemoryError) {
                    attempt++
                    compositeBytes.clear()
                    _state.update { it.copy(status = FeedStatus.Stale(tr("Not enough memory for the European composite"))) }
                }
                delay(if (attempt == 0) 120_000L else (15_000L * attempt).coerceAtMost(120_000L))
            }
        }
    }

    private fun stopComposite() {
        compositeJob?.cancel()
        compositeJob = null
        loopLoadJob?.cancel(); loopLoadJob = null
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
        val previousBox = compositeBounds
        compositeTileJob?.cancel()
        compositeTileJob = viewModelScope.launch {
            val layer = withContext(Dispatchers.Default) {
                CompositeTile.build(grid, loc, box[0], box[1], box[2], box[3], 2048, origin.siteLat, origin.siteLon, true)
                    ?.let { compositeLayer(it) }
            }
            val sameArea = previousBox != null && previousBox.contentEquals(box)
            compositeBounds = box
            compositeZoom = mapZoom
            if (!sameArea) {
                // Loop frames were cut for the old area.
                loopLoadJob?.cancel(); loopLoadJob = null
                compositeLoopLayers = emptyMap()
            }
            maybePrefetchLoop()
            if (_state.value.mode == Mode.COMPOSITE && _state.value.showingLatest) {
                _state.update { it.copy(compositeLayer = layer, frameEndMillis = grid.endMillis) }
            }
        }
    }

    private fun compositeLayer(t: CompositeTile) = RasterLayer(
        t.texWidth, t.texHeight, t.values, t.mesh, Product.REF.palette, Product.REF.min, Product.REF.max,
    )

    /**
     * Loads the composite loop for the current view in the background: three
     * frames at a time, newest first. Frames appear in the loop as they are
     * ready, so playback can start long before the last one arrives. The
     * newest frame reuses the composite already in memory.
     */
    private fun startCompositeLoopLoad(): Job? {
        val loc = compositeLocator ?: return null
        val box = compositeBounds ?: return null
        val origin = _state.value.origin
        val keys = compositeKeys.sortedByDescending { it.timeMillis }
        if (keys.size < 2) return null
        val layers = java.util.concurrent.ConcurrentHashMap<Long, RasterLayer>(compositeLoopLayers)
        val wanted = keys.map { it.timeMillis }.toSet()
        layers.keys.retainAll(wanted) // frames that aged out of the loop window
        compositeLoopLayers = layers
        val missing = keys.filter { !layers.containsKey(it.timeMillis) }
        if (missing.isEmpty()) return null
        val gate = kotlinx.coroutines.sync.Semaphore(3)        // downloads in flight
        val decodeGate = kotlinx.coroutines.sync.Semaphore(2)  // full-size decodes in memory at once
        var lowMemory = false
        fun publish() {
            if (compositeLoopLayers !== layers) return
            val done = layers.size
            setLoopLoading(if (done < keys.size) tr("Loading loop %s/%s", done, keys.size) else null)
            if (_state.value.loop.playing) _state.update { it.copy(loop = it.loop.copy(frames = layers.keys.sorted())) }
        }
        return viewModelScope.launch {
            publish()
            missing.map { k ->
                launch {
                    gate.acquire()
                    try {
                        if (lowMemory) return@launch
                        val grid = compositeGrid?.takeIf { compositeKey?.key == k.key }
                        val bytes = if (grid == null) opera.fetchBytes(k) else null
                        decodeGate.acquire()
                        val layer = try {
                            withContext(Dispatchers.Default) {
                                val g = grid ?: OperaComposite.decode(bytes!!)
                                CompositeTile.build(g, loc, box[0], box[1], box[2], box[3], 1024, origin.siteLat, origin.siteLon, true)?.let { compositeLayer(it) }
                            }
                        } finally {
                            decodeGate.release()
                        }
                        if (layer != null) { layers[k.timeMillis] = layer; publish() }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: OutOfMemoryError) {
                        lowMemory = true // loop what we have rather than crash
                    } catch (e: Exception) {
                        // A missing frame just leaves a gap in the loop.
                    } finally {
                        gate.release()
                    }
                }
            }.forEach { it.join() }
            if (compositeLoopLayers === layers) setLoopLoading(null)
        }
    }

    /** On Wi-Fi, get the loop ready while the user looks at the map, so Play starts instantly. */
    private fun maybePrefetchLoop() {
        if (_state.value.mode != Mode.COMPOSITE || loopLoadJob?.isActive == true) return
        val cm = getApplication<Application>().getSystemService(android.net.ConnectivityManager::class.java) ?: return
        if (cm.isActiveNetworkMetered) return
        loopLoadJob = startCompositeLoopLoad()
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
            if (_state.value.mode == Mode.COMPOSITE) {
                if (loopLoadJob?.isActive != true && compositeLoopLayers.size < compositeKeys.size) loopLoadJob = startCompositeLoopLoad()
                // Start as soon as a few frames are in; the rest join the loop as they arrive.
                val enough = minOf(3, compositeKeys.size)
                while (isActive && compositeLoopLayers.size < enough && loopLoadJob?.isActive == true) delay(100)
                if (compositeLoopLayers.size < 2) { setLoopLoading(null); stopLoop(); return@launch }
                _state.update { it.copy(loop = it.loop.copy(frames = compositeLoopLayers.keys.sorted())) }
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
        if (s.loop.index == null || !s.loop.available) {
            val snap = snapshot ?: return
            val available = Product.entries.filter { snap.sweep(s.tilt, it.moment) != null || dualPolSweep(s.tilt, it.moment) != null }.toSet()
            val product = if (s.product in available || available.isEmpty()) s.product else Product.REF
            if (product != s.product || available != s.availableProducts) {
                _state.update { it.copy(product = product, availableProducts = available.ifEmpty { setOf(Product.REF) }) }
            }
        }
        val st = _state.value
        textureBuilder(st.product, st)?.let { render(st.product, st.origin, loc, it, second = false) }
        if (st.dualPane) {
            textureBuilder(st.product2, st)?.let { render(st.product2, st.origin, loc, it, second = true) }
        } else if (st.siteLayer2 != null) {
            _state.update { it.copy(siteLayer2 = null) }
        }
    }

    /**
     * How to build the texture for [p] right now: the loop frame on screen
     * (REF and velocity frames are matched by time), or the live sweep.
     */
    private fun textureBuilder(p: Product, s: MapUiState): (() -> SweepTexture?)? {
        val idx = s.loop.index
        val buffer = loopFor(p)
        val m = s.motion
        if (idx != null && s.loop.available && buffer != null) {
            val key = s.loop.frames.getOrNull(idx) ?: return null
            val compact = (buffer.floorEntry(key) ?: buffer.ceilingEntry(key))?.value ?: return null
            return {
                if (p == Product.SRV) compact.toTexture { az, v -> v - StormRelative.component(az, m.fromDeg.toFloat(), m.speedKt * 0.514444f) }
                else compact.toTexture()
            }
        }
        val snap = snapshot ?: return null
        val sweep = snap.sweep(s.tilt, p.moment) ?: dualPolSweep(s.tilt, p.moment) ?: return null // tilt still downloading
        return {
            val srv: ((Radial, MomentData) -> MomentData)? = if (p == Product.SRV) {
                { r, md -> StormRelative.apply(r, m.fromDeg.toFloat(), m.speedKt * 0.514444f) ?: md }
            } else null
            SweepTexture.from(sweep, p.moment, srv)
        }
    }

    private fun render(product: Product, origin: MercatorProjector, radar: LatLon, build: () -> SweepTexture?, second: Boolean = false) {
        if (second) frameJob2?.cancel() else frameJob?.cancel()
        val job = viewModelScope.launch {
            val built = withContext(Dispatchers.Default) {
                val tex = build() ?: return@withContext null
                tex to RasterLayer(
                    tex.gateCount, tex.radialCount, tex.values, tex.wedgeMesh(origin, radar.lat, radar.lon),
                    product.palette, product.min, product.max,
                )
            } ?: return@launch
            if (second) {
                _state.update { it.copy(siteLayer2 = built.second) }
            } else {
                currentTexture = built.first
                _state.update { it.copy(siteLayer = built.second, frameEndMillis = built.first.endMillis) }
            }
        }
        if (second) frameJob2 = job else frameJob = job
    }

    fun setDualPane(on: Boolean) {
        _state.update { it.copy(dualPane = on && it.mode == Mode.SITE) }
        showCurrent()
    }

    // ---- storm cells ---------------------------------------------------------------------------

    fun setShowCells(on: Boolean) {
        prefs.edit().putBoolean("cells", on).apply()
        cellsTime = 0
        _state.update { it.copy(showCells = on, cells = emptyList()) }
        rebuildOverlays()
        if (on) snapshot?.sweep(loopTilt(), Moment.REFLECTIVITY)?.let { cellsFromSweep(it) }
        if (on) compositeGrid?.let { cellsFromComposite(it) }
    }

    /** Cells within 200 km of a single radar, 1 km grid, lowest tilt. */
    private fun cellsFromSweep(sweep: app.hookline.radar.core.radar.Sweep) {
        val radar = siteLocation ?: return
        updateCells {
            val tex = SweepTexture.from(sweep, Moment.REFLECTIVITY, null) ?: return@updateCells null
            LocalGrid.sample(radar, 1.0, 401, tex.endMillis) { lat, lon ->
                val (brg, dist) = MercatorProjector.bearingDistance(radar.lat, radar.lon, lat, lon)
                if (dist > 200_000) Float.NaN
                else tex.valueAtPolar(brg, dist)?.takeIf { it != Gate.RANGE_FOLDED && it > -50f } ?: 0f
            }
        }
    }

    /** Cells within 300 km of the map centre on the European composite, 2 km grid. */
    private fun cellsFromComposite(g: Odim.Grid) {
        val loc = compositeLocator ?: return
        val center = mapCenter
        updateCells {
            LocalGrid.sample(center, 2.0, 301, g.endMillis) { lat, lon ->
                val v = CompositeTile.valueAt(g, loc, lat, lon)
                if (v.isNaN()) Float.NaN else if (v.isInfinite()) 0f else v
            }
        }
    }

    private fun updateCells(build: () -> LocalGrid?) {
        if (!_state.value.showCells) return
        viewModelScope.launch {
            val grid = withContext(Dispatchers.Default) { runCatching { build() }.getOrNull() } ?: return@launch
            if (grid.timeMillis <= cellsTime) return@launch
            val found = withContext(Dispatchers.Default) { StormCells.detect(grid, 40f, minCells = if (grid.cellKm < 1.5) 8 else 3) }
            val minutes = if (cellsTime == 0L) 0.0 else (grid.timeMillis - cellsTime) / 60_000.0
            val tracked = StormCells.track(_state.value.cells, found, minutes) { ++cellSeq }
            cellsTime = grid.timeMillis
            _state.update { it.copy(cells = tracked) }
            rebuildOverlays()
        }
    }

    /** A ring per cell, coloured by strength, with its path to +30 minutes. */
    private fun cellOutlines(o: MercatorProjector): List<Outline> {
        val out = mutableListOf<Outline>()
        for (c in _state.value.cells) {
            val argb = when {
                c.maxDbz >= 60 -> 0xFFE040FB.toInt()
                c.maxDbz >= 55 -> 0xFFE5312B.toInt()
                c.maxDbz >= 50 -> 0xFFF2902B.toInt()
                else -> 0xFFF2D23B.toInt()
            }
            val radius = (kotlin.math.sqrt(c.areaKm2 / Math.PI) + 3).coerceIn(4.0, 25.0) * 1000
            val ring = FloatArray(48)
            for (i in 0 until 24) {
                val (la, lo) = RadialGeometry.destination(c.lat, c.lon, i * 15.0, radius)
                val (x, y) = o.fromLatLon(la, lo)
                ring[i * 2] = x.toFloat(); ring[i * 2 + 1] = y.toFloat()
            }
            out += Outline(ring, argb, 2.5f)
            val end = c.ahead(30.0) ?: continue
            val (x0, y0) = o.fromLatLon(c.lat, c.lon)
            val mid = c.ahead(15.0)!!
            val (x1, y1) = o.fromLatLon(mid.lat, mid.lon)
            val (x2, y2) = o.fromLatLon(end.lat, end.lon)
            out += Outline(floatArrayOf(x0.toFloat(), y0.toFloat(), x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat()), argb, 2f, closed = false)
            // Tick marks at 15 and 30 minutes.
            for (p in listOf(mid, end)) {
                val mark = FloatArray(8)
                for ((j, b) in listOf(0.0, 90.0, 180.0, 270.0).withIndex()) {
                    val (la, lo) = RadialGeometry.destination(p.lat, p.lon, b, 1_200.0 * (1 + mapZoomScale()))
                    val (mx, my) = o.fromLatLon(la, lo)
                    mark[j * 2] = mx.toFloat(); mark[j * 2 + 1] = my.toFloat()
                }
                out += Outline(mark, argb, 2f)
            }
        }
        return out
    }

    fun showCell(c: StormCell) = flyTo(c.lat, c.lon, 9.0)

    // ---- hail and rotation swaths (US) ---------------------------------------------------------------

    fun setTrackOverlay(p: MrmsTracks.Product?) {
        prefs.edit().putString("track_overlay", p?.name).apply()
        overlayJob?.cancel(); overlayJob = null; overlaySwath = null
        _state.update { it.copy(trackOverlay = p, overlayLayer = null, overlayInfo = if (p == null) null else tr("Loading %s…", midSentence(trackLabel(p)))) }
        p?.let { startTracks(it) }
    }

    private fun startTracks(p: MrmsTracks.Product) {
        overlayJob = viewModelScope.launch {
            while (isActive) {
                try {
                    val sw = mrmsTracks.latest(p)
                    val origin = _state.value.origin
                    val layer = withContext(Dispatchers.Default) { swathLayer(sw, origin, p) }
                    overlaySwath = sw
                    _state.update { it.copy(overlayLayer = layer, overlayInfo = tr("%s, until %s", trackLabel(p), clock(sw.timeMillis))) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.update { it.copy(overlayInfo = tr("%s not available: %s", trackLabel(p), e.message)) }
                }
                delay(p.refreshMs)
            }
        }
    }

    private fun swathLayer(sw: MrmsTracks.Swath, origin: MercatorProjector, p: MrmsTracks.Product): RasterLayer {
        val values = FloatArray(sw.values.size) { i -> sw.values[i].let { if (it.isNaN()) CompositeTile.BELOW else it } }
        // Lat/lon grid → Mercator: a 64 × 32 quad mesh keeps the curvature right.
        val nx = 64; val ny = 32
        val mesh = FloatArray(nx * ny * 6 * 4)
        var k = 0
        fun vertex(u: Double, v: Double) {
            val lat = sw.north - v * (sw.north - sw.south)
            val lon = sw.west + u * (sw.east - sw.west)
            val (x, y) = origin.fromLatLon(lat, lon)
            mesh[k++] = x.toFloat(); mesh[k++] = y.toFloat(); mesh[k++] = u.toFloat(); mesh[k++] = v.toFloat()
        }
        for (j in 0 until ny) for (i in 0 until nx) {
            val u0 = i.toDouble() / nx; val u1 = (i + 1).toDouble() / nx
            val v0 = j.toDouble() / ny; val v1 = (j + 1).toDouble() / ny
            vertex(u0, v0); vertex(u1, v0); vertex(u0, v1)
            vertex(u1, v0); vertex(u1, v1); vertex(u0, v1)
        }
        return when (p) {
            MrmsTracks.Product.HAIL_24H -> RasterLayer(sw.width, sw.height, values, mesh, HAIL_PALETTE, 0f, 100f)
            MrmsTracks.Product.ROTATION_60M -> RasterLayer(sw.width, sw.height, values, mesh, ROTATION_PALETTE, 0f, 0.03f)
        }
    }

    private fun overlayText(lat: Double, lon: Double): String? {
        val sw = overlaySwath ?: return null
        val p = _state.value.trackOverlay ?: return null
        val v = sw.valueAt(lat, lon)
        if (v.isNaN()) return null
        return when (p) {
            MrmsTracks.Product.HAIL_24H -> {
                val size = if (_state.value.units == app.hookline.radar.core.data.UnitSystem.IMPERIAL) "%.1f in".format(Locale.US, v / 25.4) else "%.1f cm".format(Locale.US, v / 10)
                tr("Radar-estimated hail up to %s here in the last 24 hours.", size)
            }
            MrmsTracks.Product.ROTATION_60M -> if (v >= 0.01) tr("Rotation of %s /s detected here in the last hour (strong).", "%.3f".format(Locale.US, v))
                else tr("Rotation of %s /s detected here in the last hour.", "%.3f".format(Locale.US, v))
        }
    }

    // ---- cross-section ---------------------------------------------------------------------------------------

    /** Slices the current volume along the measure line (reflectivity, 0–15 km). */
    fun makeCrossSection() {
        val s = _state.value
        val m = s.measure ?: return
        val a = m.a ?: return; val b = m.b ?: return
        if (s.mode != Mode.SITE) {
            _state.update { it.copy(crossSectionNote = tr("Cross-sections need a single radar: zoom in near one.")) }
            return
        }
        val snap = snapshot ?: return
        val radar = siteLocation ?: return
        // European radars load tilts on demand: ask for all of them.
        if (s.site?.network == Network.OPERA) wantedTilts.value = snap.tilts.toSet()
        _state.update { it.copy(crossSectionNote = tr("Building cross-section…")) }
        viewModelScope.launch {
            val cs = withContext(Dispatchers.Default) {
                val textures = snap.tilts.mapNotNull { t -> snap.sweep(t, Moment.REFLECTIVITY)?.let { SweepTexture.from(it, Moment.REFLECTIVITY, null) } }
                    .sortedBy { it.elevationDeg }
                if (textures.isEmpty()) return@withContext null
                val (brg, dist) = MercatorProjector.bearingDistance(a.lat, a.lon, b.lat, b.lon)
                val w = 320; val h = 140; val top = 15_000.0
                val out = FloatArray(w * h) { Float.NaN }
                for (i in 0 until w) {
                    val (la, lo) = RadialGeometry.destination(a.lat, a.lon, brg, dist * (i + 0.5) / w)
                    val (rb, rd) = MercatorProjector.bearingDistance(radar.lat, radar.lon, la, lo)
                    val centers = textures.map { RadialGeometry.beamHeightMeters(rd, it.elevationDeg.toDouble()) }
                    val half = rd * Math.tan(Math.toRadians(0.5)) + 150
                    for (j in 0 until h) {
                        val z = (h - j - 0.5) / h * top
                        var best = -1; var bestD = Double.MAX_VALUE
                        for (t in centers.indices) {
                            val d = kotlin.math.abs(centers[t] - z)
                            if (d < bestD) { bestD = d; best = t }
                        }
                        // Fill between neighbouring beams, but not above the top beam or below the lowest.
                        val gap = if (textures.size > 1) {
                            val upper = centers.getOrNull(best + 1); val lower = centers.getOrNull(best - 1)
                            maxOf(half, listOfNotNull(upper, lower).maxOfOrNull { kotlin.math.abs(it - centers[best]) / 2 } ?: half)
                        } else half
                        if (best < 0 || bestD > gap) continue
                        val v = textures[best].valueAtPolar(rb, rd)
                        out[j * w + i] = when {
                            v == null -> CompositeTile.BELOW
                            v == Gate.RANGE_FOLDED -> Float.NaN
                            else -> v
                        }
                    }
                }
                CrossSection(w, h, out, dist, top, textures.size)
            }
            _state.update {
                it.copy(
                    crossSection = cs,
                    crossSectionNote = if (cs == null) tr("No reflectivity volume yet.") else if (cs.tilts < 4) tr("Only %s tilts loaded so far; more appear as they download.", cs.tilts) else null,
                )
            }
        }
    }

    fun closeCrossSection() = _state.update { it.copy(crossSection = null, crossSectionNote = null) }

    // ---- archive (US NEXRAD) ------------------------------------------------------------------------------------

    /** Opens the archive browser for the current NEXRAD radar (or the nearest one). */
    fun openArchive() {
        val s = _state.value
        val site = s.site?.takeIf { it.network == Network.NEXRAD }
            ?: Sites.byDistance(mapCenter).firstOrNull { it.network == Network.NEXRAD } ?: return
        _state.update { it.copy(archive = ArchiveUi(site, LocalDate.now(java.time.ZoneOffset.UTC).minusDays(1))) }
        archiveSetDate(_state.value.archive!!.date)
    }

    fun archiveSetSite(site: RadarSite) {
        val a = _state.value.archive ?: return
        if (site.network != Network.NEXRAD) return
        _state.update { it.copy(archive = a.copy(site = site, keys = emptyList(), index = null)) }
        archiveSetDate(a.date)
    }

    fun archiveSetDate(date: LocalDate) {
        val a = _state.value.archive ?: return
        archiveJob?.cancel()
        _state.update { it.copy(archive = a.copy(date = date, keys = emptyList(), index = null, loading = tr("Listing scans…"), error = null)) }
        archiveJob = viewModelScope.launch {
            try {
                val keys = nexrad.archiveKeys(a.site.id, date)
                    .filter { k -> val n = k.substringAfterLast('/'); n.startsWith(a.site.id) && !n.contains("_MDM") }
                    .sorted()
                _state.update { it.copy(archive = it.archive?.copy(keys = keys, loading = null, error = if (keys.isEmpty()) tr("No scans stored for this day.") else null)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(archive = it.archive?.copy(loading = null, error = tr("Couldn't list the archive: %s", e.message))) }
            }
        }
    }

    /** Loads one archived volume (about 5–10 MB) and shows it instead of live data. */
    fun archiveLoad(index: Int) {
        val a = _state.value.archive ?: return
        val key = a.keys.getOrNull(index) ?: return
        archiveJob?.cancel()
        stopLoop()
        streamJob?.cancel(); backfillJob?.cancel(); dualPolJob?.cancel()
        _state.update { it.copy(archive = a.copy(index = index, loading = tr("Downloading %s UTC…", a.label(index)), error = null)) }
        archiveJob = viewModelScope.launch {
            try {
                val raw = nexrad.fetchArchive(key)
                val snap = withContext(Dispatchers.Default) {
                    val bytes = if (raw.size > 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()) {
                        java.util.zip.GZIPInputStream(raw.inputStream()).readBytes()
                    } else raw
                    val asm = VolumeAssembler()
                    asm.accept(1, app.hookline.radar.core.radar.Level2Decoder.decodeRadials(bytes))
                    asm.snapshot()
                }
                if (snap.sweeps.isEmpty()) throw java.io.IOException(tr("This scan uses an older format Hookline can't read (before 2008)."))
                snapshot = snap
                currentTexture = null
                refLoop.clear(); velLoop.clear()
                siteLocation = snap.site?.let { LatLon(it.latitude.toDouble(), it.longitude.toDouble()) } ?: a.site.location
                cellsTime = 0
                _state.update {
                    it.copy(
                        mode = Mode.SITE, site = a.site, autoSite = false, status = FeedStatus.Live,
                        tilts = snap.tilts, tilt = snap.tilts.firstOrNull() ?: 0.5f,
                        loop = LoopUi(available = false), cells = emptyList(),
                        archive = it.archive?.copy(loading = null),
                        attribution = attributionFor(Mode.SITE, a.site),
                    )
                }
                setModeSilently()
                rebuildOverlays()
                showCurrent()
                snap.sweep(snap.tilts.firstOrNull() ?: 0.5f, Moment.REFLECTIVITY)?.let { cellsFromSweep(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(archive = it.archive?.copy(loading = null, error = e.message ?: tr("Couldn't load this scan"))) }
            }
        }
    }

    /** Leaving the composite/mosaic when an archived scan opens. */
    private fun setModeSilently() {
        stopComposite()
        _state.update { it.copy(compositeLayer = null, imagery = emptyList()) }
    }

    fun archiveStep(delta: Int) {
        val a = _state.value.archive ?: return
        val i = (a.index ?: return) + delta
        if (i in a.keys.indices) archiveLoad(i)
    }

    fun exitArchive() {
        val a = _state.value.archive
        archiveJob?.cancel()
        _state.update { it.copy(archive = null) }
        if (a?.index != null) {
            prefs.edit().putBoolean("auto_site", true).apply()
            _state.update { it.copy(autoSite = true) }
            snapshot = null
            _state.update { it.copy(site = null, siteLayer = null) }
            a.site.let { startSite(it) }
        }
        decideSource()
        updateImagery()
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
            it.copy(motion = best?.let { (a, m, _) -> MotionInfo(m.fromDeg, m.speedKt, tr("From the %s", a.event.lowercase())) } ?: MapUiState.DEFAULT_MOTION)
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
        list += placeOutlines(o)
        if (_state.value.lightning) list += lightningOutlines(o)
        if (_state.value.showCells && !_state.value.simple) list += cellOutlines(o)
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
        if (v == null) return Inspection(tr("No echo"), tr("Nothing above the radar's detection threshold here."), height)
        if (v == Gate.RANGE_FOLDED) return Inspection(
            tr("Range folded"), tr("The radar can't tell how far away this echo is. Try another tilt."), height,
        )
        return when (p) {
            Product.REF -> Inspection(
                if (_state.value.simple) when {
                    v < 30 -> tr("Light rain")
                    v < 40 -> tr("Moderate rain")
                    v < 50 -> tr("Heavy rain")
                    v < 60 -> tr("Very heavy rain")
                    else -> tr("Hail likely")
                } else "${v.roundToInt()} dBZ",
                when {
                    v < 20 -> tr("Light rain, drizzle or snow.")
                    v < 40 -> tr("Moderate rain.")
                    v < 50 -> tr("Heavy rain.")
                    v < 60 -> tr("Very heavy rain; hail possible.")
                    else -> tr("Hail likely.")
                },
                height,
            )
            Product.VEL, Product.SRV -> {
                val dir = if (v < 0) tr("toward the radar") else tr("away from the radar")
                Inspection(
                    Fmt.speed(abs(v).toDouble(), _state.value.units) + " " + dir,
                    if (abs(v) >= 25.7f) tr("Strong wind. Look for an opposite colour right next to it: that pair means rotation.") else tr("Wind moving %s.", dir),
                    height,
                )
            }
            Product.CC -> Inspection(
                "%.2f".format(Locale.US, v),
                when {
                    v < 0.8f -> tr("Non-rain targets: debris, large hail, birds or insects. A round drop inside a hook is a debris signature.")
                    v < 0.95f -> tr("Mixed precipitation or hail.")
                    else -> tr("Uniform rain or snow.")
                },
                height,
            )
            Product.ZDR -> Inspection(
                "%.1f dB".format(Locale.US, v),
                when {
                    v > 2.5f -> tr("Large, flattened raindrops.")
                    v > 0.5f -> tr("Rain.")
                    v > -0.5f -> tr("Round targets: hail, snow or drizzle.")
                    else -> tr("Vertically aligned ice crystals.")
                },
                height,
            )
        }
    }

    companion object {
        /** Zoom at which the map switches from the composite to the nearest single radar. */
        const val SITE_ZOOM = 6.0
        private const val LIGHTNING_WINDOW = 15 * 60_000L

        /** MESH hail size in mm: pea, quarter, golf ball, tennis ball, baseball and larger. */
        val HAIL_PALETTE = Palette(
            "mm",
            listOf(
                Palette.Stop(0f, 0, solid = true),
                Palette.Stop(6f, Palette.argb(200, 120, 200, 255), solid = true),
                Palette.Stop(19f, Palette.argb(220, 242, 210, 59), solid = true),
                Palette.Stop(25f, Palette.argb(230, 242, 144, 43), solid = true),
                Palette.Stop(44f, Palette.argb(240, 229, 49, 43), solid = true),
                Palette.Stop(64f, Palette.argb(250, 224, 64, 251), solid = true),
                Palette.Stop(100f, Palette.argb(255, 255, 255, 255), solid = true),
            ),
        )

        /** Azimuthal shear in 1/s: weak, moderate, strong, intense. */
        val ROTATION_PALETTE = Palette(
            "1/s",
            listOf(
                Palette.Stop(0f, 0, solid = true),
                Palette.Stop(0.004f, Palette.argb(170, 154, 165, 174), solid = true),
                Palette.Stop(0.007f, Palette.argb(220, 242, 210, 59), solid = true),
                Palette.Stop(0.01f, Palette.argb(235, 242, 144, 43), solid = true),
                Palette.Stop(0.015f, Palette.argb(245, 229, 49, 43), solid = true),
                Palette.Stop(0.02f, Palette.argb(255, 224, 64, 251), solid = true),
            ),
        )
        private const val RING_SEGMENTS = 120
        private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
        fun clock(millis: Long): String = TIME.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
    }
}
