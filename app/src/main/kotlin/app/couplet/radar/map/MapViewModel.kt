package app.couplet.radar.map

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.couplet.radar.core.radar.Moment
import app.couplet.radar.core.radar.MomentData
import app.couplet.radar.core.radar.Radial
import app.couplet.radar.core.radar.Palette
import app.couplet.radar.core.radar.StormRelative
import app.couplet.radar.core.radar.Sweep
import app.couplet.radar.core.radar.SweepRasterizer
import app.couplet.radar.demo.DemoStorm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Product(val label: String) { REF("REF"), VEL("VEL"), SRV("SRV"), CC("CC"), ZDR("ZDR") }

data class MapUiState(
    val site: String = "KTLX",
    val siteName: String = "Oklahoma City",
    val product: Product = Product.REF,
    val tiltIndex: Int = 0,
    val tilts: List<Float> = listOf(0.5f, 0.9f, 1.3f, 1.8f, 2.4f),
    val scanAgeSeconds: Int = 48,
    val playing: Boolean = true,
    val image: Bitmap? = null,
    val loading: Boolean = true,
    val warningHeadline: String? = "Tornado warning",
    val warningDetail: String? = "Your location, until 7:42 PM",
    val stormModeOn: Boolean = true,
)

/**
 * Interim map state: renders the demo storm with the CPU rasterizer.
 * M0 replaces this with NexradSource chunks + the GL radial renderer.
 */
class MapViewModel : ViewModel() {
    private val _state = MutableStateFlow(MapUiState())
    val state: StateFlow<MapUiState> = _state.asStateFlow()

    private var sweep: Sweep? = null
    private var viewport = 0 to 0

    fun onViewport(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || viewport == width to height) return
        viewport = width to height
        render()
    }

    fun selectProduct(p: Product) {
        _state.update { it.copy(product = p) }
        render()
    }

    fun selectTilt(i: Int) = _state.update { it.copy(tiltIndex = i) }
    fun togglePlay() = _state.update { it.copy(playing = !it.playing) }

    private fun render() {
        val (w, h) = viewport
        if (w == 0) return
        val product = _state.value.product
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val bmp = withContext(Dispatchers.Default) {
                val s = sweep ?: DemoStorm.sweep().also { sweep = it }
                // Render at half resolution; bins are far larger than pixels anyway.
                val rw = w / 2; val rh = h / 2
                val srv: (Radial, MomentData) -> MomentData = { r, md ->
                    StormRelative.apply(r, motionFromDeg = 225f, motionSpeedMs = 15f) ?: md
                }
                val moment = if (product == Product.VEL || product == Product.SRV) Moment.VELOCITY else Moment.REFLECTIVITY
                val palette = if (moment == Moment.VELOCITY) Palette.VELOCITY_CVD else Palette.NWS_REFLECTIVITY
                val transform = if (product == Product.SRV) srv else null
                // Centre the demo mesocyclone (31 km W, 65 km N of the radar) on screen.
                val mpp = 200f
                val px = SweepRasterizer.rasterize(
                    s, moment, palette, rw, rh,
                    centerX = rw * 0.5f + 31_000f / mpp,
                    centerY = rh * 0.48f + 65_000f / mpp,
                    metersPerPixel = mpp,
                    transform = transform,
                )
                Bitmap.createBitmap(px, rw, rh, Bitmap.Config.ARGB_8888)
            }
            _state.update { it.copy(image = bmp, loading = false) }
        }
    }
}
