package app.hookline.radar.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.hookline.radar.core.data.Confidence
import app.hookline.radar.core.data.RainForecast
import app.hookline.radar.core.data.RainIntensity
import app.hookline.radar.core.data.RainSource
import app.hookline.radar.core.designsystem.Dimens
import app.hookline.radar.core.designsystem.Hookline
import app.hookline.radar.i18n.ForecastText
import app.hookline.radar.i18n.tr

private val RainLight = Color(0xFF6FB6F0)
private val RainStrong = Color(0xFF2A63D6)
private val RainHeavy = Color(0xFFE4472F)

/**
 * The headline: will it rain where you are, and when. Always a range,
 * always with how sure it is; tap for the full hour and the reasons.
 */
@Composable
fun RainCardView(card: RainCard, now: Long, onClick: () -> Unit, onAllowLocation: () -> Unit, modifier: Modifier = Modifier) {
    val c = Hookline.colors
    val f = card.forecast
    val (title, sub) = when {
        f != null -> ForecastText.headline(f, now) to listOfNotNull(
            card.place,
            ForecastText.confidence(f.confidence),
            f.ageMinutes(now).takeIf { it >= 10 }?.let { tr("radar %s min old", it) },
        ).joinToString(" · ")
        card.status == RainCardStatus.NEED_LOCATION -> tr("Will it rain here?") to tr("Allow location to see the next hour")
        card.status == RainCardStatus.LOADING -> tr("Checking the radar for rain…") to (card.place ?: "")
        card.status == RainCardStatus.NO_COVERAGE -> tr("No rain radar here") to tr("Rain forecasts cover the US and Europe")
        else -> tr("Rain forecast unavailable") to tr("Couldn't reach the radar data. Trying again shortly.")
    }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .background(c.chrome, RoundedCornerShape(Dimens.PanelRadius))
            .border(1.dp, if (f?.result?.rainingNow == true || f?.arrivalWindow != null) c.ink.copy(alpha = 0.5f) else c.hairline, RoundedCornerShape(Dimens.PanelRadius))
            .clickable(onClick = if (card.status == RainCardStatus.NEED_LOCATION) onAllowLocation else onClick)
            .semantics { contentDescription = "$title. $sub. " + tr("Tap for details.") }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = Hookline.type.title, color = c.ink, maxLines = 2)
            if (sub.isNotEmpty()) Text(sub, style = Hookline.type.caption, color = c.inkSecondary, maxLines = 1)
        }
        if (f != null && f.confidence != Confidence.NONE) {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                RainBars(f, Modifier.width(96.dp).height(28.dp))
                ConfidenceMeter(f.confidence)
            }
        }
    }
}

/** The next hour as 13 bars, one per 5 minutes: height and colour by intensity; dots = dry; stubs = unknown. */
@Composable
fun RainBars(f: RainForecast, modifier: Modifier = Modifier) {
    val c = Hookline.colors
    val (thr, heavy) = remember(f.kind) { RainSource.thresholdsFor(f.kind) }
    Canvas(modifier) {
        val n = f.series.size
        val gap = size.width * 0.012f + 1f
        val bw = (size.width - gap * (n - 1)) / n
        val base = size.height - 2f
        for (i in 0 until n) {
            val x = i * (bw + gap)
            val v = f.series[i]
            when {
                v.isNaN() -> drawRoundRect(c.hairline, Offset(x, base - size.height * 0.12f), Size(bw, size.height * 0.12f), CornerRadius(2f))
                v < thr -> drawCircle(c.hairline, radius = (bw * 0.22f).coerceAtLeast(1.5f), center = Offset(x + bw / 2, base - 2f))
                else -> {
                    val k = RainIntensity.level(f.kind, v)
                    val h = size.height * (0.18f + 0.82f * k)
                    drawRoundRect(if (v >= heavy) RainHeavy else lerp(RainLight, RainStrong, k), Offset(x, base - h), Size(bw, h), CornerRadius(bw * 0.25f))
                }
            }
        }
    }
}

