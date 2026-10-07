package app.hookline.radar.map

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.hookline.radar.core.data.Fmt
import app.hookline.radar.core.designsystem.ChromePanel
import app.hookline.radar.core.designsystem.Dimens
import app.hookline.radar.core.designsystem.Hookline
import app.hookline.radar.core.radar.MercatorProjector
import app.hookline.radar.render.RadarGlView
import app.hookline.radar.share.GifEncoder
import app.hookline.radar.share.Sharer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import app.hookline.radar.i18n.tr
import app.hookline.radar.i18n.compass
import app.hookline.radar.i18n.midSentence

/** A square map control with an icon. */
@Composable
fun MapButton(description: String, active: Boolean = false, onClick: () -> Unit, icon: @Composable (Color) -> Unit) {
    val c = Hookline.colors
    ChromePanel(
        Modifier.size(Dimens.MinTouch)
            .let { if (active) it.border(1.5.dp, c.ink, RoundedCornerShape(Dimens.PanelRadius)) else it }
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { icon(c.ink) }
    }
}

@Composable
fun LocateIcon(color: Color, following: Boolean) {
    Canvas(Modifier.size(22.dp)) {
        val w = size.width; val r = w * 0.32f; val s = 1.8.dp.toPx()
        drawCircle(color, r, style = Stroke(s))
        if (following) drawCircle(color, r * 0.45f)
        for ((a, b) in listOf(Offset(w / 2, 0f) to Offset(w / 2, w * 0.18f), Offset(w / 2, w) to Offset(w / 2, w * 0.82f),
            Offset(0f, w / 2) to Offset(w * 0.18f, w / 2), Offset(w, w / 2) to Offset(w * 0.82f, w / 2))) drawLine(color, a, b, s)
    }
}

@Composable
fun RulerIcon(color: Color) {
    Canvas(Modifier.size(22.dp)) {
        val w = size.width; val s = 1.8.dp.toPx()
        drawLine(color, Offset(w * 0.12f, w * 0.88f), Offset(w * 0.88f, w * 0.12f), s)
        drawCircle(color, w * 0.1f, Offset(w * 0.12f, w * 0.88f))
        drawCircle(color, w * 0.1f, Offset(w * 0.88f, w * 0.12f))
        for (k in 1..3) {
            val t = k / 4f
            val x = w * (0.12f + 0.76f * t); val y = w * (0.88f - 0.76f * t)
            drawLine(color, Offset(x - w * 0.07f, y - w * 0.07f), Offset(x + w * 0.0f, y - w * 0.0f), s)
        }
    }
}

@Composable
fun ShareIcon(color: Color) {
    Canvas(Modifier.size(22.dp)) {
        val w = size.width; val s = 1.8.dp.toPx()
        drawLine(color, Offset(w / 2, w * 0.08f), Offset(w / 2, w * 0.62f), s)
        drawLine(color, Offset(w / 2, w * 0.08f), Offset(w * 0.3f, w * 0.28f), s)
        drawLine(color, Offset(w / 2, w * 0.08f), Offset(w * 0.7f, w * 0.28f), s)
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.28f, w * 0.42f); lineTo(w * 0.12f, w * 0.42f); lineTo(w * 0.12f, w * 0.92f)
            lineTo(w * 0.88f, w * 0.92f); lineTo(w * 0.88f, w * 0.42f); lineTo(w * 0.72f, w * 0.42f)
        }
        drawPath(path, color, style = Stroke(s))
    }
}

