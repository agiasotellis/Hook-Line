package app.hookline.radar.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import app.hookline.radar.core.data.RadarSite
import app.hookline.radar.core.designsystem.ChromePanel
import app.hookline.radar.core.designsystem.Dimens
import app.hookline.radar.core.designsystem.HazardColors
import app.hookline.radar.core.designsystem.HazardSwatch
import app.hookline.radar.core.designsystem.Hookline
import app.hookline.radar.core.designsystem.PillRow
import app.hookline.radar.core.designsystem.Readout
import app.hookline.radar.core.designsystem.SelectPill
import app.hookline.radar.render.RadarGlView
import java.util.Locale

@Composable
fun MapScreen(vm: MapViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val c = Hookline.colors
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latest by rememberUpdatedState(s)

    // Views are created once and outlive recompositions.
    val radar = remember { RadarGlView(context).also { it.renderer.setClearColor(0xFF141B22.toInt()) } }
    val baseMap = remember { BaseMap(context) }
    var showLayers by remember { mutableStateOf(false) }

    // The map owns gestures; the radar follows its camera exactly.
    LaunchedEffect(baseMap) {
        baseMap.onCameraMove = {
            baseMap.radarViewport(latest.origin, baseMap.mapView.width, baseMap.mapView.height)?.let(radar::setViewport)
        }
        baseMap.onCameraIdle = vm::onCameraIdle
        baseMap.onLongPress = vm::inspect
    }
    LaunchedEffect(s.camera?.seq) { s.camera?.let { baseMap.moveTo(it.lat, it.lon, it.zoom) } }
    LaunchedEffect(s.intensity) { baseMap.setIntensity(s.intensity) }

    // Start where the user is (coarse location is enough to pick radars).
    val locationPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) DeviceLocation.request(context) { lat, lon -> vm.onLocation(lat, lon) } }
    LaunchedEffect(Unit) {
        if (DeviceLocation.hasPermission(context)) DeviceLocation.request(context) { lat, lon -> vm.onLocation(lat, lon) }
        else locationPermission.launch(android.Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> baseMap.onStart()
                Lifecycle.Event.ON_RESUME -> { baseMap.onResume(); radar.onResume() }
                Lifecycle.Event.ON_PAUSE -> { radar.onPause(); baseMap.onPause() }
                Lifecycle.Event.ON_STOP -> baseMap.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        onDispose {
            lifecycle.removeObserver(obs)
            baseMap.onDestroy()
        }
    }

    Box(Modifier.fillMaxSize().background(c.mapVoid)) {
        AndroidView(
            factory = { radar },
            update = { v ->
                v.setLayers(s.compositeLayer, s.siteLayer)
                v.setOutlines(s.outlines)
            },
            modifier = Modifier.fillMaxSize(),
        )
        // Translucent vector map on top: roads, borders and names stay readable over echoes.
        AndroidView(factory = { baseMap.mapView }, modifier = Modifier.fillMaxSize())

        Column(
            Modifier.fillMaxWidth().statusBarsPadding().padding(Dimens.EdgeInset),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SiteChip(s, onSelect = vm::selectSite, onAuto = vm::setAutoSite)
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
                StatusChip(s.status, s.showingLatest, s.mode)
                if (s.stormModeOn) Chip("Storm Mode on", dot = true)
            }
        }

        Column(
            Modifier.align(Alignment.CenterEnd).padding(end = Dimens.EdgeInset),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.End,
        ) {
            TiltLadder(tilts = s.tilts, selected = s.tilt, onSelect = vm::selectTilt)
            ChromePanel(
                Modifier.size(Dimens.MinTouch).clickable { showLayers = true }
                    .semantics { contentDescription = "Layers and offline maps" },
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LayersIcon(c.ink) }
            }
        }

        Column(Modifier.align(Alignment.BottomCenter)) {
            s.inspector?.let { InspectorCard(it, onDismiss = vm::dismissInspector) }
            BottomDock(s, onProduct = vm::selectProduct, onPlay = vm::togglePlay, onScrub = vm::scrubTo)
        }
    }

    if (showLayers) {
        val offline by vm.offline.ui.collectAsStateWithLifecycle()
        val density = LocalDensity.current.density
        LayersSheet(
            intensity = s.intensity,
            loopLength = s.loopLength,
            offline = offline,
            onIntensity = vm::setIntensity,
            onLoopLength = vm::setLoopLength,
            onDownload = { baseMap.visibleBounds()?.let { b -> vm.downloadVisibleMap(b, density) } },
            onDelete = { id -> vm.offline.delete(id) },
            onDismiss = { showLayers = false },
        )
    }
}

