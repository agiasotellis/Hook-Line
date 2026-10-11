package app.hookline.radar.map

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.hookline.radar.HooklineApp
import app.hookline.radar.core.data.RainIntensity
import app.hookline.radar.core.designsystem.Hookline
import app.hookline.radar.core.designsystem.PillRow
import app.hookline.radar.core.designsystem.SelectPill
import app.hookline.radar.i18n.tr
import app.hookline.radar.stormmode.AlertChecker
import app.hookline.radar.stormmode.AlertPrefs

/** Rain alert strength and quiet hours. */
@Composable
fun AlertSettingsSection() {
    val c = Hookline.colors
    val context = LocalContext.current
    val prefs = remember { AlertPrefs(context) }
    var rainMin by remember { mutableStateOf(prefs.rainMin) }
    var quietOn by remember { mutableStateOf(prefs.quietOn) }
    var from by remember { mutableIntStateOf(prefs.quietFrom) }
    var to by remember { mutableIntStateOf(prefs.quietTo) }
    var severe by remember { mutableStateOf(prefs.quietLetSevere) }

    Spacer(Modifier.height(4.dp))
    Text(tr("Rain alerts"), style = Hookline.type.control, color = c.ink)
    Text(tr("Notify me when the next hour brings"), style = Hookline.type.caption, color = c.inkSecondary)
    PillRow {
        listOf(RainIntensity.LIGHT to tr("Any rain"), RainIntensity.MODERATE to tr("Moderate+"), RainIntensity.HEAVY to tr("Heavy only")).forEach { (i, label) ->
            SelectPill(label, selected = rainMin == i, onClick = { rainMin = i; prefs.rainMin = i }, modifier = Modifier.weight(1f))
        }
    }
    Text(
        tr("Rain alerts only go out when the forecast is at least medium confidence, at most once every 90 minutes per place."),
        style = Hookline.type.caption, color = c.inkSecondary,
    )

    Spacer(Modifier.height(4.dp))
    ToggleRow(
        tr("Quiet hours"),
        if (quietOn) tr("%s to %s", hour(from), hour(to)) else tr("Off"),
        quietOn,
    ) { quietOn = it; prefs.quietOn = it }
    if (quietOn) {
        HourStepper(tr("From"), from) { from = it; prefs.quietFrom = it }
        HourStepper(tr("Until"), to) { to = it; prefs.quietTo = it }
        ToggleRow(
            tr("Let severe warnings through"),
            tr("Red warnings, tornado and flash flood warnings, and emergencies still notify"),
            severe,
        ) { severe = it; prefs.quietLetSevere = it }
        Text(
            tr("Other warnings held back in quiet hours notify when they end, if still in force. Rain alerts are skipped."),
            style = Hookline.type.caption, color = c.inkSecondary,
        )
    }
}

private fun hour(h: Int) = "%02d:00".format(h)

@Composable
private fun HourStepper(label: String, value: Int, onChange: (Int) -> Unit) {
    val c = Hookline.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Hookline.type.body, color = c.ink, modifier = Modifier.weight(1f))
        StepButton("−", tr("Earlier")) { onChange((value + 23) % 24) }
        Text(hour(value), style = Hookline.type.control, color = c.ink, modifier = Modifier.padding(horizontal = 14.dp))
        StepButton("+", tr("Later")) { onChange((value + 1) % 24) }
    }
}

@Composable
private fun StepButton(text: String, description: String, onClick: () -> Unit) {
    val c = Hookline.colors
    androidx.compose.foundation.layout.Box(
        Modifier.size(44.dp).border(1.dp, c.hairline, CircleShape).clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Text(text, style = Hookline.type.title, color = c.ink) }
}

/**
 * Checks what can stop alerts arriving: notifications blocked, the alert
 * channels turned off, battery optimisation, background restriction, and
 * whether the background check has actually been running.
 */
