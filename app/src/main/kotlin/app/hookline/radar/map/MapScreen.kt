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
import app.hookline.radar.core.data.WarningItem
import app.hookline.radar.core.designsystem.ChromePanel
import app.hookline.radar.core.designsystem.Dimens
import app.hookline.radar.core.designsystem.HazardSwatch
import app.hookline.radar.core.designsystem.Hookline
import app.hookline.radar.core.designsystem.PillRow
import app.hookline.radar.core.designsystem.Readout
import app.hookline.radar.core.designsystem.SelectPill
import app.hookline.radar.render.RadarGlView
import java.util.Locale
import kotlinx.coroutines.launch

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
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    // Loading screen: until the map style and the first radar picture are in (or 10 s at most).
    var mapReady by remember { mutableStateOf(false) }
    var loadingDone by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(10_000); loadingDone = true }
    val dataReady = s.siteLayer != null || s.compositeLayer != null || s.mode == Mode.MOSAIC ||
        s.status is FeedStatus.Stale || (s.mode == Mode.NONE && s.status is FeedStatus.NoCoverage)
    LaunchedEffect(mapReady, dataReady) { if (mapReady && dataReady) loadingDone = true }
    var following by remember { mutableStateOf(false) }
    var shareMenu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val share = remember { ShareActions(context, radar, baseMap, vm) }
    var crash by remember { mutableStateOf(app.hookline.radar.CrashLog.read(context)) }

    // The map owns gestures; the radar follows its camera exactly.
    LaunchedEffect(baseMap) {
        baseMap.onCameraMove = {
            baseMap.radarViewport(latest.origin, baseMap.mapView.width, baseMap.mapView.height)?.let(radar::setViewport)
        }
        baseMap.onCameraIdle = vm::onCameraIdle
        baseMap.onLongPress = vm::inspect
        baseMap.onStyleLoaded = { mapReady = true; baseMap.showLocation(context) }
        baseMap.onTap = { p -> vm.measureTap(app.hookline.radar.core.data.LatLon(p.latitude, p.longitude)) }
        baseMap.onFollowDismissed = { following = false }
    }
    LaunchedEffect(s.camera?.seq) { s.camera?.let { baseMap.moveTo(it.lat, it.lon, it.zoom) } }
    LaunchedEffect(s.intensity) { baseMap.setIntensity(s.intensity) }
    LaunchedEffect(s.imagery) { baseMap.setImagery(s.imagery) }
    LaunchedEffect(s.redNight) {
        baseMap.setRedNight(s.redNight)
        radar.setRedNight(s.redNight, if (s.redNight) 0xFF0A0505.toInt() else 0xFF141B22.toInt())
    }

    // Start where the user is (coarse location is enough to pick radars).
    val locationPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            DeviceLocation.request(context) { lat, lon -> vm.onLocation(lat, lon) }
            baseMap.showLocation(context)
        }
    }
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
                ChromePanel(
                    Modifier.size(Dimens.MinTouch).clickable { sheet = Sheet.MENU }.semantics { contentDescription = "Menu" },
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { MenuIcon(c.ink) }
                }
                SiteChip(s, onClick = { sheet = Sheet.RADARS })
                s.homeWarning?.let { w ->
                    WarningBanner(w, modifier = Modifier.weight(1f).clickable { sheet = Sheet.WARNINGS })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusChip(s.status, s.showingLatest, s.mode)
                if (s.warnings.isNotEmpty()) {
                    Box(Modifier.clickable { sheet = Sheet.WARNINGS }) {
                        Chip(if (s.warnings.size == 1) "1 warning" else "${s.warnings.size} warnings", swatch = Color(s.warnings.first().argb))
                    }
                }
                if (s.stormModeOn) Box(Modifier.clickable { sheet = Sheet.STORM }) { Chip("Storm Mode", dot = true) }
            }
        }

        Column(
            Modifier.align(Alignment.CenterEnd).padding(end = Dimens.EdgeInset),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.End,
        ) {
            TiltLadder(tilts = s.tilts, selected = s.tilt, onSelect = vm::selectTilt)
            MapButton(if (following) "Stop following your location" else "Show and follow your location", active = following, onClick = {
                if (!DeviceLocation.hasPermission(context)) {
                    locationPermission.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
                } else if (baseMap.showLocation(context)) {
                    following = !following
                    baseMap.setFollow(following)
                }
            }) { LocateIcon(it, following) }
            MapButton("Measure distance", active = s.measure != null, onClick = {
                if (s.measure == null) vm.startMeasure() else vm.closeMeasure()
            }) { RulerIcon(it) }
            Box {
                MapButton("Share", onClick = { if (busy == null) shareMenu = true }) { ShareIcon(it) }
                androidx.compose.material3.DropdownMenu(expanded = shareMenu, onDismissRequest = { shareMenu = false }) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Share picture", style = Hookline.type.control) },
                        onClick = { shareMenu = false; scope.launch { runCatching { share.still { busy = it } } } },
                    )
                    val canLoop = s.loop.available && s.loop.frames.size >= 2
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(if (canLoop) "Share loop (GIF)" else "Share loop (needs a loop)", style = Hookline.type.control) },
                        enabled = canLoop,
                        onClick = { shareMenu = false; scope.launch { runCatching { share.loop { busy = it } } } },
                    )
                }
            }
            ChromePanel(
                Modifier.size(Dimens.MinTouch).clickable { sheet = Sheet.LAYERS }
                    .semantics { contentDescription = "Layers and offline maps" },
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LayersIcon(c.ink) }
            }
        }

        Column(Modifier.align(Alignment.BottomCenter)) {
            crash?.let { report ->
                CrashCard(
                    onSend = {
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_SUBJECT, "Hookline crash report")
                            putExtra(android.content.Intent.EXTRA_TEXT, report)
                        }
                        context.startActivity(android.content.Intent.createChooser(send, "Send crash report"))
                        app.hookline.radar.CrashLog.clear(context); crash = null
                    },
                    onDismiss = { app.hookline.radar.CrashLog.clear(context); crash = null },
                )
            }
            busy?.let { Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) { Chip(it, dot = true) } }
            if (s.measure != null) MeasureCard(s, onClear = vm::startMeasure, onClose = vm::closeMeasure)
            s.inspector?.let { InspectorCard(it, onDismiss = vm::dismissInspector) }
            BottomDock(s, onProduct = vm::selectProduct, onPlay = vm::togglePlay, onScrub = vm::scrubTo)
        }
    }

    val close = { sheet = null }
    LoadingScreen(
        visible = !loadingDone,
        status = when {
            !mapReady -> "Loading map"
            s.mode == Mode.COMPOSITE -> "Loading European radar"
            s.mode == Mode.SITE && s.site != null -> "Connecting to ${s.site!!.label}"
            else -> "Finding radar near you"
        },
    )
    when (sheet) {
        Sheet.MENU -> MenuSheet(s, onPick = { sheet = it }, onDismiss = close)
        Sheet.RADARS -> RadarsSheet(
            s,
            onSelect = { vm.selectSite(it); sheet = null },
            onAuto = { vm.setAutoSite(); sheet = null },
            onDismiss = close,
        )
        Sheet.WARNINGS -> WarningsSheet(s, onShow = { vm.showWarning(it); sheet = null }, onDismiss = close)
        Sheet.STORM -> StormModeSheet(s, onToggle = vm::setStormMode, onLevel = vm::setEuMinLevel, onDismiss = close)
        Sheet.GUIDE -> GuideSheet(close)
        Sheet.ABOUT -> AboutSheet(close)
        Sheet.LAYERS -> {
            val offline by vm.offline.ui.collectAsStateWithLifecycle()
            val density = LocalDensity.current.density
            LayersSheet(
                s = s,
                offline = offline,
                onIntensity = vm::setIntensity,
                onLoopLength = vm::setLoopLength,
                onSatellite = vm::setSatellite,
                onUnits = vm::setUnits,
                onRedNight = vm::setRedNight,
                onWarnings = vm::setShowWarnings,
                onDownload = { baseMap.visibleBounds()?.let { b -> vm.downloadVisibleMap(b, density) } },
                onDelete = { id -> vm.offline.delete(id) },
                onDismiss = close,
            )
        }
        null -> Unit
    }
}

