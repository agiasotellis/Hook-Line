package app.hookline.radar.map

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.hookline.radar.R
import app.hookline.radar.core.designsystem.Hookline
import app.hookline.radar.i18n.tr

/** Shown once, before Android's location prompt, so people know what it's for. */
@Composable
fun LocationRationale(onAllow: () -> Unit, onLater: () -> Unit) {
    val c = Hookline.colors
    Box(Modifier.fillMaxSize().background(c.mapVoid.copy(alpha = 0.75f)), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.navigationBarsPadding().padding(12.dp)
                .background(c.raised, RoundedCornerShape(24.dp)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Image(painterResource(R.drawable.ic_hookline_mark), contentDescription = null, modifier = Modifier.size(40.dp))
            Text(tr("Use your location?"), style = Hookline.type.title, color = c.ink)
            Text(
                tr("Hookline uses your location to open the map where you are, pick the nearest radar and show the warnings for your area."),
                style = Hookline.type.body, color = c.ink,
            )
            Text(
                tr("Storm Mode keeps the last location you opened the app at, so it can check warnings and rain for it every 15 minutes, even when the app is closed. It never tracks you in the background. You can change this in Storm Mode at any time."),
                style = Hookline.type.caption, color = c.inkSecondary,
            )
            PrimaryButton(tr("Continue"), onClick = onAllow)
            Text(
                tr("Not now"), style = Hookline.type.control, color = c.ink,
                modifier = Modifier.align(Alignment.CenterHorizontally).clickable(role = Role.Button, onClick = onLater).padding(10.dp),
            )
        }
    }
}

/** Full-width filled button for the one main action on a screen. */
@Composable
fun PrimaryButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Hookline.colors
    androidx.compose.foundation.layout.Box(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .background(if (enabled) c.ink else c.hairline, androidx.compose.foundation.shape.RoundedCornerShape(28.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = Hookline.type.control, color = if (enabled) c.inkOnSelected else c.ink)
    }
}