@Composable
fun DeliveryCheck() {
    val c = Hookline.colors
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    // Re-check when the user comes back from a settings screen.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val problems = remember(tick) { deliveryProblems(context) }
    var testResult by remember { mutableStateOf<String?>(null) }

    Spacer(Modifier.height(4.dp))
    Text(tr("Alert delivery"), style = Hookline.type.control, color = c.ink)
    if (problems.isEmpty()) {
        Text(tr("Nothing on this phone is blocking alerts."), style = Hookline.type.body, color = c.inkSecondary)
    } else {
        problems.forEach { p ->
            Column(
                Modifier.fillMaxWidth().border(1.dp, c.hairline, androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(p.title, style = Hookline.type.control, color = c.ink)
                Text(p.text, style = Hookline.type.caption, color = c.inkSecondary)
                p.fix?.let { (label, intent) ->
                    Text(
                        label, style = Hookline.type.control, color = c.ink,
                        modifier = Modifier.clickable { runCatching { context.startActivity(intent) } }.padding(vertical = 6.dp),
                    )
                }
            }
        }
    }
    val last = AlertPrefs(context).lastCheckMillis
    Text(
        if (last == 0L) tr("The background check hasn't run yet.")
        else tr("Last background check: %s.", MapViewModel.clock(last)),
        style = Hookline.type.caption, color = c.inkSecondary,
    )
    if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
        Text(
            tr("On Samsung phones, also check that Hookline isn't under Settings › Battery › Background usage limits › Sleeping apps."),
            style = Hookline.type.caption, color = c.inkSecondary,
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            tr("Send a test alert"), style = Hookline.type.control, color = c.ink,
            modifier = Modifier.clickable {
                testResult = if (AlertChecker.sendTest(context)) tr("Sent. If nothing appeared, notifications are blocked for Hookline.")
                else tr("Notifications are blocked. Allow them first.")
                tick++
            }.padding(vertical = 10.dp),
        )
    }
    testResult?.let { Text(it, style = Hookline.type.caption, color = c.inkSecondary) }
}

private data class DeliveryProblem(val title: String, val text: String, val fix: Pair<String, Intent>?)

private fun appSettings(context: Context) =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

private fun deliveryProblems(context: Context): List<DeliveryProblem> {
    val out = mutableListOf<DeliveryProblem>()
    val nm = NotificationManagerCompat.from(context)
    val notifSettings = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val permissionMissing = Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    if (permissionMissing || !nm.areNotificationsEnabled()) {
        out += DeliveryProblem(
            tr("Notifications are off"),
            tr("Hookline can't show warnings or rain alerts."),
            tr("Turn on notifications") to notifSettings,
        )
    } else {
        val sys = context.getSystemService(NotificationManager::class.java)
        listOf(HooklineApp.CHANNEL_WARNINGS to tr("Warnings"), HooklineApp.CHANNEL_RAIN to tr("Rain alerts")).forEach { (id, name) ->
            val ch = sys.getNotificationChannel(id)
            if (ch != null && ch.importance == NotificationManager.IMPORTANCE_NONE) {
                out += DeliveryProblem(
                    tr("%s are turned off", name),
                    tr("This kind of notification is switched off in Android's settings."),
                    tr("Open notification settings") to Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .putExtra(Settings.EXTRA_CHANNEL_ID, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }
    if (Build.VERSION.SDK_INT >= 28 && context.getSystemService(ActivityManager::class.java).isBackgroundRestricted) {
        out += DeliveryProblem(
            tr("Background activity is restricted"),
            tr("Android stops Hookline's checks while the app is closed, so alerts won't arrive."),
            tr("Open app settings") to appSettings(context),
        )
    }
    val pm = context.getSystemService(PowerManager::class.java)
    if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
        out += DeliveryProblem(
            tr("Battery optimisation may delay checks"),
            tr("Android can postpone the 15-minute check when the phone is idle. For dependable alerts, set Hookline's battery use to Unrestricted."),
            tr("Open battery settings") to Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
    val last = AlertPrefs(context).lastCheckMillis
    if (AlertChecker.isEnabled(context) && last > 0 && System.currentTimeMillis() - last > 60 * 60_000L) {
        out += DeliveryProblem(
            tr("Checks are running late"),
            tr("The last background check was more than an hour ago. Android is holding Hookline back; the settings above usually fix it."),
            null,
        )
    }
    return out
}
