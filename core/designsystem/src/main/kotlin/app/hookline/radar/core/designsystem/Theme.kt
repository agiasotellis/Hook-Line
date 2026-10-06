package app.hookline.radar.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Hookline design tokens. See docs/03-DESIGN-SYSTEM.md.
 *
 * The one rule: the map is the only colour. Chrome is neutral; state is shown
 * with fill, weight and shape. Hazard colours appear only on alerts and always
 * with a label and a hatch pattern.
 */
@Immutable
data class HooklineColors(
    val mapVoid: Color,
    val chrome: Color,
    val raised: Color,
    val ink: Color,
    val inkSecondary: Color,
    val inkOnSelected: Color,
    val hairline: Color,
)

val NightColors = HooklineColors(
    mapVoid = Color(0xFF141B22),
    chrome = Color(0xEB1C252E), // 92 %
    raised = Color(0xFF26313C),
    ink = Color(0xFFECE8DF),
    inkSecondary = Color(0xFF9AA5AE),
    inkOnSelected = Color(0xFF141B22),
    hairline = Color(0xFF3A4652),
)

val DayColors = HooklineColors(
    mapVoid = Color(0xFFE6E3DC),
    chrome = Color(0xF2F3F1EC),
    raised = Color(0xFFFFFFFF),
    ink = Color(0xFF1A2128),
    inkSecondary = Color(0xFF4B555E),
    inkOnSelected = Color(0xFFF3F1EC),
    hairline = Color(0xFFBFC4C8),
)

val RedNightColors = HooklineColors(
    mapVoid = Color(0xFF0A0505),
    chrome = Color(0xEB140808),
    raised = Color(0xFF1E0B0B),
    ink = Color(0xFFD9473F),
    inkSecondary = Color(0xFF8C2C27),
    inkOnSelected = Color(0xFF0A0505),
    hairline = Color(0xFF3A1414),
)

object HazardColors {
    val TornadoWarning = Color(0xFFE0245E)
    val TornadoEmergency = Color(0xFFB000B0)
    val SevereWarning = Color(0xFFF5A623)
    val FlashFlood = Color(0xFF2E9E5B)
    val TornadoWatch = Color(0xFFFFD84D)
    val SevereWatch = Color(0xFFE8A6C8)
}

object Dimens {
    val MinTouch = 56.dp
    val EdgeInset = 12.dp
    val PanelRadius = 14.dp
    val SheetRadius = 24.dp
    val AlertRadius = 6.dp
}

/**
 * Interim families until Barlow / Barlow Semi Condensed (SIL OFL) are bundled in
 * res/font. sans-serif-condensed keeps the narrow, signage-like width.
 */
object HooklineFonts {
    val Condensed: FontFamily = FontFamily.SansSerif // TODO swap to bundled Barlow Semi Condensed
    val Body: FontFamily = FontFamily.SansSerif // TODO swap to bundled Barlow
}

@Immutable
data class HooklineType(
    val readoutXL: TextStyle = TextStyle(fontFamily = HooklineFonts.Condensed, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 32.sp, fontFeatureSettings = "tnum"),
    val title: TextStyle = TextStyle(fontFamily = HooklineFonts.Condensed, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    val control: TextStyle = TextStyle(fontFamily = HooklineFonts.Condensed, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp),
    val body: TextStyle = TextStyle(fontFamily = HooklineFonts.Body, fontSize = 15.sp, lineHeight = 22.sp),
    val caption: TextStyle = TextStyle(fontFamily = HooklineFonts.Body, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
)

enum class HooklineThemeMode { Night, Day, RedNight }

val LocalHooklineColors = staticCompositionLocalOf { NightColors }
val LocalHooklineType = staticCompositionLocalOf { HooklineType() }

object Hookline {
    val colors: HooklineColors @Composable get() = LocalHooklineColors.current
    val type: HooklineType @Composable get() = LocalHooklineType.current
}

@Composable
fun HooklineTheme(mode: HooklineThemeMode = HooklineThemeMode.Night, content: @Composable () -> Unit) {
    val c = when (mode) {
        HooklineThemeMode.Night -> NightColors
        HooklineThemeMode.Day -> DayColors
        HooklineThemeMode.RedNight -> RedNightColors
    }
    val scheme = if (mode == HooklineThemeMode.Day) {
        lightColorScheme(primary = c.ink, onPrimary = c.inkOnSelected, background = c.mapVoid, surface = c.raised, onSurface = c.ink, outline = c.hairline)
    } else {
        darkColorScheme(primary = c.ink, onPrimary = c.inkOnSelected, background = c.mapVoid, surface = c.raised, onSurface = c.ink, outline = c.hairline)
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalHooklineColors provides c, LocalHooklineType provides HooklineType()) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
