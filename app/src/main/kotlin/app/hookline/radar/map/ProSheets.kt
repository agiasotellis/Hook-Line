package app.hookline.radar.map

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.hookline.radar.core.data.Fmt
import app.hookline.radar.core.data.HailRisk
import app.hookline.radar.core.data.LatLon
import app.hookline.radar.core.data.Network
import app.hookline.radar.core.data.Sites
import app.hookline.radar.core.data.StormCell
import app.hookline.radar.core.data.StormCells
import app.hookline.radar.core.data.UnitSystem
import app.hookline.radar.core.designsystem.Dimens
import app.hookline.radar.core.designsystem.Hookline
import app.hookline.radar.core.radar.MercatorProjector
import app.hookline.radar.render.RadarGlView
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import app.hookline.radar.i18n.tr
import app.hookline.radar.i18n.compass
import app.hookline.radar.i18n.hailLabel

// ---- storm cells ----------------------------------------------------------------------------------

@Composable
fun CellsSheet(s: MapUiState, onShow: (StormCell) -> Unit, onDismiss: () -> Unit) {
    val c = Hookline.colors
    val ref = s.me ?: s.places.firstOrNull()?.let { LatLon(it.lat, it.lon) }
    HooklineSheet(onDismiss) {
        Text(tr("Storms"), style = Hookline.type.title, color = c.ink)
        Text(
            tr("Cells of 40 dBZ and stronger, followed from scan to scan. Rings show where they are; lines show where they'll be in 15 and 30 minutes if they keep moving the same way.") +
                if (ref == null) "" else " " + tr("Distances are from your location."),
            style = Hookline.type.caption, color = c.inkSecondary,
        )
        if (s.cells.isEmpty()) {
            Text(tr("No strong storms in view right now."), style = Hookline.type.body, color = c.ink, modifier = Modifier.padding(vertical = 16.dp))
        }
        s.cells.forEach { cell ->
            Column(
                Modifier.fillMaxWidth().background(c.chrome, RoundedCornerShape(10.dp))
                    .border(1.dp, Color(cellColor(cell)), RoundedCornerShape(10.dp))
                    .clickable { onShow(cell) }.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(10.dp).background(Color(cellColor(cell)), CircleShape))
                    Text(tr("Storm %s", cell.id), style = Hookline.type.control, color = c.ink, modifier = Modifier.weight(1f))
                    Text("${cell.maxDbz.toInt()} dBZ", style = Hookline.type.control, color = c.ink)
                }
                if (cell.hail != HailRisk.NONE) Text(hailLabel(cell.hail), style = Hookline.type.body, color = c.ink)
                val moving = cell.speedMs?.let { sp ->
                    tr("Moving %s at %s", compass(Fmt.bearing(cell.headingDeg ?: 0.0).substringAfter(' ')), Fmt.speed(sp, s.units))
                } ?: tr("Motion after the next scan")
                Text(moving, style = Hookline.type.caption, color = c.inkSecondary)
                ref?.let { p ->
                    val (brg, dist) = MercatorProjector.bearingDistance(p.lat, p.lon, cell.lat, cell.lon)
                    val eta = StormCells.arrivalMinutes(cell, p)
                    Text(
                        tr("%s %s of you", Fmt.distance(dist, s.units), compass(Fmt.bearing(brg).substringAfter(' '))) +
                            (eta?.let { " · " + tr("heading your way, about %s min", it) } ?: ""),
                        style = Hookline.type.caption, color = if (eta != null) c.ink else c.inkSecondary,
                    )
                }
            }
        }
    }
}

fun cellColor(c: StormCell): Int = when {
    c.maxDbz >= 60 -> 0xFFE040FB.toInt()
    c.maxDbz >= 55 -> 0xFFE5312B.toInt()
    c.maxDbz >= 50 -> 0xFFF2902B.toInt()
    else -> 0xFFF2D23B.toInt()
}

