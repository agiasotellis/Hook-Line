package app.hookline.radar.map

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.hookline.radar.R
import app.hookline.radar.core.designsystem.Hookline

/**
 * Covers the map while it and the first radar image load, so the app never
 * opens on a blank screen. The mark sits exactly where the system launch
 * screen put it, so the hand-over is seamless; then a thin scanning line and
 * what's loading appear underneath.
 */
@Composable
fun LoadingScreen(visible: Boolean, status: String) {
    val c = Hookline.colors
    AnimatedVisibility(visible = visible, exit = fadeOut(tween(350))) {
        Box(
            Modifier.fillMaxSize().background(c.mapVoid)
                // Swallow touches so the map underneath can't be moved blind.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .semantics { contentDescription = "Hookline is loading: $status" },
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                // Same size as the system launch screen's icon (288 dp box for the 108 dp foreground).
                modifier = Modifier.size(288.dp).align(Alignment.Center),
            )
            Column(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 72.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ScanLine(Modifier.width(120.dp).height(2.dp))
                Text(
                    status,
                    style = Hookline.type.caption,
                    color = c.inkSecondary,
                    modifier = Modifier.padding(top = 14.dp),
                )
            }
        }
    }
}

/** A short bright segment sweeping along a hairline: indeterminate progress without a spinner. */
@Composable
private fun ScanLine(modifier: Modifier) {
    val c = Hookline.colors
    val t by rememberInfiniteTransition(label = "scan").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "scan",
    )
    Canvas(modifier) {
        val y = size.height / 2
        drawLine(c.hairline, Offset(0f, y), Offset(size.width, y), strokeWidth = size.height)
        val seg = size.width * 0.3f
        val start = -seg + (size.width + seg) * t
        drawLine(
            c.ink,
            Offset(start.coerceAtLeast(0f), y),
            Offset((start + seg).coerceAtMost(size.width), y),
            strokeWidth = size.height,
        )
    }
}
