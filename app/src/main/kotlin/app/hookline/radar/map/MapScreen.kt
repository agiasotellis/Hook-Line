package app.hookline.radar.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.hookline.radar.core.data.Hazard
import app.hookline.radar.core.designsystem.ChromePanel
import app.hookline.radar.core.designsystem.Dimens
import app.hookline.radar.core.designsystem.HazardColors
import app.hookline.radar.core.designsystem.HazardSwatch
import app.hookline.radar.core.designsystem.Hookline
import app.hookline.radar.core.designsystem.PillRow
import app.hookline.radar.core.designsystem.Readout
import app.hookline.radar.core.designsystem.SelectPill
import app.hookline.radar.render.RadarGlView

@Composable
fun MapScreen(vm: MapViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val c = Hookline.colors
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Plain holder (not snapshot state): the view is created inside composition.
    val glHolder = remember { arrayOfNulls<RadarGlView>(1) }

    // GLSurfaceView must follow the activity lifecycle.
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> glHolder[0]?.onResume()
                Lifecycle.Event.ON_PAUSE -> glHolder[0]?.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    Box(Modifier.fillMaxSize().background(c.mapVoid)) {
        AndroidView(
            factory = { ctx ->
                RadarGlView(ctx).also { v ->
                    v.renderer.setClearColor(0xFF141B22.toInt())
                    v.onInspect = vm::inspect
                    glHolder[0] = v
                }
            },
            update = { v ->
                // Re-centre on the radar when the site changes.
                if (v.tag != s.site.id) {
                    v.tag = s.site.id
                    v.center(0.0, 0.0, metersPerPixel = 250.0)
                }
                v.setFrame(s.frame)
                v.setOutlines(s.outlines)
            },
            modifier = Modifier.fillMaxSize(),
        )

        Column(
            Modifier.fillMaxWidth().statusBarsPadding().padding(Dimens.EdgeInset),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SiteChip(s, onSelect = vm::selectSite)
                s.homeWarning?.let { w ->
                    WarningBanner(
                        headline = w.event,
                        detail = "Home" + (w.expires?.let { ", until " + MapViewModel.clock(it.toEpochMilli()) } ?: ""),
                        hazard = w.hazard,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusChip(s.status)
                if (s.stormModeOn) Chip("Storm Mode on", dot = true)
            }
        }

        TiltLadder(
            tilts = s.tilts, selected = s.tilt, onSelect = vm::selectTilt,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = Dimens.EdgeInset),
        )

        Column(Modifier.align(Alignment.BottomCenter)) {
            s.inspector?.let { InspectorCard(it, onDismiss = vm::dismissInspector) }
            BottomDock(s, onProduct = vm::selectProduct)
        }
    }
}

@Composable
private fun SiteChip(s: MapUiState, onSelect: (app.hookline.radar.core.data.RadarSite) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ChromePanel(Modifier.height(Dimens.MinTouch).clickable { open = true }.semantics {
            contentDescription = "Radar site ${s.site.id}, ${s.site.name}. Tap to change."
        }) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalArrangement = Arrangement.Center) {
                Text(s.site.id, style = Hookline.type.title, color = Hookline.colors.ink)
                Text(s.site.name, style = Hookline.type.caption, color = Hookline.colors.inkSecondary)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            s.sites.forEach { site ->
                DropdownMenuItem(
                    text = { Text("${site.id}  ${site.name}", style = Hookline.type.control) },
                    onClick = { open = false; onSelect(site) },
                )
            }
        }
    }
}

@Composable
private fun WarningBanner(headline: String, detail: String, hazard: Hazard, modifier: Modifier = Modifier) {
    val c = Hookline.colors
    val color = when (hazard) {
        Hazard.TORNADO_EMERGENCY -> HazardColors.TornadoEmergency
        Hazard.TORNADO_WARNING -> HazardColors.TornadoWarning
        Hazard.SEVERE_WARNING -> HazardColors.SevereWarning
        Hazard.FLASH_FLOOD_WARNING -> HazardColors.FlashFlood
        Hazard.TORNADO_WATCH -> HazardColors.TornadoWatch
        else -> HazardColors.SevereWatch
    }
    Row(
        modifier
            .height(Dimens.MinTouch)
            .background(c.chrome, RoundedCornerShape(Dimens.AlertRadius))
            .border(1.dp, color, RoundedCornerShape(Dimens.AlertRadius))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HazardSwatch(color)
        Column {
            Text(headline, style = Hookline.type.control, color = c.ink)
            Text(detail, style = Hookline.type.caption, color = c.inkSecondary)
        }
    }
}

@Composable
private fun StatusChip(status: FeedStatus) {
    when (status) {
        FeedStatus.Connecting -> Chip("Connecting to radar…")
        FeedStatus.Live -> Chip("Live", dot = true)
        is FeedStatus.Stale -> Chip("Not updating: ${status.message}", hatched = true)
        is FeedStatus.Demo -> Chip("Demo storm, not real data", hatched = true)
    }
}

