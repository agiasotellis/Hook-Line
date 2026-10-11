package app.hookline.radar.map

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.hookline.radar.BuildConfig
import app.hookline.radar.core.data.Network
import app.hookline.radar.core.data.RadarSite
import app.hookline.radar.core.data.Sites
import app.hookline.radar.core.data.WarningItem
import app.hookline.radar.core.data.WarningLevel
import app.hookline.radar.core.designsystem.Hookline
import app.hookline.radar.core.designsystem.PillRow
import app.hookline.radar.core.designsystem.SelectPill
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import app.hookline.radar.i18n.tr

/** Public privacy policy (also linked from the Play listing). */
const val PRIVACY_URL = "https://github.com/agiasotellis/couplet-radar/blob/main/docs/privacy/PRIVACY.md"

/** The sheets reachable from the main menu. */
enum class Sheet { MENU, RADARS, LAYERS, WARNINGS, STORM, PLACES, CELLS, ARCHIVE, GUIDE, ABOUT, RAIN, SIMPLE_HELP }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HooklineSheet(onDismiss: () -> Unit, scroll: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val c = Hookline.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = c.raised,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).let { if (scroll) it.verticalScroll(rememberScrollState()) else it },
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

// ---- main menu ---------------------------------------------------------------------------------

@Composable
fun MenuSheet(s: MapUiState, onPick: (Sheet) -> Unit, onDismiss: () -> Unit, top: @Composable () -> Unit = {}) {
    HooklineSheet(onDismiss) {
        BrandRow()
        top()
        val radar = when (s.mode) {
            Mode.SITE -> s.site?.let { "${it.label}, ${it.name}" + if (s.autoSite) " " + tr("(auto)") else "" } ?: tr("Radar")
            Mode.COMPOSITE -> tr("Europe composite (auto)")
            Mode.MOSAIC -> tr("US mosaic (zoom in for a radar)")
            Mode.NONE -> tr("No open radar here")
        }
        MenuRow(tr("Radars"), radar) { onPick(Sheet.RADARS) }
        MenuRow(
            tr("Warnings"),
            when (val n = s.warnings.size) { 0 -> tr("None in view"); 1 -> tr("1 in view"); else -> tr("%s in view", n) },
        ) { onPick(Sheet.WARNINGS) }
        MenuRow(
            tr("Storm Mode"),
            when {
                !s.stormModeEnabled -> tr("Off")
                s.homeLabel == null -> tr("On, waiting for your location")
                else -> tr("Watching %s", s.homeLabel)
            },
        ) { onPick(Sheet.STORM) }
        MenuRow(
            tr("Places"),
            s.places.firstOrNull { it.outlook != null }?.let { "${it.name}: ${it.outlook}" }
                ?: if (s.places.isEmpty()) tr("Save places for warnings and rain alerts") else tr("%s watched", s.places.size),
        ) { onPick(Sheet.PLACES) }
        if (!s.simple) MenuRow(tr("Storms"), if (s.cells.isEmpty()) tr("No strong storms in view") else tr("%s tracked, strongest %s dBZ", s.cells.size, s.cells.first().maxDbz.toInt())) { onPick(Sheet.CELLS) }
        if (!s.simple) MenuRow(tr("Archive (US)"), tr("Any US radar scan since 2008")) { onPick(Sheet.ARCHIVE) }
        MenuRow(tr("Layers, loops and offline maps"), if (s.satellite) tr("Satellite where there's no radar") else tr("Satellite off")) { onPick(Sheet.LAYERS) }
        MenuRow(tr("Guide"), tr("What the colours mean, gestures")) { onPick(Sheet.GUIDE) }
        MenuRow(tr("About and data sources"), tr("Version %s", BuildConfig.VERSION_NAME)) { onPick(Sheet.ABOUT) }
    }
}