// ---- archive ---------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ArchiveSheet(
    s: MapUiState,
    onDate: (LocalDate) -> Unit,
    onSite: (app.hookline.radar.core.data.RadarSite) -> Unit,
    onLoad: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Hookline.colors
    val a = s.archive ?: return
    var picking by remember { mutableStateOf(false) }
    var choosingSite by remember { mutableStateOf(false) }
    HooklineSheet(onDismiss) {
        Text(tr("Archive"), style = Hookline.type.title, color = c.ink)
        Text(
            tr("Every US radar scan since 2008, from NOAA's archive. Times are UTC. Each scan is a 5–10 MB download."),
            style = Hookline.type.caption, color = c.inkSecondary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill("${a.site.label} ${a.site.name.substringBefore(',')}") { choosingSite = !choosingSite }
            Pill(a.date.toString()) { picking = true }
        }
        if (choosingSite) {
            val center = s.me ?: a.site.location
            Sites.byDistance(center).filter { it.network == Network.NEXRAD }.take(20).forEach { site ->
                Text(
                    "${site.label}  ${site.name}",
                    style = Hookline.type.body, color = c.ink,
                    modifier = Modifier.fillMaxWidth().clickable { choosingSite = false; onSite(site) }.padding(vertical = 10.dp),
                )
            }
        }
        a.loading?.let { Text(it, style = Hookline.type.body, color = c.ink) }
        a.error?.let { Text(it, style = Hookline.type.body, color = c.ink) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            a.keys.indices.forEach { i ->
                val selected = a.index == i
                Text(
                    a.label(i),
                    style = Hookline.type.control,
                    color = if (selected) c.inkOnSelected else c.ink,
                    modifier = Modifier
                        .background(if (selected) c.ink else c.chrome, RoundedCornerShape(8.dp))
                        .clickable { onLoad(i) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }
    }
    if (picking) {
        val min = LocalDate.of(2008, 6, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val max = Instant.now().toEpochMilli()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = a.date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            yearRange = 2008..LocalDate.now().year,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis in min..max
            },
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    picking = false
                    state.selectedDateMillis?.let { onDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                }) { Text(tr("Show day")) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(tr("Cancel")) } },
        ) { DatePicker(state = state) }
    }
}

