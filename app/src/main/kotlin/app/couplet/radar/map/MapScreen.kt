package app.couplet.radar.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.couplet.radar.core.designsystem.ChromePanel
import app.couplet.radar.core.designsystem.Couplet
import app.couplet.radar.core.designsystem.Dimens
import app.couplet.radar.core.designsystem.HazardColors
import app.couplet.radar.core.designsystem.HazardSwatch
import app.couplet.radar.core.designsystem.PillRow
import app.couplet.radar.core.designsystem.Readout
import app.couplet.radar.core.designsystem.SelectPill

@Composable
fun MapScreen(vm: MapViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val c = Couplet.colors

    BoxWithConstraints(Modifier.fillMaxSize().background(c.mapVoid)) {
        val density = LocalDensity.current
        val wPx = with(density) { maxWidth.roundToPx() }
        val hPx = with(density) { maxHeight.roundToPx() }
        LaunchedEffect(wPx, hPx) { vm.onViewport(wPx, hPx) }

        s.image?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "${s.product.label} scan from ${s.site}",
                contentScale = ContentScale.FillBounds,
                // Nearest-neighbour keeps radar bins crisp; never smooth data.
                filterQuality = FilterQuality.None,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Column(
            Modifier.fillMaxWidth().statusBarsPadding().padding(Dimens.EdgeInset),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SiteChip(s.site, s.siteName)
                s.warningHeadline?.let { WarningBanner(it, s.warningDetail.orEmpty(), Modifier.weight(1f)) }
            }
            if (s.stormModeOn) StatusChip("Storm Mode on")
        }

        TiltLadder(
            tilts = s.tilts, selected = s.tiltIndex, onSelect = vm::selectTilt,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = Dimens.EdgeInset),
        )

        BottomDock(
            state = s,
            onProduct = vm::selectProduct,
            onPlay = vm::togglePlay,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun SiteChip(id: String, name: String) {
    ChromePanel(Modifier.height(Dimens.MinTouch).clickable { /* open radar picker */ }) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalArrangement = Arrangement.Center) {
            Text(id, style = Couplet.type.title, color = Couplet.colors.ink)
            Text(name, style = Couplet.type.caption, color = Couplet.colors.inkSecondary)
        }
    }
}

@Composable
private fun WarningBanner(headline: String, detail: String, modifier: Modifier = Modifier) {
    val c = Couplet.colors
    Row(
        modifier
            .height(Dimens.MinTouch)
            .background(c.chrome, RoundedCornerShape(Dimens.AlertRadius))
            .border(1.dp, HazardColors.TornadoWarning, RoundedCornerShape(Dimens.AlertRadius))
            .clickable { /* open alert detail */ }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HazardSwatch(HazardColors.TornadoWarning)
        Column {
            Text(headline, style = Couplet.type.control, color = c.ink)
            Text(detail, style = Couplet.type.caption, color = c.inkSecondary)
        }
    }
}

@Composable
private fun StatusChip(text: String) {
    val c = Couplet.colors
    Row(
        Modifier.background(c.chrome, CircleShape).border(1.dp, c.hairline, CircleShape).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(7.dp).background(c.ink, CircleShape))
        Text(text, style = Couplet.type.caption, color = c.inkSecondary)
    }
}

@Composable
private fun TiltLadder(tilts: List<Float>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    ChromePanel(modifier) {
        Column(Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            tilts.forEachIndexed { i, t ->
                SelectPill("$t°", selected = i == selected, onClick = { onSelect(i) }, modifier = Modifier.width(52.dp))
            }
        }
    }
}

@Composable
private fun BottomDock(state: MapUiState, onProduct: (Product) -> Unit, onPlay: () -> Unit, modifier: Modifier = Modifier) {
    val c = Couplet.colors
    Column(
        modifier
            .fillMaxWidth()
            .background(c.mapVoid.copy(alpha = 0.94f))
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Legend(state.product)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.size(48.dp).border(1.dp, c.hairline, CircleShape).clickable(onClick = onPlay)
                    .semantics { contentDescription = if (state.playing) "Pause loop" else "Play loop" },
                contentAlignment = Alignment.Center,
            ) { Text(if (state.playing) "II" else "▶", style = Couplet.type.control, color = c.ink) }
            TimeTape(Modifier.weight(1f).height(48.dp))
            Readout(
                value = "%d:%02d".format(state.scanAgeSeconds / 60, state.scanAgeSeconds % 60),
                caption = "scan age",
                alignEnd = true,
            )
        }
        PillRow {
            Product.entries.forEach { p ->
                SelectPill(p.label, selected = p == state.product, onClick = { onProduct(p) }, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** Instrument tape: ticks per sweep, tall = low tilt, fixed centre needle. */
@Composable
private fun TimeTape(modifier: Modifier = Modifier) {
    val c = Couplet.colors
    Canvas(modifier.semantics { contentDescription = "Loop timeline, drag to scrub" }) {
        val pattern = floatArrayOf(1f, 0.73f, 0.53f, 1f, 0.4f, 0.3f, 0.23f)
        val step = 6.dp.toPx()
        val base = size.height - 14.dp.toPx()
        val maxH = 30.dp.toPx()
        var x = 0f; var i = 0
        while (x < size.width) {
            val past = x < size.width / 2
            drawLine(
                if (past) c.inkSecondary else c.hairline,
                start = androidx.compose.ui.geometry.Offset(x, base),
                end = androidx.compose.ui.geometry.Offset(x, base - maxH * pattern[i % pattern.size]),
                strokeWidth = 2.dp.toPx(),
            )
            x += step; i++
        }
        drawLine(
            c.ink,
            start = androidx.compose.ui.geometry.Offset(size.width / 2, 0f),
            end = androidx.compose.ui.geometry.Offset(size.width / 2, base),
            strokeWidth = 2.dp.toPx(),
        )
    }
}

@Composable
private fun Legend(product: Product) {
    val c = Couplet.colors
    val (label, colors) = when (product) {
        Product.VEL, Product.SRV -> "m/s" to listOf(Color(0xFFAADCFF), Color(0xFF2878FF), Color(0xFF465060), Color(0xFFA0460A), Color(0xFFFF8C14))
        else -> "dBZ" to listOf(
            Color(0xFF04E9E7), Color(0xFF019FF4), Color(0xFF0300F4), Color(0xFF02FD02), Color(0xFF01C501), Color(0xFF008E00),
            Color(0xFFFDF802), Color(0xFFE5BC00), Color(0xFFFD9500), Color(0xFFFD0000), Color(0xFFD40000), Color(0xFFBC0000),
            Color(0xFFF800FD), Color(0xFF9854C6),
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = Couplet.type.caption, color = c.inkSecondary, modifier = Modifier.width(34.dp))
        Box(Modifier.weight(1f).height(6.dp).background(Brush.horizontalGradient(colors), RoundedCornerShape(3.dp)))
        Spacer(Modifier.width(4.dp))
    }
}