@Composable
private fun MenuRow(title: String, subtitle: String, onClick: () -> Unit) {
    val c = Hookline.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Hookline.type.control, color = c.ink)
            Text(subtitle, style = Hookline.type.caption, color = c.inkSecondary, maxLines = 1)
        }
        Text("›", style = Hookline.type.title, color = c.inkSecondary)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(c.hairline))
}

// ---- radars ---------------------------------------------------------------------------------------

@Composable
fun RadarsSheet(s: MapUiState, onSelect: (RadarSite) -> Unit, onAuto: () -> Unit, onDismiss: () -> Unit) {
    val c = Hookline.colors
    var query by remember { mutableStateOf("") }
    HooklineSheet(onDismiss, scroll = false) {
        Text(tr("Radars"), style = Hookline.type.title, color = c.ink)
        Text(
            tr(
                "%s in the US and %s in Europe. Zoomed out you see the European composite or the US mosaic; elsewhere, satellite.",
                Sites.ALL.count { it.network == Network.NEXRAD }, Sites.ALL.count { it.network == Network.OPERA },
            ),
            style = Hookline.type.caption, color = c.inkSecondary,
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text(tr("Search by code, city or country"), style = Hookline.type.body) },
            modifier = Modifier.fillMaxWidth(),
            textStyle = Hookline.type.body,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = c.ink, unfocusedTextColor = c.ink,
                focusedBorderColor = c.ink, unfocusedBorderColor = c.hairline, cursorColor = c.ink,
                focusedPlaceholderColor = c.inkSecondary, unfocusedPlaceholderColor = c.inkSecondary,
            ),
        )
        ToggleRow(
            tr("Nearest radar automatically"),
            tr("Switches radar as you move the map"),
            s.autoSite,
        ) { if (it) onAuto() }
        val q = query.trim()
        val rows: List<Pair<RadarSite, String>> = if (q.isEmpty()) {
            s.nearbySites.map { (site, km) -> site to "$km km" }
        } else {
            Sites.ALL.filter {
                it.id.contains(q, true) || it.name.contains(q, true) || it.country.equals(q, true)
            }.take(60).map { it to (if (it.network == Network.NEXRAD) "US" else it.country.uppercase()) }
        }
        Text(if (q.isEmpty()) tr("Nearest to the map centre") else tr("%s found", rows.size), style = Hookline.type.caption, color = c.inkSecondary)
        LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(0.6f)) {
            items(rows, key = { it.first.id }) { (site, right) ->
                val selected = site.id == s.site?.id
                Row(
                    Modifier.fillMaxWidth().height(52.dp).clickable { onSelect(site) }.padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        site.label, style = Hookline.type.control, color = if (selected) c.ink else c.inkSecondary,
                        modifier = Modifier.width(64.dp),
                    )
                    Text(site.name, style = Hookline.type.body, color = c.ink, maxLines = 1, modifier = Modifier.weight(1f))
                    Text(right, style = Hookline.type.caption, color = c.inkSecondary)
                }
            }
        }
    }
}

// ---- warnings -------------------------------------------------------------------------------------

private val WHEN: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
private fun fmt(i: Instant?): String? = i?.let { WHEN.format(it.atZone(ZoneId.systemDefault())) }

