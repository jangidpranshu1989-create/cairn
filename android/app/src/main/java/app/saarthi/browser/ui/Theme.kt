package app.saarthi.browser.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.saarthi.browser.data.Prefs

/** Neutral graphite palette shared with the desktop app. The only hue is the muted "agent" blue. */
@Immutable
data class Palette(
    val dark: Boolean,
    val chrome: Color, val bg: Color, val surface: Color, val surface2: Color, val surface3: Color,
    val line: Color, val line2: Color, val text: Color, val muted: Color, val faint: Color,
    val accent: Color, val accentInk: Color, val agent: Color, val agentSoft: Color,
    val ok: Color, val err: Color, val warn: Color, val scrim: Color,
)

val DarkPalette = Palette(
    dark = true,
    chrome = Color(0xFF0F0F0F), bg = Color(0xFF151515), surface = Color(0xFF1C1C1C), surface2 = Color(0xFF242424), surface3 = Color(0xFF2E2E2E),
    line = Color(0x14FFFFFF), line2 = Color(0x24FFFFFF), text = Color(0xFFEDEDEB), muted = Color(0xFFA1A19D), faint = Color(0xFF6C6C68),
    accent = Color(0xFFEDEDEB), accentInk = Color(0xFF141414), agent = Color(0xFF82A9D9), agentSoft = Color(0x2282A9D9),
    ok = Color(0xFF6DBE8F), err = Color(0xFFE3786E), warn = Color(0xFFD6B25E), scrim = Color(0x99000000),
)

val LightPalette = Palette(
    dark = false,
    chrome = Color(0xFFF1F1EF), bg = Color(0xFFF8F8F7), surface = Color(0xFFFFFFFF), surface2 = Color(0xFFF0F0EE), surface3 = Color(0xFFE6E6E3),
    line = Color(0x14000000), line2 = Color(0x24000000), text = Color(0xFF1A1A19), muted = Color(0xFF62625E), faint = Color(0xFF9A9A95),
    accent = Color(0xFF1A1A19), accentInk = Color(0xFFFFFFFF), agent = Color(0xFF2F6AA8), agentSoft = Color(0x1A2F6AA8),
    ok = Color(0xFF2E8A57), err = Color(0xFFC0453A), warn = Color(0xFF93700F), scrim = Color(0x66000000),
)

/** Observable current palette; reading any colour from [C] subscribes the composable to theme changes. */
object C {
    var p by mutableStateOf(DarkPalette)
    val dark get() = p.dark
    val chrome get() = p.chrome; val bg get() = p.bg; val surface get() = p.surface; val surface2 get() = p.surface2; val surface3 get() = p.surface3
    val line get() = p.line; val line2 get() = p.line2; val text get() = p.text; val muted get() = p.muted; val faint get() = p.faint
    val accent get() = p.accent; val accentInk get() = p.accentInk; val agent get() = p.agent; val agentSoft get() = p.agentSoft
    val ok get() = p.ok; val err get() = p.err; val warn get() = p.warn; val scrim get() = p.scrim
}

private val type = Typography(
    displaySmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-1).sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.4).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 13.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.6.sp),
)

@Composable
fun SaarthiTheme(content: @Composable () -> Unit) {
    val sys = isSystemInDarkTheme()
    val dark = when (Prefs.theme) { "light" -> false; "dark" -> true; else -> sys }
    val pal = if (dark) DarkPalette else LightPalette
    // Swap the palette synchronously so the very first frame after a theme change is already correct.
    if (C.p != pal) C.p = pal
    val scheme = if (dark) darkColorScheme(
        primary = pal.accent, onPrimary = pal.accentInk, secondary = pal.agent, background = pal.bg, onBackground = pal.text,
        surface = pal.surface, onSurface = pal.text, surfaceVariant = pal.surface2, onSurfaceVariant = pal.muted, error = pal.err,
        outline = pal.line2, outlineVariant = pal.line, surfaceContainer = pal.surface, surfaceContainerHigh = pal.surface2,
        surfaceContainerHighest = pal.surface3, surfaceContainerLow = pal.surface, inverseSurface = pal.text, inverseOnSurface = pal.bg,
    ) else lightColorScheme(
        primary = pal.accent, onPrimary = pal.accentInk, secondary = pal.agent, background = pal.bg, onBackground = pal.text,
        surface = pal.surface, onSurface = pal.text, surfaceVariant = pal.surface2, onSurfaceVariant = pal.muted, error = pal.err,
        outline = pal.line2, outlineVariant = pal.line, surfaceContainer = pal.surface, surfaceContainerHigh = pal.surface2,
        surfaceContainerHighest = pal.surface3, surfaceContainerLow = pal.surface, inverseSurface = pal.text, inverseOnSurface = pal.bg,
    )
    MaterialTheme(colorScheme = scheme, typography = type, content = content)
}

/** The Saarthi hexagon-S mark, drawn natively in the current text colour. */
@Composable
fun Logo(size: Dp = 40.dp, modifier: Modifier = Modifier, color: Color = C.text) {
    Canvas(modifier.size(size)) {
        val s = this.size.width / 32f
        val hex = Path().apply { moveTo(16 * s, 2.5f * s); lineTo(27.7f * s, 9.25f * s); lineTo(27.7f * s, 22.75f * s); lineTo(16 * s, 29.5f * s); lineTo(4.3f * s, 22.75f * s); lineTo(4.3f * s, 9.25f * s); close() }
        drawPath(hex, color, style = Stroke(1.9f * s, join = StrokeJoin.Round))
        val p = Path().apply {
            moveTo(20.5f * s, 11.5f * s)
            cubicTo(19.5f * s, 10.2f * s, 17.9f * s, 9.5f * s, 16.1f * s, 9.5f * s)
            cubicTo(13.5f * s, 9.5f * s, 11.7f * s, 10.9f * s, 11.7f * s, 12.8f * s)
            cubicTo(11.7f * s, 17.2f * s, 20.7f * s, 15.1f * s, 20.7f * s, 19.4f * s)
            cubicTo(20.7f * s, 21.4f * s, 18.8f * s, 22.7f * s, 16.1f * s, 22.7f * s)
            cubicTo(14.2f * s, 22.7f * s, 12.5f * s, 21.9f * s, 11.5f * s, 20.5f * s)
        }
        drawPath(p, color, style = Stroke(2.1f * s, cap = StrokeCap.Round))
    }
}