/** Three segments: high fills all, medium two, low one. */
@Composable
fun ConfidenceMeter(conf: Confidence, modifier: Modifier = Modifier) {
    val c = Hookline.colors
    val filled = when (conf) { Confidence.HIGH -> 3; Confidence.MEDIUM -> 2; Confidence.LOW -> 1; Confidence.NONE -> 0 }
    Row(modifier.semantics { contentDescription = ForecastText.confidence(conf) }, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(3) { i ->
            Box(Modifier.size(width = 14.dp, height = 4.dp).background(if (i < filled) c.ink else c.hairline, RoundedCornerShape(2.dp)))
        }
    }
}

/** Everything behind the headline: the hour, how sure, why, and when the data is from. */
@Composable
fun RainSheet(card: RainCard, now: Long, onAlerts: () -> Unit, onDismiss: () -> Unit) {
    val c = Hookline.colors
    HooklineSheet(onDismiss) {
        val f = card.forecast
        if (f == null) {
            Text(
                when (card.status) {
                    RainCardStatus.NO_COVERAGE -> tr("No rain radar here")
                    RainCardStatus.LOADING -> tr("Checking the radar for rain…")
                    else -> tr("Rain forecast unavailable")
                },
                style = Hookline.type.title, color = c.ink,
            )
            Text(
                when (card.status) {
                    RainCardStatus.NO_COVERAGE -> tr("Hookline forecasts rain from radar, and open radar data covers the US and most of Europe. There's no radar coverage here, so there is no forecast.")
                    else -> tr("The radar data couldn't be loaded. Check your connection; Hookline tries again every two minutes.")
                },
                style = Hookline.type.body, color = c.ink,
            )
            return@HooklineSheet
        }
        Text(ForecastText.headline(f, now), style = Hookline.type.title, color = c.ink)
        Text(listOfNotNull(card.place, ForecastText.detail(f, now)).joinToString(" · "), style = Hookline.type.body, color = c.inkSecondary)

        if (f.confidence != Confidence.NONE) {
            RainBars(f, Modifier.fillMaxWidth().height(72.dp).padding(top = 6.dp))
            Row(Modifier.fillMaxWidth()) {
                Text(tr("%s (radar)", MapViewModel.clock(f.observedMillis)), style = Hookline.type.caption, color = c.inkSecondary, modifier = Modifier.weight(1f))
                Text(tr("+30 min"), style = Hookline.type.caption, color = c.inkSecondary, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                Text(tr("+60 min"), style = Hookline.type.caption, color = c.inkSecondary, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
            }
            IntensityKey()
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
            ConfidenceMeter(f.confidence)
            Text(ForecastText.confidence(f.confidence), style = Hookline.type.control, color = c.ink)
        }
        if (f.issues.isEmpty()) {
            Text(
                if (f.rainNearby) tr("The radar picture is recent and the rain is moving steadily.")
                else tr("The radar picture is recent and there's no rain nearby."),
                style = Hookline.type.body, color = c.ink,
            )
        } else {
            f.issues.forEach { i ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("•", style = Hookline.type.body, color = c.inkSecondary)
                    Text(ForecastText.issue(i, f, now), style = Hookline.type.body, color = c.ink)
                }
            }
        }

        val age = f.ageMinutes(now)
        Text(
            tr("Radar picture observed %s, checked %s.", MapViewModel.clock(f.observedMillis), MapViewModel.clock(card.checkedMillis)) +
                if (age >= app.hookline.radar.core.data.RainForecaster.OLD_DATA_MIN) " " + tr("That's %s min ago: the forecast may be out of date.", age) else "",
            style = Hookline.type.caption, color = if (age >= app.hookline.radar.core.data.RainForecaster.OLD_DATA_MIN) c.ink else c.inkSecondary,
        )
        Text(
            tr("How it works: Hookline compares the last two radar pictures, works out how the rain is moving and moves it forward. Times are a range because rain speeds up, slows and changes shape. It can't see new showers before they form, so in showery weather rain can arrive with no forecast."),
            style = Hookline.type.caption, color = c.inkSecondary,
        )
        Text(
            tr("Rain alert settings"),
            style = Hookline.type.control, color = c.ink,
            modifier = Modifier.clickable(onClick = onAlerts).padding(vertical = 10.dp),
        )
    }
}

@Composable
private fun IntensityKey() {
    val c = Hookline.colors
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(RainLight to tr("Light"), lerp(RainLight, RainStrong, 0.6f) to tr("Moderate"), RainStrong to tr("Strong"), RainHeavy to tr("Heavy")).forEach { (col, label) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(9.dp).background(col, RoundedCornerShape(2.dp)))
                Text(label, style = Hookline.type.caption, color = c.inkSecondary)
            }
        }
    }
}

