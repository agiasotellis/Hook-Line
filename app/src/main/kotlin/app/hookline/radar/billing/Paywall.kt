package app.hookline.radar.billing

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import app.hookline.radar.R
import app.hookline.radar.core.designsystem.Hookline
import app.hookline.radar.i18n.tr

fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

/**
 * Shown over the map once the free trial has ended. The map stays visible
 * behind it, so people see exactly what they're unlocking.
 */
@Composable
fun PaywallScreen(access: Access, onBuy: (Activity) -> Unit, onRestore: () -> Unit) {
    val c = Hookline.colors
    val context = LocalContext.current
    Box(
        Modifier.fillMaxSize().background(c.mapVoid.copy(alpha = 0.92f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                .verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Image(painterResource(R.drawable.ic_hookline_mark), contentDescription = null, modifier = Modifier.size(56.dp))
            Text(tr("Your free week is over"), style = Hookline.type.readoutXL, color = c.ink)
            Text(
                tr("Unlock Hookline once and keep everything, for good. No subscription, no ads, no account."),
                style = Hookline.type.body, color = c.ink,
            )
            Spacer(Modifier.height(4.dp))
            listOf(
                tr("Every NEXRAD and open European radar, every tilt and product"),
                tr("Warnings, Storm Mode and rain alerts for your places"),
                tr("Storm tracking, cross-sections, side by side and the archive"),
                tr("Lightning, satellite, the widget and offline maps"),
                tr("Every future update included"),
            ).forEach { line ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.padding(top = 8.dp).size(6.dp).background(c.ink, CircleShape))
                    Text(line, style = Hookline.type.body, color = c.ink)
                }
            }
            Spacer(Modifier.height(10.dp))
            BuyButton(
                label = if (access.busy) tr("Opening Google Play…") else access.price?.let { tr("Unlock Hookline · %s", it) } ?: tr("Unlock Hookline"),
                enabled = !access.busy,
            ) { context.findActivity()?.let(onBuy) }
            Text(
                tr("One payment through Google Play. Restore it free on any phone with the same Google account."),
                style = Hookline.type.caption, color = c.inkSecondary,
            )
            Text(
                tr("Restore purchase"),
                style = Hookline.type.control, color = c.ink,
                modifier = Modifier.clickable(role = Role.Button, onClick = onRestore).padding(vertical = 10.dp),
            )
            access.message?.let { Text(it, style = Hookline.type.body, color = c.ink) }
        }
    }
}

@Composable
fun BuyButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Hookline.colors
    Box(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .background(if (enabled) c.ink else c.hairline, RoundedCornerShape(28.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = Hookline.type.control, color = if (enabled) c.inkOnSelected else c.ink)
    }
}

/** Menu row during the trial: days left and a way to buy now. */
@Composable
fun TrialCard(access: Access, onBuy: (Activity) -> Unit, onRestore: () -> Unit) {
    if (access.unlocked) return
    val c = Hookline.colors
    val context = LocalContext.current
    Column(
        Modifier.fillMaxWidth().border(1.dp, c.hairline, RoundedCornerShape(14.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            when (access.trialDaysLeft) {
                0 -> tr("Free trial ended")
                1 -> tr("Free trial: 1 day left")
                else -> tr("Free trial: %s days left", access.trialDaysLeft.toString())
            },
            style = Hookline.type.control, color = c.ink,
        )
        Text(tr("Buy once, keep it forever. No subscription."), style = Hookline.type.caption, color = c.inkSecondary)
        BuyButton(access.price?.let { tr("Unlock Hookline · %s", it) } ?: tr("Unlock Hookline"), enabled = !access.busy) {
            context.findActivity()?.let(onBuy)
        }
        Text(
            tr("Restore purchase"), style = Hookline.type.caption, color = c.ink,
            modifier = Modifier.clickable(role = Role.Button, onClick = onRestore).padding(vertical = 6.dp),
        )
        access.message?.let { Text(it, style = Hookline.type.caption, color = c.ink) }
    }
}