@Composable
private fun Chip(text: String, dot: Boolean = false, hatched: Boolean = false) {
    val c = Hookline.colors
    Row(
        Modifier.background(c.chrome, CircleShape)
            .border(1.dp, if (hatched) c.ink else c.hairline, CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (dot) Box(Modifier.size(7.dp).background(c.ink, CircleShape))
        Text(text, style = Hookline.type.caption, color = if (hatched) c.ink else c.inkSecondary, maxLines = 1)
    }
}

@Composable
private fun TiltLadder(tilts: List<Float>, selected: Float, onSelect: (Float) -> Unit, modifier: Modifier = Modifier) {
    if (tilts.isEmpty()) return
    ChromePanel(modifier) {
        Column(
            Modifier.padding(4.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            tilts.take(8).forEach { t ->
                SelectPill("$t°", selected = t == selected, onClick = { onSelect(t) }, modifier = Modifier.width(52.dp))
            }
        }
    }
}

@Composable
private fun InspectorCard(i: Inspection, onDismiss: () -> Unit) {
    val c = Hookline.colors
    Column(
        Modifier
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .fillMaxWidth()
            .background(c.raised, RoundedCornerShape(Dimens.PanelRadius))
            .border(1.dp, c.hairline, RoundedCornerShape(Dimens.PanelRadius))
            .clickable(onClick = onDismiss)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(i.value, style = Hookline.type.readoutXL, color = c.ink)
        Text(i.meaning, style = Hookline.type.body, color = c.ink)
        Text(i.height, style = Hookline.type.caption, color = c.inkSecondary)
    }
}

@Composable
private fun BottomDock(s: MapUiState, onProduct: (Product) -> Unit) {
    val c = Hookline.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.mapVoid.copy(alpha = 0.94f))
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Legend(s.product)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TimeTape(s.ticks, s.nowMillis, Modifier.weight(1f).height(48.dp))
            val age = s.scanAgeSeconds
            Readout(
                value = age?.let { if (it < 3600) "%d:%02d".format(it / 60, it % 60) else "${it / 3600} h" } ?: "–",
                caption = if (age != null && age > 600) "scan age, stale" else "scan age",
                alignEnd = true,
            )
        }
        PillRow {
            Product.entries.forEach { p ->
                val enabled = p in s.availableProducts
                SelectPill(
                    p.label,
                    selected = p == s.product,
                    onClick = { if (enabled) onProduct(p) },
                    modifier = Modifier.weight(1f).alpha(if (enabled) 1f else 0.35f),
                )
            }
        }
        Text("Radar and warnings: NOAA / National Weather Service", style = Hookline.type.caption, color = c.inkSecondary)
    }
}

/**
 * Instrument tape of completed sweeps over the last hour: one tick per sweep,
 * tall ticks for low tilts. The fixed needle on the right is "now".
 */
@Composable
private fun TimeTape(ticks: List<ScanTick>, now: Long, modifier: Modifier = Modifier) {
    val c = Hookline.colors
    Canvas(modifier.semantics { contentDescription = "${ticks.size} scans received in the last hour" }) {
        val base = size.height - 6.dp.toPx()
        val maxH = size.height - 10.dp.toPx()
        val windowMs = 60 * 60 * 1000f
        drawLine(c.hairline, Offset(0f, base), Offset(size.width, base), strokeWidth = 1.dp.toPx())
        for (t in ticks) {
            val x = size.width * (1f - (now - t.endMillis) / windowMs)
            if (x < 0f) continue
            val h = maxH * (1f - (t.tilt / 20f)).coerceIn(0.25f, 1f)
            drawLine(c.inkSecondary, Offset(x, base), Offset(x, base - h), strokeWidth = 2.dp.toPx())
        }
        drawLine(c.ink, Offset(size.width - 1.dp.toPx(), 0f), Offset(size.width - 1.dp.toPx(), base), strokeWidth = 2.dp.toPx())
    }
}

@Composable
private fun Legend(product: Product) {
    val c = Hookline.colors
    val lut = remember(product) {
        product.palette.toLut(product.min, product.max, 24).filter { (it ushr 24) != 0 }.map { Color(it) }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            when (product) { Product.REF -> "dBZ"; Product.VEL, Product.SRV -> "m/s"; Product.CC -> "CC"; Product.ZDR -> "dB" },
            style = Hookline.type.caption, color = c.inkSecondary, modifier = Modifier.width(34.dp),
        )
        if (lut.size >= 2) Box(Modifier.weight(1f).height(6.dp).background(Brush.horizontalGradient(lut), RoundedCornerShape(3.dp)))
        Spacer(Modifier.width(4.dp))
    }
}