@Composable
private fun CrashCard(onSend: () -> Unit, onDismiss: () -> Unit) {
    val c = Hookline.colors
    Column(
        Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth()
            .background(c.raised, RoundedCornerShape(Dimens.PanelRadius))
            .border(1.dp, c.ink, RoundedCornerShape(Dimens.PanelRadius))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Hookline closed unexpectedly last time", style = Hookline.type.control, color = c.ink)
        Text(
            "A technical report was saved on this phone. Sending it shows exactly what went wrong; it contains no location or personal data.",
            style = Hookline.type.caption, color = c.inkSecondary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Send report", style = Hookline.type.control, color = c.ink, modifier = Modifier.clickable(onClick = onSend).padding(vertical = 8.dp))
            Text("Dismiss", style = Hookline.type.control, color = c.inkSecondary, modifier = Modifier.clickable(onClick = onDismiss).padding(vertical = 8.dp))
        }
    }
}

@Composable
private fun MenuIcon(color: Color) {
    Canvas(Modifier.size(22.dp)) {
        val stroke = 2.dp.toPx()
        for (f in listOf(0.25f, 0.5f, 0.75f)) {
            drawLine(color, Offset(size.width * 0.12f, size.height * f), Offset(size.width * 0.88f, size.height * f), stroke)
        }
    }
}