/** The measure tool's readout: distance, bearing and how long a storm would take. */
@Composable
fun MeasureCard(s: MapUiState, onClear: () -> Unit, onClose: () -> Unit, onCrossSection: () -> Unit) {
    val c = Hookline.colors
    val m = s.measure ?: return
    Column(
        Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth()
            .background(c.raised, RoundedCornerShape(Dimens.PanelRadius))
            .border(1.dp, c.hairline, RoundedCornerShape(Dimens.PanelRadius))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val a = m.a; val b = m.b
        when {
            a == null -> Text(tr("Tap the start point, for example a storm."), style = Hookline.type.body, color = c.ink)
            b == null -> Text(tr("Now tap the end point, for example where you are."), style = Hookline.type.body, color = c.ink)
            else -> {
                val (brg, dist) = MercatorProjector.bearingDistance(a.lat, a.lon, b.lat, b.lon)
                Text(Fmt.distance(dist, s.units), style = Hookline.type.readoutXL, color = c.ink)
                Text(tr("Bearing %s", compass(Fmt.bearing(brg))), style = Hookline.type.body, color = c.ink)
                val speed = s.motion.speedKt * 0.514444
                if (speed > 1) {
                    Text(
                        tr("A storm moving at %s covers this in about %s.", Fmt.speed(speed, s.units), Fmt.duration(dist / speed)),
                        style = Hookline.type.body, color = c.ink,
                    )
                    Text(tr("Storm speed: %s", midSentence(s.motion.source)), style = Hookline.type.caption, color = c.inkSecondary)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            if (m.a != null && m.b != null && s.mode == Mode.SITE) {
                Text(tr("Cross-section"), style = Hookline.type.control, color = c.ink, modifier = Modifier.clickable(onClick = onCrossSection).padding(vertical = 8.dp))
            }
            Text(tr("Clear"), style = Hookline.type.control, color = c.ink, modifier = Modifier.clickable(onClick = onClear).padding(vertical = 8.dp))
            Text(tr("Done"), style = Hookline.type.control, color = c.inkSecondary, modifier = Modifier.clickable(onClick = onClose).padding(vertical = 8.dp))
        }
    }
}

/** Captures what's on screen and shares it as a picture or an animated loop. */
class ShareActions(
    private val context: Context,
    private val radar: RadarGlView,
    private val baseMap: BaseMap,
    private val vm: MapViewModel,
) {
    private fun title(s: MapUiState, frameMillis: Long?): String {
        val what = when (s.mode) {
            Mode.SITE -> "${if (s.mode == Mode.SITE) s.product.label else "REF"} · ${s.site?.label ?: ""} ${s.site?.name ?: ""}"
            Mode.COMPOSITE -> tr("Reflectivity · Europe composite")
            Mode.MOSAIC -> tr("Reflectivity · US mosaic")
            Mode.NONE -> tr("Satellite")
        }
        return what + (frameMillis?.let { " · " + MapViewModel.clock(it) } ?: "")
    }

    private fun credit(s: MapUiState) = listOf(s.attribution, tr("Map %s", BaseMap.ATTRIBUTION)).filter { it.isNotEmpty() }.joinToString(". ")

    suspend fun still(progress: (String?) -> Unit) {
        val s = vm.state.value
        progress(tr("Preparing picture…"))
        try {
            val r = radar.capture()
            val map = baseMap.snapshot()
            val file = withContext(Dispatchers.Default) {
                val img = Sharer.compose(context, r, map, title(s, s.frameEndMillis), credit(s), 0xFF141B22.toInt())
                Sharer.write(context, "hookline.png") { img.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            Sharer.share(context, file, "image/png", tr("Radar from Hookline"))
        } finally {
            progress(null)
        }
    }

    /** Steps through the loop, captures each frame and encodes a GIF (about 540 px wide). */
    suspend fun loop(progress: (String?) -> Unit) {
        val s0 = vm.state.value
        val n = s0.loop.frames.size
        if (n < 2) return
        try {
            val map = baseMap.snapshot()
            val frames = mutableListOf<IntArray>()
            var w = 0; var h = 0
            for (i in 0 until n) {
                progress(tr("Making loop %s/%s…", i + 1, n))
                vm.scrubTo(i)
                delay(450) // let the frame render
                val st = vm.state.value
                val r = radar.capture()
                val px = withContext(Dispatchers.Default) {
                    val img = Sharer.scaleToWidth(Sharer.compose(context, r, map, title(st, st.frameEndMillis), credit(st), 0xFF141B22.toInt()), 540)
                    w = img.width; h = img.height
                    Sharer.pixels(img)
                }
                frames += px
            }
            vm.scrubTo(n) // back to live
            progress(tr("Encoding…"))
            val file = withContext(Dispatchers.Default) {
                val delays = List(n) { if (it == n - 1) 150 else 35 }
                Sharer.write(context, "hookline-loop.gif") { GifEncoder(w, h).encode(frames, delays, it) }
            }
            Sharer.share(context, file, "image/gif", tr("Radar loop from Hookline"))
        } finally {
            progress(null)
        }
    }
}