@Composable
private fun SiteChip(s: MapUiState, onSelect: (RadarSite) -> Unit, onAuto: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val (title, subtitle) = when {
        s.mode == Mode.COMPOSITE -> "Europe" to "All radars combined"
        s.site != null -> s.site.label to s.site.name
        else -> "No radar" to "No open data here"
    }
    Box {
        ChromePanel(Modifier.height(Dimens.MinTouch).clickable { open = true }.semantics {
            contentDescription = "Radar: $title, $subtitle. Tap to choose a radar."
        }) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalArrangement = Arrangement.Center) {
                Text(title, style = Hookline.type.title, color = Hookline.colors.ink, maxLines = 1)
                Text(
                    if (s.autoSite) "$subtitle, auto" else subtitle,
                    style = Hookline.type.caption, color = Hookline.colors.inkSecondary, maxLines = 1,
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(if (s.autoSite) "Nearest radar automatically (on)" else "Nearest radar automatically", style = Hookline.type.control) },
                onClick = { open = false; onAuto() },
            )
            s.nearbySites.forEach { (site, km) ->
                DropdownMenuItem(
                    text = { Text("${site.label}  ${site.name}  $km km", style = Hookline.type.control) },
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
private fun StatusChip(status: FeedStatus, latest: Boolean, mode: Mode) {
    when {
        !latest && status == FeedStatus.Live -> Chip("Looping, not live")
        status == FeedStatus.Connecting -> Chip(if (mode == Mode.COMPOSITE) "Loading European radar…" else "Connecting to radar…")
        status == FeedStatus.Live -> Chip(if (mode == Mode.COMPOSITE) "Live composite, every 5 min" else "Live", dot = true)
        status is FeedStatus.Stale -> Chip("Not updating: ${status.message}", strong = true)
        status is FeedStatus.NoCoverage -> Chip(status.message, strong = true)
    }
}

@Composable
private fun Chip(text: String, dot: Boolean = false, strong: Boolean = false) {
    val c = Hookline.colors
    Row(
        Modifier.background(c.chrome, CircleShape)
            .border(1.dp, if (strong) c.ink else c.hairline, CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (dot) Box(Modifier.size(7.dp).background(c.ink, CircleShape))
        Text(text, style = Hookline.type.caption, color = if (strong) c.ink else c.inkSecondary, maxLines = 1)
    }
}

@Composable
private fun TiltLadder(tilts: List<Float>, selected: Float, onSelect: (Float) -> Unit) {
    if (tilts.isEmpty()) return
    ChromePanel {
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
private fun LayersIcon(color: Color) {
    Canvas(Modifier.size(22.dp)) {
        val w = size.width; val h = size.height
        val stroke = 1.8.dp.toPx()
        val top = androidx.compose.ui.graphics.Path().apply {
            moveTo(w / 2, h * 0.1f); lineTo(w * 0.95f, h * 0.35f); lineTo(w / 2, h * 0.6f); lineTo(w * 0.05f, h * 0.35f); close()
        }
        drawPath(top, color, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
        drawLine(color, Offset(w * 0.05f, h * 0.6f), Offset(w / 2, h * 0.85f), stroke)
        drawLine(color, Offset(w / 2, h * 0.85f), Offset(w * 0.95f, h * 0.6f), stroke)
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
private fun BottomDock(s: MapUiState, onProduct: (Product) -> Unit, onPlay: () -> Unit, onScrub: (Int) -> Unit) {
    val c = Hookline.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.mapVoid.copy(alpha = 0.94f))
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Legend(s)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val canPlay = s.loop.available && s.loop.frames.size >= 2
            Box(
                Modifier.size(48.dp).alpha(if (canPlay) 1f else 0.35f)
                    .border(1.dp, c.hairline, CircleShape)
                    .clickable(enabled = canPlay, onClick = onPlay)
                    .semantics { contentDescription = if (s.loop.playing) "Pause loop" else "Play loop" },
                contentAlignment = Alignment.Center,
            ) { PlayPauseIcon(s.loop.playing, c.ink) }
            Column(Modifier.weight(1f)) {
                LoopTape(s.loop, onScrub, Modifier.fillMaxWidth().height(36.dp))
                Text(
                    s.loop.loading ?: when {
                        s.mode == Mode.NONE -> ""
                        !s.loop.available && s.mode == Mode.COMPOSITE -> "Loop needs at least two composites"
                        !s.loop.available -> "Loops cover the lowest tilt for REF and velocity"
                        s.frameEndMillis != null -> MapViewModel.clock(s.frameEndMillis) + if (s.showingLatest) ", latest" else ""
                        else -> ""
                    },
                    style = Hookline.type.caption, color = c.inkSecondary, maxLines = 1,
                )
            }
            val age = s.frameAgeSeconds
            Readout(
                value = age?.let { if (it < 3600) "%d:%02d".format(Locale.US, it / 60, it % 60) else "${it / 3600} h" } ?: "–",
                caption = if (age != null && age > 600 && s.showingLatest) "scan age, stale" else "scan age",
                alignEnd = true,
            )
        }
        PillRow {
            Product.entries.forEach { p ->
                val enabled = p in s.availableProducts
                SelectPill(
                    p.label,
                    selected = p == (if (s.mode == Mode.COMPOSITE) Product.REF else s.product),
                    onClick = { if (enabled) onProduct(p) },
                    modifier = Modifier.weight(1f).alpha(if (enabled) 1f else 0.35f),
                )
            }
        }
        Text(
            listOf(s.attribution, "Map ${BaseMap.ATTRIBUTION}").filter { it.isNotEmpty() }.joinToString(". "),
            style = Hookline.type.caption, color = c.inkSecondary, maxLines = 2,
        )
    }
}

@Composable
private fun PlayPauseIcon(playing: Boolean, color: Color) {
    Canvas(Modifier.size(18.dp)) {
        val w = size.width; val h = size.height
        if (playing) {
            drawRect(color, Offset(w * 0.2f, h * 0.15f), androidx.compose.ui.geometry.Size(w * 0.2f, h * 0.7f))
            drawRect(color, Offset(w * 0.6f, h * 0.15f), androidx.compose.ui.geometry.Size(w * 0.2f, h * 0.7f))
        } else {
            val p = androidx.compose.ui.graphics.Path().apply { moveTo(w * 0.25f, h * 0.12f); lineTo(w * 0.88f, h / 2); lineTo(w * 0.25f, h * 0.88f); close() }
            drawPath(p, color)
        }
    }
}

/**
 * Instrument tape: one tick per loop frame. The selected frame is the tall
 * bright tick; tap or drag along the tape to scrub.
 */
@Composable
private fun LoopTape(loop: LoopUi, onScrub: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = Hookline.colors
    val n = loop.frames.size
    val current = loop.index ?: (n - 1)
    fun indexAt(x: Float, width: Float) = if (n <= 1) 0 else ((x / width) * (n - 1)).let { Math.round(it) }.coerceIn(0, n - 1)
    Canvas(
        modifier
            .semantics { contentDescription = if (n > 0) "Loop frame ${current + 1} of $n. Drag to scrub." else "Loop is empty" }
            .pointerInput(n) { detectTapGestures { o -> onScrub(indexAt(o.x, size.width.toFloat())) } }
            .pointerInput(n) { detectHorizontalDragGestures { change, _ -> onScrub(indexAt(change.position.x, size.width.toFloat())) } },
    ) {
        val base = size.height - 2.dp.toPx()
        drawLine(c.hairline, Offset(0f, base), Offset(size.width, base), strokeWidth = 1.dp.toPx())
        if (n == 0) return@Canvas
        for (i in 0 until n) {
            val x = if (n == 1) size.width else size.width * i / (n - 1)
            val sel = i == current
            val h = if (sel) size.height - 2.dp.toPx() else size.height * 0.45f
            drawLine(if (sel) c.ink else c.inkSecondary, Offset(x, base), Offset(x, base - h), strokeWidth = (if (sel) 3 else 2).dp.toPx())
        }
    }
}

@Composable
private fun Legend(s: MapUiState) {
    val c = Hookline.colors
    val product = if (s.mode == Mode.COMPOSITE) Product.REF else s.product
    val lut = remember(product) {
        product.palette.toLut(product.min, product.max, 24).filter { (it ushr 24) != 0 }.map { Color(it) }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            when (product) { Product.REF -> "dBZ"; Product.VEL, Product.SRV -> "m/s"; Product.CC -> "CC"; Product.ZDR -> "dB" },
            style = Hookline.type.caption, color = c.inkSecondary, modifier = Modifier.width(34.dp),
        )
        if (lut.size >= 2) Box(Modifier.weight(1f).height(6.dp).background(Brush.horizontalGradient(lut), RoundedCornerShape(3.dp)))
        if (product == Product.SRV) {
            Text(
                "${s.motion.fromDeg}° at ${s.motion.speedKt} kt",
                style = Hookline.type.caption, color = c.inkSecondary,
            )
        }
        Spacer(Modifier.width(4.dp))
    }
    if (product == Product.SRV) {
        Text("Storm motion: ${s.motion.source.lowercase()}", style = Hookline.type.caption, color = c.inkSecondary)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LayersSheet(
    intensity: MapIntensity,
    loopLength: Int,
    offline: OfflineUi,
    onIntensity: (MapIntensity) -> Unit,
    onLoopLength: (Int) -> Unit,
    onDownload: () -> Unit,
    onDelete: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Hookline.colors
    val sliderColors = SliderDefaults.colors(
        thumbColor = c.ink, activeTrackColor = c.ink, inactiveTrackColor = c.hairline,
    )
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.raised) {
        Column(
            Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Map", style = Hookline.type.title, color = c.ink)
            LabeledSlider("Roads", intensity.roads, sliderColors) { onIntensity(intensity.copy(roads = it)) }
            LabeledSlider("Place names", intensity.labels, sliderColors) { onIntensity(intensity.copy(labels = it)) }
            LabeledSlider("State and county lines", intensity.borders, sliderColors) { onIntensity(intensity.copy(borders = it)) }

            Spacer(Modifier.height(6.dp))
            Text("Loop", style = Hookline.type.title, color = c.ink)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Frames", style = Hookline.type.body, color = c.ink, modifier = Modifier.weight(1f))
                Text("$loopLength", style = Hookline.type.control, color = c.inkSecondary)
            }
            Slider(
                value = loopLength.toFloat(), onValueChange = { onLoopLength(it.roundToIntSafe()) },
                valueRange = 2f..24f, steps = 21, colors = sliderColors,
            )
            Text(
                "Earlier frames load when you open a radar, about 1–2 MB each. Takes effect next time you switch radars.",
                style = Hookline.type.caption, color = c.inkSecondary,
            )

            Spacer(Modifier.height(6.dp))
            Text("Offline maps", style = Hookline.type.title, color = c.ink)
            Text(
                "Save the map you're looking at for places with no signal. Radar still needs a connection.",
                style = Hookline.type.caption, color = c.inkSecondary,
            )
            val busy = offline.progress != null
            Box(
                Modifier.fillMaxWidth().height(52.dp)
                    .background(if (busy) c.hairline else c.ink, CircleShape)
                    .clickable(enabled = !busy, onClick = onDownload),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (busy) "Saving… ${((offline.progress ?: 0f) * 100).toInt()}%" else "Save this map area",
                    style = Hookline.type.control, color = if (busy) c.ink else c.inkOnSelected,
                )
            }
            offline.progress?.let {
                LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth(), color = c.ink, trackColor = c.hairline)
            }
            offline.message?.let { Text(it, style = Hookline.type.caption, color = c.inkSecondary) }
            offline.regions.forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(r.name, style = Hookline.type.body, color = c.ink)
                        Text(
                            r.sizeMb?.let { "%.0f MB".format(Locale.US, it) + if (r.complete) "" else ", incomplete" } ?: "",
                            style = Hookline.type.caption, color = c.inkSecondary,
                        )
                    }
                    Text(
                        "Delete",
                        style = Hookline.type.control, color = c.ink,
                        modifier = Modifier.clickable { onDelete(r.id) }.padding(12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, colors: androidx.compose.material3.SliderColors, onChange: (Float) -> Unit) {
    val c = Hookline.colors
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = Hookline.type.body, color = c.ink, modifier = Modifier.weight(1f))
            Text(if (value <= 0.01f) "Off" else "${(value * 100).toInt()}%", style = Hookline.type.control, color = c.inkSecondary)
        }
        Slider(value = value, onValueChange = onChange, colors = colors)
    }
}

private fun Float.roundToIntSafe(): Int = Math.round(this)