@Composable
private fun Pill(text: String, onClick: () -> Unit) {
    val c = Hookline.colors
    Text(
        text, style = Hookline.type.control, color = c.ink, maxLines = 1,
        modifier = Modifier.border(1.dp, c.hairline, RoundedCornerShape(20.dp)).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

/** Shown over the map while an archived scan is on screen. */
@Composable
fun ArchiveBar(s: MapUiState, onPrev: () -> Unit, onNext: () -> Unit, onPick: () -> Unit, onExit: () -> Unit) {
    val c = Hookline.colors
    val a = s.archive ?: return
    val i = a.index ?: return
    Row(
        Modifier.fillMaxWidth().background(c.chrome, RoundedCornerShape(Dimens.PanelRadius))
            .border(1.dp, c.ink, RoundedCornerShape(Dimens.PanelRadius)).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("‹", style = Hookline.type.title, color = c.ink, modifier = Modifier.clickable(onClick = onPrev).padding(12.dp))
        Column(Modifier.weight(1f).clickable(onClick = onPick), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(tr("Archive · %s", a.site.label), style = Hookline.type.caption, color = c.inkSecondary)
            Text(a.loading ?: "${a.date} ${a.label(i)} UTC", style = Hookline.type.control, color = c.ink, maxLines = 1)
        }
        Text("›", style = Hookline.type.title, color = c.ink, modifier = Modifier.clickable(onClick = onNext).padding(12.dp))
        Text(tr("Live"), style = Hookline.type.control, color = c.ink, modifier = Modifier.clickable(onClick = onExit).padding(12.dp))
    }
}

// ---- cross-section --------------------------------------------------------------------------------

@Composable
fun CrossSectionSheet(s: MapUiState, onDismiss: () -> Unit) {
    val c = Hookline.colors
    val cs = s.crossSection
    HooklineSheet(onDismiss) {
        Text(tr("Cross-section"), style = Hookline.type.title, color = c.ink)
        Text(
            tr("A vertical slice along your measure line: reflectivity from the ground to 15 km, built from every tilt of the latest volume. Gaps are where no beam passes."),
            style = Hookline.type.caption, color = c.inkSecondary,
        )
        s.crossSectionNote?.let { Text(it, style = Hookline.type.body, color = c.ink) }
        if (cs != null) {
            val bmp = remember(cs) { crossSectionBitmap(cs) }
            Box(Modifier.fillMaxWidth().aspectRatio(cs.width / cs.height.toFloat()).background(Color(0xFF0B1015))) {
                Image(
                    bmp.asImageBitmap(), contentDescription = tr("Cross-section of reflectivity"),
                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.None,
                )
                // Height lines every 3 km (or 10,000 ft).
                Canvas(Modifier.fillMaxSize()) {
                    val step = if (s.units == UnitSystem.IMPERIAL) 3048.0 else 3000.0
                    var z = step
                    while (z < cs.topM) {
                        val y = size.height * (1 - (z / cs.topM)).toFloat()
                        drawLine(Color(0x55ECE8DF), Offset(0f, y), Offset(size.width, y), 1f)
                        z += step
                    }
                }
            }
            Row {
                Text("A", style = Hookline.type.caption, color = c.inkSecondary, modifier = Modifier.weight(1f))
                Text(Fmt.distance(cs.distanceM, s.units), style = Hookline.type.caption, color = c.inkSecondary)
                Text("B", style = Hookline.type.caption, color = c.inkSecondary, modifier = Modifier.weight(1f).padding(start = 4.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
            Text(
                tr("Lines every %s above the radar. %s tilts.", if (s.units == UnitSystem.IMPERIAL) "10,000 ft" else "3 km", cs.tilts),
                style = Hookline.type.caption, color = c.inkSecondary,
            )
        }
    }
}

private fun crossSectionBitmap(cs: CrossSection): Bitmap {
    val lut = Product.REF.palette.toLut(Product.REF.min, Product.REF.max, 256)
    val px = IntArray(cs.width * cs.height) { i ->
        val v = cs.values[i]
        when {
            v.isNaN() -> 0xFF1A2128.toInt() // no beam here
            v < -1e29f -> 0xFF0B1015.toInt() // beam, no echo
            else -> lut[(((v - Product.REF.min) / (Product.REF.max - Product.REF.min)) * 255).toInt().coerceIn(0, 255)]
                .let { if ((it ushr 24) == 0) 0xFF0B1015.toInt() else it or 0xFF000000.toInt() }
        }
    }
    return Bitmap.createBitmap(px, cs.width, cs.height, Bitmap.Config.ARGB_8888)
}

// ---- second pane -----------------------------------------------------------------------------------

/**
 * The lower half in side-by-side mode: its own radar surface and a
 * non-interactive base map that copies the main camera.
 */
@Composable
fun SecondPane(s: MapUiState, follower: Array<BaseMap?>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val radar = remember { RadarGlView(context).also { it.renderer.setClearColor(0xFF141B22.toInt()) } }
    val map = remember { BaseMap(context, interactive = false) }
    val latest = androidx.compose.runtime.rememberUpdatedState(s)
    LaunchedEffect(map) {
        map.onCameraMove = {
            map.radarViewport(latest.value.origin, map.mapView.width, map.mapView.height)?.let(radar::setViewport)
        }
    }
    LaunchedEffect(s.intensity) { map.setIntensity(s.intensity) }
    LaunchedEffect(s.redNight) {
        map.setRedNight(s.redNight)
        radar.setRedNight(s.redNight, if (s.redNight) 0xFF0A0505.toInt() else 0xFF141B22.toInt())
    }
    DisposableEffect(Unit) {
        follower[0] = map
        map.onStart(); map.onResume(); radar.onResume()
        onDispose {
            follower[0] = null
            radar.onPause(); map.onPause(); map.onStop(); map.onDestroy()
        }
    }
    Box(modifier) {
        AndroidView(factory = { radar }, update = { v -> v.setLayers(null, s.siteLayer2); v.setOutlines(s.outlines) }, modifier = Modifier.fillMaxSize())
        AndroidView(factory = { map.mapView }, modifier = Modifier.fillMaxSize())
        Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) { PaneLabel(s.product2.label) }
    }
}

@Composable
fun PaneLabel(text: String) {
    val c = Hookline.colors
    Text(
        text, style = Hookline.type.control, color = c.ink,
        modifier = Modifier.background(c.chrome, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
fun DualIcon(color: Color) {
    Canvas(Modifier.size(22.dp)) {
        val w = size.width; val sw = 1.8.dp.toPx()
        drawRect(color, Offset(w * 0.1f, w * 0.08f), androidx.compose.ui.geometry.Size(w * 0.8f, w * 0.38f), style = androidx.compose.ui.graphics.drawscope.Stroke(sw))
        drawRect(color, Offset(w * 0.1f, w * 0.54f), androidx.compose.ui.geometry.Size(w * 0.8f, w * 0.38f), style = androidx.compose.ui.graphics.drawscope.Stroke(sw))
    }
}
