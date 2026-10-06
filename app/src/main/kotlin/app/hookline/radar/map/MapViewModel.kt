package app.hookline.radar.map

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.hookline.radar.BuildConfig
import app.hookline.radar.core.data.Alert
import app.hookline.radar.core.data.Hazard
import app.hookline.radar.core.data.LatLon
import app.hookline.radar.core.data.LiveEvent
import app.hookline.radar.core.data.LiveRadar
import app.hookline.radar.core.data.LiveSnapshot
import app.hookline.radar.core.data.NexradSource
import app.hookline.radar.core.data.NwsAlerts
import app.hookline.radar.core.data.RadarSite
import app.hookline.radar.core.data.Sites
import app.hookline.radar.core.data.Tilts
import app.hookline.radar.core.radar.Gate
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.hypot
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

/** One completed sweep, for the time tape. */
data class ScanTick(val endMillis: Long, val tilt: Float)

data class MapUiState(
    val site: RadarSite = Sites.byId("KTLX")!!,
    val sites: List<RadarSite> = Sites.ALL,
    val product: Product = Product.REF,
    val tilts: List<Float> = listOf(0.5f),
    val tilt: Float = 0.5f,
    val availableProducts: Set<Product> = Product.entries.toSet(),
    val scanEndMillis: Long? = null,
    val nowMillis: Long = System.currentTimeMillis(),
    val status: FeedStatus = FeedStatus.Connecting,
    val frame: RadarFrame? = null,
    val outlines: List<Outline> = emptyList(),
    val ticks: List<ScanTick> = emptyList(),
    val inspector: Inspection? = null,
    val homeWarning: Alert? = null,
    val stormModeOn: Boolean = true,
) {
    val scanAgeSeconds: Int? get() = scanEndMillis?.let { ((nowMillis - it) / 1000).toInt().coerceAtLeast(0) }
}

data class Inspection(val value: String, val meaning: String, val height: String)

class MapViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(MapUiState())
    val state: StateFlow<MapUiState> = _state.asStateFlow()

    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
    private val live = LiveRadar(NexradSource(http))
    private val nws = NwsAlerts(http, BuildConfig.NWS_USER_AGENT)
    private val checker = AlertChecker(app)

    private var snapshot: LiveSnapshot? = null
    private var demoSnapshot: LiveSnapshot? = null
    private var alerts: List<Alert> = emptyList()
    private var streamJob: Job? = null
    private var frameJob: Job? = null
    private var currentTexture: SweepTexture? = null

    init {
        startSite(_state.value.site)
        viewModelScope.launch { while (isActive) { _state.update { it.copy(nowMillis = System.currentTimeMillis()) }; delay(1_000) } }
        viewModelScope.launch { while (isActive) { refreshAlerts(); delay(60_000) } }
    }

    fun selectSite(site: RadarSite) {
        if (site.id == _state.value.site.id) return
        _state.update { MapUiState(site = site, product = it.product, stormModeOn = it.stormModeOn) }
        snapshot = null
        startSite(site)
        rebuildOutlines()
    }

    fun selectProduct(p: Product) { _state.update { it.copy(product = p, inspector = null) }; rebuildFrame() }
    fun selectTilt(t: Float) { _state.update { it.copy(tilt = t, inspector = null) }; rebuildFrame() }
    fun dismissInspector() = _state.update { it.copy(inspector = null) }

    fun inspect(eastM: Double, northM: Double) {
        val tex = currentTexture ?: return
        val p = _state.value.product
        val v = tex.valueAt(eastM, northM)
        val heightFt = RadialGeometry.beamHeightMeters(hypot(eastM, northM), tex.elevationDeg.toDouble()) * 3.281
        val height = "%,d ft above the radar".format(Locale.US, (heightFt / 10).roundToInt() * 10)
        _state.update { it.copy(inspector = describe(p, v, height)) }
    }

    private fun startSite(site: RadarSite) {
        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            live.stream(site.id).collect { e ->
                when (e) {
                    is LiveEvent.Data -> {
                        snapshot = e.snapshot
                        _state.update { s ->
                            val tilts = e.snapshot.tilts
                            val newest = e.snapshot.sweeps.values.maxByOrNull { it.endMillis }
                            s.copy(
                                status = FeedStatus.Live,
                                tilts = tilts,
                                tilt = if (s.tilt in tilts) s.tilt else tilts.firstOrNull() ?: s.tilt,
                                ticks = (s.ticks + listOfNotNull(newest?.let { ScanTick(it.endMillis, Tilts.snap(it.elevationDeg)) }))
                                    .distinct().takeLast(48),
                            )
                        }
                        rebuildFrame()
                    }
                    is LiveEvent.Trouble -> {
                        val firstData = snapshot == null
                        _state.update { s ->
                            s.copy(status = if (firstData) FeedStatus.Demo(e.message) else FeedStatus.Stale(e.message))
                        }
                        if (firstData && _state.value.frame == null) showDemo()
                    }
                }
            }
        }
    }

    /** Offline / first-run fallback: a synthetic supercell, clearly labelled as such. */
    private fun showDemo() {
        viewModelScope.launch {
            val demo = withContext(Dispatchers.Default) { DemoStorm.sweep() }
            if (snapshot != null) return@launch
            val d = LiveSnapshot(
                radarId = "DEMO", site = demo.radials.first().site,
                sweeps = mapOf((0.5f to Moment.REFLECTIVITY) to demo, (0.5f to Moment.VELOCITY) to demo),
            )
            demoSnapshot = d
            _state.update { it.copy(tilts = listOf(0.5f), tilt = 0.5f) }
            buildFrame(d, isDemo = true)
        }
    }

    private fun rebuildFrame() {
        snapshot?.let { buildFrame(it, isDemo = false); return }
        demoSnapshot?.let { buildFrame(it, isDemo = true) }
    }

    private fun buildFrame(snap: LiveSnapshot, isDemo: Boolean) {
        val s = _state.value
        val tilt = s.tilt
        val available = Product.entries.filter { snap.sweep(tilt, it.moment) != null }.toSet()
        val product = if (s.product in available || available.isEmpty()) s.product else Product.REF
        val sweep: Sweep = snap.sweep(tilt, product.moment) ?: run {
            _state.update { it.copy(availableProducts = available) }
            return
        }
        frameJob?.cancel()
        frameJob = viewModelScope.launch {
            val tex = withContext(Dispatchers.Default) {
                val srv: ((Radial, MomentData) -> MomentData)? = if (product == Product.SRV) {
                    // Default motion until NWS storm tracks are wired in: from 225° at 15 m/s.
                    { r, md -> StormRelative.apply(r, 225f, 15f) ?: md }
                } else null
                SweepTexture.from(sweep, product.moment, srv)
            } ?: return@launch
            currentTexture = tex
            _state.update {
                it.copy(
                    product = product,
                    availableProducts = available,
                    frame = RadarFrame(tex, product.palette, product.min, product.max),
                    scanEndMillis = if (isDemo) System.currentTimeMillis() - 48_000 else tex.endMillis,
                )
            }
        }
    }

    private suspend fun refreshAlerts() {
        alerts = runCatching {
            nws.activeEvents(listOf("Tornado Warning", "Severe Thunderstorm Warning", "Flash Flood Warning"))
        }.getOrElse { return }
        val home = checker.places().firstOrNull()?.second
        _state.update { s -> s.copy(homeWarning = home?.let { h -> alerts.firstOrNull { it.covers(h) } }) }
        rebuildOutlines()
    }

    private fun rebuildOutlines() {
        val origin = _state.value.site.location
        val list = alerts.flatMap { a ->
            val (argb, width) = when (a.hazard) {
                Hazard.TORNADO_EMERGENCY -> 0xFFB000B0.toInt() to 4f
                Hazard.TORNADO_WARNING -> 0xFFE0245E.toInt() to 3.5f
                Hazard.SEVERE_WARNING -> 0xFFF5A623.toInt() to 2.5f
                Hazard.FLASH_FLOOD_WARNING -> 0xFF2E9E5B.toInt() to 2.5f
                else -> return@flatMap emptyList()
            }
            a.polygons.map { ring ->
                val pts = FloatArray(ring.size * 2)
                ring.forEachIndexed { i, ll ->
                    val (e, n) = Sites.toLocalMeters(ll, origin)
                    pts[i * 2] = e.toFloat(); pts[i * 2 + 1] = n.toFloat()
                }
                Outline(pts, argb, width)
            }
        }
        _state.update { it.copy(outlines = list) }
    }

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
                Inspection("$kt kt $dir", if (kt >= 50) "Strong wind. Look for an opposite colour right next to it: that pair means rotation." else "Wind moving $dir.", height)
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