@Composable
fun WarningsSheet(s: MapUiState, onShow: (WarningItem) -> Unit, onDismiss: () -> Unit) {
    val c = Hookline.colors
    var open by remember { mutableStateOf<String?>(null) }
    HooklineSheet(onDismiss) {
        Text(tr("Warnings"), style = Hookline.type.title, color = c.ink)
        Text(
            tr("Official warnings for the area on screen: NWS in the US, national weather services via MeteoAlarm in Europe. Hookline is not an official warning service; always follow local authorities."),
            style = Hookline.type.caption, color = c.inkSecondary,
        )
        if (s.warnings.isEmpty()) {
            Text(
                tr("No active warnings in view. Move the map to check another area."),
                style = Hookline.type.body, color = c.ink, modifier = Modifier.padding(vertical = 16.dp),
            )
        }
        s.warnings.take(80).forEach { w ->
            val expanded = open == w.id
            Column(
                Modifier.fillMaxWidth()
                    .background(c.chrome, RoundedCornerShape(10.dp))
                    .border(1.dp, Color(w.argb), RoundedCornerShape(10.dp))
                    .clickable { open = if (expanded) null else w.id }
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(10.dp).background(Color(w.argb), CircleShape))
                    Text(w.event, style = Hookline.type.control, color = c.ink, modifier = Modifier.weight(1f))
                    Text(w.source, style = Hookline.type.caption, color = c.inkSecondary)
                }
                if (w.area.isNotBlank()) Text(w.area, style = Hookline.type.caption, color = c.inkSecondary, maxLines = if (expanded) 6 else 1)
                val times = listOfNotNull(fmt(w.onset)?.let { tr("From %s", it) }, fmt(w.expires)?.let { tr("until %s", it) }).joinToString(" ")
                if (times.isNotEmpty()) Text(times, style = Hookline.type.caption, color = c.inkSecondary)
                if (expanded) {
                    if (w.headline != w.event) Text(w.headline, style = Hookline.type.body, color = c.ink)
                    w.description?.takeIf { it.isNotBlank() }?.let { Text(it.trim(), style = Hookline.type.body, color = c.ink) }
                    w.instruction?.takeIf { it.isNotBlank() }?.let { Text(it.trim(), style = Hookline.type.body, color = c.ink) }
                    if (w.polygons.isNotEmpty()) {
                        Text(
                            tr("Show on map"),
                            style = Hookline.type.control, color = c.ink,
                            modifier = Modifier.padding(top = 4.dp).clickable { onShow(w) }.padding(vertical = 8.dp),
                        )
                    }
                }
            }
        }
        if (s.warnings.size > 80) Text(tr("Zoom in to see the rest."), style = Hookline.type.caption, color = c.inkSecondary)
    }
}

// ---- places ---------------------------------------------------------------------------------------

@Composable
fun PlacesSheet(
    s: MapUiState,
    onAdd: (String) -> Unit,
    onRemove: (Long) -> Unit,
    onWarnings: (Long, Boolean) -> Unit,
    onRain: (Long, Boolean) -> Unit,
    onWifiOnly: (Boolean) -> Unit,
    onShow: (app.hookline.radar.stormmode.Place) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Hookline.colors
    var name by remember { mutableStateOf("") }
    HooklineSheet(onDismiss) {
        Text(tr("Places"), style = Hookline.type.title, color = c.ink)
        Text(
            tr("Storm Mode watches each place for official warnings and rain starting in the next hour. The widget shows the first place with rain alerts on."),
            style = Hookline.type.caption, color = c.inkSecondary,
        )
        s.places.forEach { p ->
            Column(
                Modifier.fillMaxWidth().background(c.chrome, RoundedCornerShape(10.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = Hookline.type.control, color = c.ink)
                        Text(p.outlook ?: tr("Not checked yet"), style = Hookline.type.caption, color = c.inkSecondary)
                    }
                    Text(tr("Show"), style = Hookline.type.control, color = c.ink, modifier = Modifier.clickable { onShow(p) }.padding(10.dp))
                    if (p.id != 0L) {
                        Text(tr("Delete"), style = Hookline.type.control, color = c.inkSecondary, modifier = Modifier.clickable { onRemove(p.id) }.padding(10.dp))
                    }
                }
                ToggleRow(tr("Warnings"), null, p.warnings) { onWarnings(p.id, it) }
                ToggleRow(tr("Rain starting soon"), null, p.rain) { onRain(p.id, it) }
            }
        }
        Text(tr("Add a place"), style = Hookline.type.control, color = c.ink, modifier = Modifier.padding(top = 6.dp))
        Text(tr("Move the map so the place is in the centre, name it and save."), style = Hookline.type.caption, color = c.inkSecondary)
        OutlinedTextField(
            value = name, onValueChange = { name = it }, singleLine = true,
            placeholder = { Text(tr("Name, e.g. Work"), style = Hookline.type.body) },
            modifier = Modifier.fillMaxWidth(), textStyle = Hookline.type.body,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = c.ink, unfocusedTextColor = c.ink,
                focusedBorderColor = c.ink, unfocusedBorderColor = c.hairline, cursorColor = c.ink,
                focusedPlaceholderColor = c.inkSecondary, unfocusedPlaceholderColor = c.inkSecondary,
            ),
        )
        Box(
            Modifier.fillMaxWidth().height(52.dp).background(c.ink, CircleShape).clickable { onAdd(name); name = "" },
            contentAlignment = Alignment.Center,
        ) { Text(tr("Save the map centre"), style = Hookline.type.control, color = c.inkOnSelected) }
        ToggleRow(
            tr("Rain checks on Wi-Fi only"),
            tr("Each check downloads 1–3 MB. Otherwise every 30 min on mobile data, about 100 MB a month."),
            s.rainWifiOnly, onWifiOnly,
        )
    }
}