@Composable
private fun SiteChip(s: MapUiState, onClick: () -> Unit) {
    val (title, subtitle) = when {
        s.mode == Mode.COMPOSITE -> "Europe" to "All radars combined"
        s.mode == Mode.MOSAIC -> "US mosaic" to "Zoom in for a radar"
        s.site != null -> s.site.label to s.site.name
        s.satellite -> "Satellite" to "No open radar here"
        else -> "No radar" to "No open data here"
    }
    Box {
        ChromePanel(Modifier.height(Dimens.MinTouch).clickable(onClick = onClick).semantics {
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
    }
}

@Composable
private fun WarningBanner(w: WarningItem, modifier: Modifier = Modifier) {
    val c = Hookline.colors
    val color = Color(w.argb)
    val headline = w.event
    val detail = "Home" + (w.expires?.let { ", until " + MapViewModel.clock(it.toEpochMilli()) } ?: "")
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
            Text(headline, style = Hookline.type.control, color = c.ink, maxLines = 1)
            Text(detail, style = Hookline.type.caption, color = c.inkSecondary, maxLines = 1)
        }
    }
}

@Composable
private fun StatusChip(status: FeedStatus, latest: Boolean, mode: Mode) {
    when {
        !latest && status == FeedStatus.Live -> Chip("Looping, not live")
        mode == Mode.MOSAIC -> Chip("US mosaic, updates every few minutes", dot = true)
        status == FeedStatus.Connecting -> Chip(if (mode == Mode.COMPOSITE) "Loading European radar…" else "Connecting to radar…")
        status == FeedStatus.Live -> Chip(if (mode == Mode.COMPOSITE) "Live composite, every 5 min" else "Live", dot = true)
        status is FeedStatus.Stale -> Chip("Not updating: ${status.message}", strong = true)
        status is FeedStatus.NoCoverage -> Chip(status.message, strong = true)
    }
}

@Composable
private fun Chip(text: String, dot: Boolean = false, strong: Boolean = false, swatch: Color? = null) {
    val c = Hookline.colors
    Row(
        Modifier.background(c.chrome, CircleShape)
            .border(1.dp, if (strong) c.ink else c.hairline, CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (dot) Box(Modifier.size(7.dp).background(c.ink, CircleShape))
        swatch?.let { Box(Modifier.size(9.dp).background(it, CircleShape)) }
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
                        s.mode == Mode.MOSAIC -> "Loops: zoom in to a radar"
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
                    selected = p == (if (s.mode != Mode.SITE) Product.REF else s.product),
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
    val product = if (s.mode != Mode.SITE) Product.REF else s.product
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
                "${s.motion.fromDeg}° at ${app.hookline.radar.core.data.Fmt.speed(s.motion.speedKt * 0.514444, s.units)}",
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
    s: MapUiState,
    offline: OfflineUi,
    onIntensity: (MapIntensity) -> Unit,
    onLoopLength: (Int) -> Unit,
    onSatellite: (Boolean) -> Unit,
    onWarnings: (Boolean) -> Unit,
    onUnits: (app.hookline.radar.core.data.UnitSystem) -> Unit,
    onRedNight: (Boolean) -> Unit,
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
            val intensity = s.intensity
            val loopLength = s.loopLength
            Text("Layers", style = Hookline.type.title, color = c.ink)
            ToggleRow("Satellite where there's no radar", "Infrared clouds from GOES, Meteosat and Himawari", s.satellite, onSatellite)
            ToggleRow("Warnings on the map", "NWS in the US, MeteoAlarm in Europe", s.showWarnings, onWarnings)
            ToggleRow("Night vision", "Everything in dim red, to keep your eyes adjusted outdoors", s.redNight, onRedNight)
            Text("Units", style = Hookline.type.body, color = c.ink)
            PillRow {
                app.hookline.radar.core.data.UnitSystem.entries.forEach { u ->
                    SelectPill(
                        if (u == app.hookline.radar.core.data.UnitSystem.METRIC) "km, m, km/h" else "mi, ft, mph",
                        selected = s.units == u, onClick = { onUnits(u) }, modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("Map", style = Hookline.type.title, color = c.ink)
            LabeledSlider("Roads", intensity.roads, sliderColors) { onIntensity(intensity.copy(roads = it)) }
            LabeledSlider("Place names", intensity.labels, sliderColors) { onIntensity(intensity.copy(labels = it)) }
            LabeledSlider("Borders", intensity.borders, sliderColors) { onIntensity(intensity.copy(borders = it)) }

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
