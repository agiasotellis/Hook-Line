package app.hookline.radar.map

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.hookline.radar.BuildConfig
import app.hookline.radar.core.data.Alert
import app.hookline.radar.core.data.Hazard
import app.hookline.radar.core.data.LatLon
import app.hookline.radar.core.data.LiveEvent
import app.hookline.radar.core.data.LiveRadar
import app.hookline.radar.core.data.LiveSnapshot
import app.hookline.radar.core.data.LoopBackfill
import app.hookline.radar.core.data.NexradSource
import app.hookline.radar.core.data.NwsAlerts
import app.hookline.radar.core.data.RadarSite
import app.hookline.radar.core.data.Sites
import app.hookline.radar.core.data.StormMotion
import app.hookline.radar.core.data.Tilts
import app.hookline.radar.core.radar.CompactMoment
import app.hookline.radar.core.radar.Gate
import app.hookline.radar.core.radar.MercatorProjector
import app.hookline.radar.core.radar.Moment
import app.hookline.radar.core.radar.MomentData
import app.hookline.radar.core.radar.Palette
import app.hookline.radar.core.radar.Radial
import app.hookline.radar.core.radar.RadialGeometry
import app.hookline.radar.core.radar.StormRelative
import app.hookline.radar.core.radar.Sweep
import app.hookline.radar.core.radar.SweepTexture
import app.hookline.radar.demo.DemoStorm
import app.hookline.radar.render.Outline
import app.hookline.radar.render.RadarFrame
import app.hookline.radar.stormmode.AlertChecker
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
import java.util.TreeMap
import java.util.concurrent.TimeUnit
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
    /** Never reached the feed; showing the synthetic demo storm. */
    data class Demo(val message: String) : FeedStatus
}

/** Storm motion in use for SRV and where it came from. */
data class MotionInfo(val fromDeg: Int, val speedKt: Int, val source: String)

data class LoopUi(
    /** End times of the frames for the current product, oldest first. */
    val frames: List<Long> = emptyList(),
    /** Frame on screen; null = latest (live). */
    val index: Int? = null,
    val playing: Boolean = false,
    /** Loops cover the lowest tilt for REF, VEL and SRV. */
    val available: Boolean = true,
    val loading: String? = null,
)

data class MapUiState(
    val site: RadarSite = Sites.byId("KTLX")!!,
    val sites: List<RadarSite> = Sites.ALL,
    val product: Product = Product.REF,
    val tilts: List<Float> = listOf(0.5f),
    val tilt: Float = 0.5f,
    val availableProducts: Set<Product> = Product.entries.toSet(),
    val frameEndMillis: Long? = null,
    val nowMillis: Long = System.currentTimeMillis(),
    val status: FeedStatus = FeedStatus.Connecting,
    val frame: RadarFrame? = null,
    val projector: MercatorProjector = MercatorProjector(35.333, -97.278),
    val outlines: List<Outline> = emptyList(),
    val loop: LoopUi = LoopUi(),
    val motion: MotionInfo = DEFAULT_MOTION,
    val inspector: Inspection? = null,
    val homeWarning: Alert? = null,
    val stormModeOn: Boolean = true,
    val intensity: MapIntensity = MapIntensity(),
    val loopLength: Int = 12,
) {
    val frameAgeSeconds: Int? get() = frameEndMillis?.let { ((nowMillis - it) / 1000).toInt().coerceAtLeast(0) }
    val showingLatest: Boolean get() = loop.index == null

    companion object {
        val DEFAULT_MOTION = MotionInfo(240, 30, "Default motion")
    }
}

data class Inspection(val value: String, val meaning: String, val height: String)

class MapViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("map", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(
        MapUiState(
            intensity = MapIntensity(
                prefs.getFloat("roads", 0.7f), prefs.getFloat("labels", 0.8f), prefs.getFloat("borders", 0.6f),
            ),
            loopLength = prefs.getInt("loop_length", 12),
        ),
    )
    val state: StateFlow<MapUiState> = _state.asStateFlow()

    val offline = OfflineMaps(app)

    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
    private val nexrad = NexradSource(http)
    private val live = LiveRadar(nexrad)
    private val backfill = LoopBackfill(nexrad)
    private val nws = NwsAlerts(http, BuildConfig.NWS_USER_AGENT)
    private val checker = AlertChecker(app)

    private var snapshot: LiveSnapshot? = null
    private var demoSnapshot: LiveSnapshot? = null
    private var alerts: List<Alert> = emptyList()
    private var mapCenter: LatLon? = null
    private var streamJob: Job? = null
    private var backfillJob: Job? = null
    private var playJob: Job? = null
    private var frameJob: Job? = null
    private var currentTexture: SweepTexture? = null

    /** Loop buffers keyed by sweep end time. Compact: ~2–3 MB per frame. */
    private val refLoop = TreeMap<Long, CompactMoment>()
    private val velLoop = TreeMap<Long, CompactMoment>()

    init {
        startSite(_state.value.site)
        viewModelScope.launch { while (isActive) { _state.update { it.copy(nowMillis = System.currentTimeMillis()) }; delay(1_000) } }
        viewModelScope.launch { while (isActive) { refreshAlerts(); delay(60_000) } }
    }

    // ---- user actions -------------------------------------------------------

    fun selectSite(site: RadarSite) {
        if (site.id == _state.value.site.id) return
        stopLoop()
        backfillJob?.cancel()
        refLoop.clear(); velLoop.clear()
        snapshot = null
        currentTexture = null
        _state.update {
            MapUiState(
                site = site, product = it.product, stormModeOn = it.stormModeOn,
                intensity = it.intensity, loopLength = it.loopLength,
                projector = MercatorProjector(site.location.lat, site.location.lon),
            )
        }
        startSite(site)
        rebuildOverlays()
    }

    fun selectProduct(p: Product) {
        _state.update { it.copy(product = p, inspector = null) }
        refreshLoopUi()
        showCurrent()
    }

    fun selectTilt(t: Float) {
        _state.update { it.copy(tilt = t, inspector = null) }
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

    fun setLoopLength(n: Int) {
        prefs.edit().putInt("loop_length", n).apply()
        _state.update { it.copy(loopLength = n) }
    }

    fun onCameraIdle(center: LatLng) {
        mapCenter = LatLon(center.latitude, center.longitude)
        val before = _state.value.motion
        chooseMotion()
        if (_state.value.motion != before && _state.value.product == Product.SRV) showCurrent()
    }

    fun inspect(at: LatLng) {
        val tex = currentTexture ?: return
        val p = _state.value.projector
        val (brg, dist) = MercatorProjector.bearingDistance(p.siteLat, p.siteLon, at.latitude, at.longitude)
        val v = tex.valueAtPolar(brg, dist)
        val heightFt = RadialGeometry.beamHeightMeters(dist, tex.elevationDeg.toDouble()) * 3.281
        val height = "%,d ft above the radar, %.0f mi out".format(Locale.US, (heightFt / 10).roundToInt() * 10, dist / 1609.34)
        _state.update { it.copy(inspector = describe(it.product, v, height)) }
    }

    fun downloadVisibleMap(bounds: LatLngBounds, pixelRatio: Float) {
        offline.download(bounds, "${_state.value.site.name} area", pixelRatio)
    }

    // ---- live data ----------------------------------------------------------

    private fun startSite(site: RadarSite) {
        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            live.stream(site.id).collect { e ->
                when (e) {
                    is LiveEvent.Data -> onLiveData(site, e)
                    is LiveEvent.Trouble -> {
                        val firstData = snapshot == null
                        _state.update { s -> s.copy(status = if (firstData) FeedStatus.Demo(e.message) else FeedStatus.Stale(e.message)) }
                        if (firstData && _state.value.frame == null) showDemo()
                    }
                }
            }
        }
    }

    private suspend fun onLiveData(site: RadarSite, e: LiveEvent.Data) {
        val first = snapshot == null
        snapshot = e.snapshot
        demoSnapshot = null
        val radarSite = e.snapshot.site
        _state.update { s ->
            val tilts = e.snapshot.tilts
            s.copy(
                status = FeedStatus.Live,
                tilts = tilts,
                tilt = if (s.tilt in tilts) s.tilt else tilts.firstOrNull() ?: s.tilt,
                projector = if (radarSite != null && (first || s.projector.siteLat != radarSite.latitude.toDouble())) {
                    MercatorProjector(radarSite.latitude.toDouble(), radarSite.longitude.toDouble())
                } else s.projector,
            )
        }
        if (first) rebuildOverlays()
        addToLoop(e.snapshot.sweep(LoopBackfill.LOOP_TILT, Moment.REFLECTIVITY), e.snapshot.sweep(LoopBackfill.LOOP_TILT, Moment.VELOCITY))
        if (first) startBackfill(site.id, e.volume)
        if (_state.value.showingLatest) showCurrent()
    }

    private fun startBackfill(siteId: String, volume: Int) {
        backfillJob?.cancel()
        val wanted = _state.value.loopLength
        if (wanted <= 1) return
        backfillJob = viewModelScope.launch {
            var got = 0
            setLoopLoading("Loading loop…")
            backfill.frames(siteId, volume, wanted - 1).collect { f ->
                got++
                addToLoop(f.reflectivity, f.velocity)
                setLoopLoading("Loading loop $got/${wanted - 1}")
            }
            setLoopLoading(null)
        }
    }

    private fun setLoopLoading(text: String?) = _state.update { it.copy(loop = it.loop.copy(loading = text)) }

    private suspend fun addToLoop(ref: Sweep?, vel: Sweep?) {
        val (r, v) = withContext(Dispatchers.Default) {
            ref?.let { CompactMoment.from(it, Moment.REFLECTIVITY) } to vel?.let { CompactMoment.from(it, Moment.VELOCITY) }
        }
        val cap = _state.value.loopLength.coerceAtLeast(2) + 4 // live frames keep arriving
        r?.let { refLoop[it.endMillis] = it; while (refLoop.size > cap) refLoop.pollFirstEntry() }
        v?.let { velLoop[it.endMillis] = it; while (velLoop.size > cap) velLoop.pollFirstEntry() }
        refreshLoopUi()
    }

    private fun loopFor(p: Product): TreeMap<Long, CompactMoment>? = when (p) {
        Product.REF -> refLoop
        Product.VEL, Product.SRV -> velLoop
        else -> null
    }

    private fun refreshLoopUi() {
        val s = _state.value
        val buffer = if (s.tilt == LoopBackfill.LOOP_TILT) loopFor(s.product) else null
        val frames = buffer?.keys?.toList().orEmpty()
        _state.update {
            val idx = it.loop.index?.takeIf { i -> i < frames.size - 1 }
            it.copy(loop = it.loop.copy(frames = frames, index = idx, available = buffer != null && snapshot != null))
        }
        if (buffer == null) stopLoop()
    }

    private fun startLoop() {
        playJob?.cancel()
        _state.update { it.copy(loop = it.loop.copy(playing = true, index = 0)) }
        playJob = viewModelScope.launch {
            while (isActive) {
                val l = _state.value.loop
                val n = l.frames.size
                if (n < 2) break
                val next = ((l.index ?: (n - 1)) + 1) % n
                _state.update { it.copy(loop = it.loop.copy(index = if (next == n - 1) null else next)) }
                showCurrent()
                // Dwell on the newest frame so the eye can find "now".
                delay(if (next == n - 1) 1_500 else 300)
            }
        }
    }

    private fun stopLoop() {
        playJob?.cancel(); playJob = null
        _state.update { it.copy(loop = it.loop.copy(playing = false)) }
    }

    /** Builds the GPU frame for whatever the user has selected: a loop frame or the latest sweep. */
    private fun showCurrent() {
        val s = _state.value
        val idx = s.loop.index
        val buffer = loopFor(s.product)
        if (idx != null && s.loop.available && buffer != null) {
            val key = s.loop.frames.getOrNull(idx) ?: return
            val compact = buffer[key] ?: return
            render(s.product, s.projector, isDemo = false) {
                if (s.product == Product.SRV) {
                    val m = s.motion
                    compact.toTexture { az, v -> v - StormRelative.component(az, m.fromDeg.toFloat(), m.speedKt * 0.514444f) }
                } else compact.toTexture()
            }
            return
        }
        val snap = snapshot ?: demoSnapshot ?: return
        val isDemo = snapshot == null
        val available = Product.entries.filter { snap.sweep(s.tilt, it.moment) != null }.toSet()
        val product = if (s.product in available || available.isEmpty()) s.product else Product.REF
        if (product != s.product || available != s.availableProducts) {
            _state.update { it.copy(product = product, availableProducts = available) }
        }
        val sweep = snap.sweep(s.tilt, product.moment) ?: return
        val motion = s.motion
        render(product, s.projector, isDemo) {
            val srv: ((Radial, MomentData) -> MomentData)? = if (product == Product.SRV) {
                { r, md -> StormRelative.apply(r, motion.fromDeg.toFloat(), motion.speedKt * 0.514444f) ?: md }
            } else null
            SweepTexture.from(sweep, product.moment, srv)
        }
    }

    private fun render(product: Product, projector: MercatorProjector, isDemo: Boolean, build: () -> SweepTexture?) {
        frameJob?.cancel()
        frameJob = viewModelScope.launch {
            val tex = withContext(Dispatchers.Default) { build() } ?: return@launch
            currentTexture = tex
            _state.update {
                it.copy(
                    frame = RadarFrame(tex, product.palette, product.min, product.max, projector),
                    frameEndMillis = if (isDemo) System.currentTimeMillis() - 48_000 else tex.endMillis,
                )
            }
        }
    }

    /** Offline / first-run fallback: a synthetic supercell, clearly labelled as such. */
    private fun showDemo() {
        viewModelScope.launch {
            val demo = withContext(Dispatchers.Default) { DemoStorm.sweep() }
            if (snapshot != null) return@launch
            val site = demo.radials.first().site
            demoSnapshot = LiveSnapshot(
                radarId = "DEMO", site = site,
                sweeps = mapOf((0.5f to Moment.REFLECTIVITY) to demo, (0.5f to Moment.VELOCITY) to demo),
            )
            _state.update {
                it.copy(
                    tilts = listOf(0.5f), tilt = 0.5f,
                    projector = if (site != null) MercatorProjector(site.latitude.toDouble(), site.longitude.toDouble()) else it.projector,
                )
            }
            showCurrent()
        }
    }

    // ---- warnings, storm motion, tracks ------------------------------------

    private suspend fun refreshAlerts() {
        alerts = runCatching {
            nws.activeEvents(listOf("Tornado Warning", "Severe Thunderstorm Warning", "Flash Flood Warning"))
        }.getOrElse { return }
        val home = checker.places().firstOrNull()?.second
        _state.update { s -> s.copy(homeWarning = home?.let { h -> alerts.firstOrNull { it.covers(h) } }) }
        chooseMotion()
        rebuildOverlays()
        if (_state.value.product == Product.SRV) showCurrent()
    }

    /**
     * SRV needs the storm's own motion. Use the NWS warning closest to where
     * the user is looking (within the radar's range); else the default, and
     * say so on screen.
     */
    private fun chooseMotion() {
        val p = _state.value.projector
        val focus = mapCenter ?: LatLon(p.siteLat, p.siteLon)
        val candidates = alerts.mapNotNull { a -> a.motion?.takeIf { it.positions.isNotEmpty() }?.let { a to it } }
            .map { (a, m) ->
                val pos = m.positions.first()
                val fromSite = MercatorProjector.bearingDistance(p.siteLat, p.siteLon, pos.lat, pos.lon).second
                val fromFocus = MercatorProjector.bearingDistance(focus.lat, focus.lon, pos.lat, pos.lon).second
                Triple(a, m, fromSite to fromFocus)
            }
            .filter { it.third.first < 230_000 }
        val best = candidates.minByOrNull { it.third.second }
        _state.update {
            it.copy(
                motion = best?.let { (a, m, _) -> MotionInfo(m.fromDeg, m.speedKt, "From the ${a.event.lowercase()}") }
                    ?: MapUiState.DEFAULT_MOTION,
            )
        }
    }

    private fun rebuildOverlays() {
        val p = _state.value.projector
        fun project(ll: LatLon): Pair<Float, Float> = p.fromLatLon(ll.lat, ll.lon).let { it.first.toFloat() to it.second.toFloat() }
        val list = mutableListOf<Outline>()
        for (a in alerts) {
            val (argb, width) = when (a.hazard) {
                Hazard.TORNADO_EMERGENCY -> 0xFFB000B0.toInt() to 4f
                Hazard.TORNADO_WARNING -> 0xFFE0245E.toInt() to 3.5f
                Hazard.SEVERE_WARNING -> 0xFFF5A623.toInt() to 2.5f
                Hazard.FLASH_FLOOD_WARNING -> 0xFF2E9E5B.toInt() to 2.5f
                else -> continue
            }
            for (ring in a.polygons) {
                val pts = FloatArray(ring.size * 2)
                ring.forEachIndexed { i, ll -> val (x, y) = project(ll); pts[i * 2] = x; pts[i * 2 + 1] = y }
                list += Outline(pts, argb, width)
            }
            a.motion?.let { list += stormTrack(it, p) }
        }
        _state.update { it.copy(outlines = list) }
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
                    // small diamond mark
                    val r = 1_200.0
                    val mark = FloatArray(8)
                    for ((j, b) in listOf(0.0, 90.0, 180.0, 270.0).withIndex()) {
                        val (la, lo) = RadialGeometry.destination(lat, lon, b, r)
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

    // ---- inspector wording --------------------------------------------------

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
                    else -> "Hail likely. Check CC: a drop below 0.9 here supports hail."
                },
                height,
            )
            Product.VEL, Product.SRV -> {
                val kt = (kotlin.math.abs(v) * 1.944f).roundToInt()
                val dir = if (v < 0) "toward the radar" else "away from the radar"
                Inspection(
                    "$kt kt $dir",
                    if (kt >= 50) "Strong wind. Look for an opposite colour right next to it: that pair means rotation." else "Wind moving $dir.",
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
        private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
        fun clock(millis: Long): String = TIME.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
    }
}