// ---- storm mode -----------------------------------------------------------------------------------

@Composable
fun StormModeSheet(
    s: MapUiState,
    onToggle: (Boolean) -> Unit,
    onLevel: (WarningLevel) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Hookline.colors
    val context = LocalContext.current
    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun needsPermission() = Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    HooklineSheet(onDismiss) {
        Text(tr("Storm Mode"), style = Hookline.type.title, color = c.ink)
        Text(
            tr("Checks the official warnings for your location every 15 minutes in the background and notifies you once per warning."),
            style = Hookline.type.body, color = c.ink,
        )
        ToggleRow(tr("Storm Mode"), if (s.stormModeEnabled) tr("On") else tr("Off"), s.stormModeEnabled) { on ->
            onToggle(on)
            if (on && needsPermission()) notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (s.stormModeEnabled && needsPermission()) {
            Text(
                tr("Notifications are blocked. Tap to allow them."),
                style = Hookline.type.control, color = c.ink,
                modifier = Modifier.clickable { notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }.padding(vertical = 8.dp),
            )
        }
        Text(tr("Watching"), style = Hookline.type.control, color = c.ink)
        Text(
            s.homeLabel ?: tr("Your location isn't known yet. Allow location access, or open the app where you live once."),
            style = Hookline.type.body, color = c.inkSecondary,
        )
        Text(
            tr("Set from your device location (US and the 38 MeteoAlarm countries). Saved places in Menu › Places each have their own warning and rain switches."),
            style = Hookline.type.caption, color = c.inkSecondary,
        )
        Spacer(Modifier.height(4.dp))
        Text(tr("United States"), style = Hookline.type.control, color = c.ink)
        Text(tr("Tornado, severe thunderstorm and flash flood warnings from the NWS."), style = Hookline.type.caption, color = c.inkSecondary)
        Spacer(Modifier.height(4.dp))
        Text(tr("Europe: notify me for"), style = Hookline.type.control, color = c.ink)
        PillRow {
            listOf(WarningLevel.YELLOW to tr("Yellow and up"), WarningLevel.ORANGE to tr("Orange and red"), WarningLevel.RED to tr("Red only")).forEach { (l, label) ->
                SelectPill(label, selected = s.euMinLevel == l, onClick = { onLevel(l) }, modifier = Modifier.weight(1f))
            }
        }
        Text(
            tr("Yellow: be aware. Orange: be prepared, dangerous weather expected. Red: take action, very dangerous weather."),
            style = Hookline.type.caption, color = c.inkSecondary,
        )
        Text(
            tr("Updated warnings for the same event and place don't notify again unless they get more severe."),
            style = Hookline.type.caption, color = c.inkSecondary,
        )
        AlertSettingsSection()
        DeliveryCheck()
    }
}

@Composable
fun ToggleRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = Hookline.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Switch) { onChange(!checked) }
            .semantics { contentDescription = if (checked) tr("%s, on", title) else tr("%s, off", title) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Hookline.type.body, color = c.ink)
            subtitle?.let { Text(it, style = Hookline.type.caption, color = c.inkSecondary) }
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = c.inkOnSelected, checkedTrackColor = c.ink,
                uncheckedThumbColor = c.inkSecondary, uncheckedTrackColor = c.chrome, uncheckedBorderColor = c.hairline,
            ),
        )
    }
}

