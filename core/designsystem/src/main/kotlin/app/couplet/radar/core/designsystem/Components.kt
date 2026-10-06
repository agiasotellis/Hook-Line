package app.couplet.radar.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Selection pill: filled ink when selected, outlined otherwise. Never coloured. */
@Composable
fun SelectPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Couplet.colors
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .semantics { this.selected = selected }
            .background(if (selected) c.ink else c.chrome.copy(alpha = 0f), CircleShape)
            .border(BorderStroke(1.dp, if (selected) c.ink else c.hairline), CircleShape)
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = Couplet.type.control, color = if (selected) c.inkOnSelected else c.ink)
    }
}

/** Floating chrome panel over the map. No shadow: shadows smear radar data. */
@Composable
fun ChromePanel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = Couplet.colors
    Box(
        modifier
            .background(c.chrome, RoundedCornerShape(Dimens.PanelRadius))
            .border(1.dp, c.hairline, RoundedCornerShape(Dimens.PanelRadius)),
    ) { content() }
}

/** Large tabular readout with a caption, e.g. scan age "0:48". */
@Composable
fun Readout(value: String, caption: String, modifier: Modifier = Modifier, alignEnd: Boolean = false) {
    Column(modifier, horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
        Text(value, style = Couplet.type.readoutXL, color = Couplet.colors.ink)
        Text(caption, style = Couplet.type.caption, color = Couplet.colors.inkSecondary)
    }
}

@Composable
fun PillRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.height(48.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), content = content)
}

/** Hatched hazard swatch: colour is always paired with pattern and a label. */
@Composable
fun HazardSwatch(color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier.width(34.dp).height(34.dp).clip(RoundedCornerShape(4.dp))) {
        drawRoundRect(color, cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
        val step = 10.dp.toPx()
        var x = -size.height
        while (x < size.width) {
            drawLine(
                color.copy(red = color.red * 0.8f, green = color.green * 0.8f, blue = color.blue * 0.8f),
                start = androidx.compose.ui.geometry.Offset(x, size.height),
                end = androidx.compose.ui.geometry.Offset(x + size.height, 0f),
                strokeWidth = 5.dp.toPx(),
            )
            x += step
        }
    }
}