// ---- simple mode ------------------------------------------------------------------------------

/** Reflectivity in words: the four colours a beginner needs, from the same palette as the map. */
@Composable
fun SimpleLegend(onHelp: () -> Unit) {
    val c = Hookline.colors
    val lut = remember { Product.REF.palette.toLut(Product.REF.min, Product.REF.max, 91) }
    fun at(dbz: Float) = Color(lut[((dbz - Product.REF.min) / (Product.REF.max - Product.REF.min) * 90).toInt().coerceIn(0, 90)])
    val classes = listOf(
        at(24f) to tr("Light"), at(35f) to tr("Moderate"), at(45f) to tr("Heavy"), at(57f) to tr("Very heavy, hail"),
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            classes.forEach { (col, label) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(width = 14.dp, height = 8.dp).background(col, RoundedCornerShape(2.dp)))
                    Text(label, style = Hookline.type.caption, color = c.ink, maxLines = 1)
                }
            }
        }
        Box(
            Modifier.size(28.dp).border(1.dp, c.hairline, CircleShape).clickable(onClick = onHelp)
                .semantics { contentDescription = tr("What am I looking at?") },
            contentAlignment = Alignment.Center,
        ) { Text("?", style = Hookline.type.control, color = c.ink) }
    }
}

/** Short explanations for simple mode. */
@Composable
fun SimpleHelpSheet(onAdvanced: () -> Unit, onDismiss: () -> Unit) {
    val c = Hookline.colors
    HooklineSheet(onDismiss) {
        Text(tr("Reading the radar"), style = Hookline.type.title, color = c.ink)
        listOf(
            tr("Colours") to tr("Rain seen by weather radar. Blue and green are light rain, yellow and orange heavier, red heavy, and pink or purple very heavy rain, often with hail."),
            tr("Moving") to tr("Press play to see the last hour. The direction the colours move is where the rain is heading."),
            tr("Your forecast") to tr("The card at the top says if rain is coming to you and roughly when, with how sure it is."),
            tr("How fresh") to tr("The time at the bottom is when the radar took the picture. New pictures come every few minutes; if it says delayed, the radar feed is behind."),
            tr("Not shown") to tr("Radar sees rain and snow, not clouds, wind or temperature. Very light drizzle and snow far from a radar can be missed."),
        ).forEach { (h, t) ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(h, style = Hookline.type.control, color = c.ink)
                Text(t, style = Hookline.type.body, color = c.inkSecondary)
            }
        }
        Text(
            tr("Switch to advanced radar"),
            style = Hookline.type.control, color = c.ink,
            modifier = Modifier.clickable(onClick = onAdvanced).padding(vertical = 10.dp),
        )
    }
}

/** Asked once: simple rain view, or the full radar toolkit. */
@Composable
fun StyleChoiceCard(onSimple: () -> Unit, onAdvanced: () -> Unit, modifier: Modifier = Modifier) {
    val c = Hookline.colors
    Column(
        modifier
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .fillMaxWidth()
            .background(c.raised, RoundedCornerShape(Dimens.PanelRadius))
            .border(1.dp, c.ink, RoundedCornerShape(Dimens.PanelRadius))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(tr("How do you want to see the radar?"), style = Hookline.type.title, color = c.ink)
        StyleOption(tr("Simple"), tr("Rain in plain words: light, moderate, heavy. Just the essentials."), onSimple)
        StyleOption(tr("Advanced"), tr("Every radar product, tilts, velocity, storm tools."), onAdvanced)
        Text(tr("You can switch any time in the menu."), style = Hookline.type.caption, color = c.inkSecondary)
    }
}

@Composable
private fun StyleOption(title: String, text: String, onClick: () -> Unit) {
    val c = Hookline.colors
    Column(
        Modifier.fillMaxWidth().border(1.dp, c.hairline, RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, style = Hookline.type.control, color = c.ink)
        Text(text, style = Hookline.type.caption, color = c.inkSecondary)
    }
}