// ---- guide and about ----------------------------------------------------------------------------

@Composable
fun GuideSheet(onDismiss: () -> Unit) {
    HooklineSheet(onDismiss) {
        Heading(tr("Guide"))
        Section(tr("Moving around"), tr("Pinch to zoom, drag to pan. Long-press anywhere for the exact value, what it means and how high the beam is there. Tap the card to close it."))
        Section(tr("What you see"), tr("Zoomed in near a radar you get that radar's own data, every tilt. Zoomed out you get the European composite (all radars combined, every 5 minutes) or NOAA's US mosaic. Where no radar shares data openly, Hookline shows infrared satellite: the whiter the cloud, the colder and higher its top."))
        Section(tr("REF – reflectivity"), tr("How much the radar beam is reflected, in dBZ. Greens are light rain, yellows and oranges heavy rain, reds and purples very heavy rain or hail."))
        Section(tr("VEL – velocity"), tr("Wind toward the radar (green) or away from it (red). A tight pair of strong green and red next to each other is rotation, the couplet a tornado warning is often based on."))
        Section(tr("SRV – storm-relative velocity"), tr("Velocity with the storm's own motion removed, so rotation inside a moving storm stands out. Hookline uses the motion in the nearest NWS warning, otherwise a default it tells you about."))
        Section(tr("CC – correlation coefficient"), tr("How alike the targets are. Rain and snow are near 1. A round drop below 0.8 inside a hook, where velocity shows rotation, is often tornado debris. Available for US radars and German radars."))
        Section(tr("ZDR – differential reflectivity"), tr("Shape of the targets. Large flattened raindrops read high; hail and snow read near zero."))
        Section(tr("Tilts"), tr("The ladder on the right picks the beam angle. The lowest is closest to the ground; higher ones look into the upper storm."))
        Section(tr("Loops"), tr("Press play at the bottom. Drag along the tape to step through frames. Loops use the lowest tilt."))
        Section(tr("Your location and measuring"), tr("The target button shows where you are and follows you as you move. The ruler measures distance and bearing between two taps, and how long a storm at the current speed takes to cover it."))
        Section(tr("Rain starting soon"), tr("For each place, Hookline looks at the latest radar and how the rain is moving, and tells you when rain should arrive in the next hour. It can't foresee storms that haven't formed yet."))
        Section(tr("Lightning"), tr("Crosses are lightning flashes in the last 15 minutes, white for the newest. Americas: NOAA GOES satellites. Europe and Africa: Meteosat's lightning imager, shown as shaded areas."))
        Section(tr("Storms"), tr("Strong cells (40 dBZ and up) get a ring coloured by strength and a line to where they'll be in 15 and 30 minutes. The Storms list says which are heading your way."))
        Section(tr("Side by side"), tr("The split-screen button shows velocity under reflectivity (or the other way round), moving together, the usual way to spot rotation."))
        Section(tr("Cross-section"), tr("Measure a line across a storm, then tap Cross-section to see a slice from the ground up: tall, strong cores and overhangs show where hail grows."))
        Section(tr("Hail and rotation (US)"), tr("From NOAA's MRMS: where radar estimated hail in the last 24 hours, or where rotation was detected in the last hour. Long-press for the value."))
        Section(tr("Archive (US)"), tr("Open any NEXRAD scan since 2008 from NOAA's archive and step through the day."))
        Section(tr("Sharing"), tr("The share button sends a picture of the map, or the loop as an animated GIF, with the data credits included."))
        Section(tr("Warnings"), tr("Coloured outlines on the map: NWS warnings in the US; yellow, orange and red MeteoAlarm warnings in Europe. Open the menu for the full text."))
    }
}

@Composable
fun AboutSheet(onDismiss: () -> Unit) {
    HooklineSheet(onDismiss) {
        BrandRow()
        Text(tr("Version %s. One price, no subscription, no account, no ads.", BuildConfig.VERSION_NAME), style = Hookline.type.body, color = Hookline.colors.ink)
        Section(
            tr("Not an official warning service"),
            tr("Hookline shows public data as it arrives and can be delayed or wrong. For safety decisions follow your national weather service and local authorities."),
        )
        Text(
            tr("Hookline is an independent app. It is not affiliated with, endorsed by or acting on behalf of any government or weather service. Official sources: weather.gov (US), meteoalarm.org (Europe) and your national weather service."),
            style = Hookline.type.caption, color = Hookline.colors.inkSecondary,
        )
        val uri = androidx.compose.ui.platform.LocalUriHandler.current
        Text(
            tr("Privacy policy"),
            style = Hookline.type.control, color = Hookline.colors.ink,
            modifier = Modifier.clickable { runCatching { uri.openUri(PRIVACY_URL) } }.padding(vertical = 10.dp),
        )
        Heading(tr("Data sources"))
        Section(tr("US radar and warnings"), "NOAA / National Weather Service: NEXRAD Level II via the NOAA Open Data Dissemination program on AWS; warnings from api.weather.gov; MRMS radar mosaic from mapservices.weather.noaa.gov.")
        Section(tr("European radar"), "EUMETNET OPERA and its member national weather services, via the EUMETNET Open Radar Data service. Licence CC BY 4.0.")
        Section(tr("German dual-pol (CC, ZDR)"), "Source: Deutscher Wetterdienst. Licence CC BY 4.0.")
        Section(tr("European warnings"), "MeteoAlarm, EUMETNET and the national weather services. Licence CC BY 4.0. Region outlines from MeteoAlarm geocodes, simplified.")
        Section(tr("Satellite"), "Imagery from NASA's Global Imagery Browse Services (GIBS), part of NASA's Earth Science Data and Information System (ESDIS): NOAA GOES-East and GOES-West, JMA Himawari. Contains modified EUMETSAT Meteosat data (CC BY 4.0), hourly.")
        Section(tr("Lightning"), "Americas: NOAA GOES-East/West Geostationary Lightning Mapper via NOAA Open Data Dissemination. Europe and Africa: contains modified EUMETSAT Meteosat data (MTG Lightning Imager, CC BY 4.0).")
        Section(tr("Hail, rotation and archive (US)"), "NOAA MRMS swaths and the NEXRAD Level II archive, via the NOAA Open Data Dissemination program on AWS.")
        Section(tr("Rain alerts"), "EUMETNET OPERA rain-rate composite (CC BY 4.0) in Europe; NOAA MRMS in the US.")
        Section(tr("Base map"), "OpenFreeMap, OpenMapTiles, © OpenStreetMap contributors.")
        Text(
            tr("Hookline is not affiliated with or endorsed by any of these organisations."),
            style = Hookline.type.caption, color = Hookline.colors.inkSecondary,
        )
    }
}

@Composable
private fun BrandRow() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(app.hookline.radar.R.drawable.ic_hookline_mark),
            contentDescription = null,
            modifier = Modifier.size(30.dp),
        )
        Text("Hookline", style = Hookline.type.title, color = Hookline.colors.ink)
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = Hookline.type.title, color = Hookline.colors.ink, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun Section(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = Hookline.type.control, color = Hookline.colors.ink)
        Text(body, style = Hookline.type.body, color = Hookline.colors.inkSecondary)
    }
}
