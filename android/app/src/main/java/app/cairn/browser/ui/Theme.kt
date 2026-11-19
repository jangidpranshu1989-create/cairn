package app.cairn.browser.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cairn.browser.R
import app.cairn.browser.data.Prefs

/**
 * Cairn's charcoal palette. Neutral, warm-grey surfaces; the single hue is "sandstone" — the colour of the top stone in the
 * Cairn mark — used only for the agent (what the AI is doing) so it always reads as "Cairn is acting".
 */
@Immutable
data class Palette(
    val dark: Boolean,
    val chrome: Color, val bg: Color, val surface: Color, val surface2: Color, val surface3: Color,
    val line: Color, val line2: Color, val text: Color, val muted: Color, val faint: Color,
    val accent: Color, val accentInk: Color, val agent: Color, val agentSoft: Color,
    val ok: Color, val err: Color, val warn: Color, val scrim: Color, val glass: Color,
)

val DarkPalette = Palette(
    dark = true,
    chrome = Color(0xFF161618), bg = Color(0xFF1C1C1F), surface = Color(0xFF232326), surface2 = Color(0xFF2B2B2F), surface3 = Color(0xFF36363B),
    line = Color(0x12FFFFFF), line2 = Color(0x21FFFFFF), text = Color(0xFFEEEDEA), muted = Color(0xFFA7A6A1), faint = Color(0xFF716F6B),
    accent = Color(0xFFEEEDEA), accentInk = Color(0xFF18181A), agent = Color(0xFFD9C3A0), agentSoft = Color(0x1FD9C3A0),
    ok = Color(0xFF86C7A0), err = Color(0xFFE88A78), warn = Color(0xFFDDB673), scrim = Color(0xA60C0C0E), glass = Color(0xC71A1A1D),
)

val LightPalette = Palette(
    dark = false,
    chrome = Color(0xFFF3F2EF), bg = Color(0xFFFAF9F7), surface = Color(0xFFFFFFFF), surface2 = Color(0xFFF0EFEC), surface3 = Color(0xFFE4E3DF),
    line = Color(0x14000000), line2 = Color(0x24000000), text = Color(0xFF1B1B1D), muted = Color(0xFF5E5D59), faint = Color(0xFF999792),
    accent = Color(0xFF1F1F22), accentInk = Color(0xFFFFFFFF), agent = Color(0xFF8A6838), agentSoft = Color(0x178A6838),
    ok = Color(0xFF2F8456), err = Color(0xFFC0453A), warn = Color(0xFF8E6B12), scrim = Color(0x73000000), glass = Color(0xD9F6F5F2),
)

/** Observable current palette; reading any colour from [C] subscribes the composable to theme changes. */
object C {
    var p by mutableStateOf(DarkPalette)
    val dark get() = p.dark
    val chrome get() = p.chrome; val bg get() = p.bg; val surface get() = p.surface; val surface2 get() = p.surface2; val surface3 get() = p.surface3
    val line get() = p.line; val line2 get() = p.line2; val text get() = p.text; val muted get() = p.muted; val faint get() = p.faint
    val accent get() = p.accent; val accentInk get() = p.accentInk; val agent get() = p.agent; val agentSoft get() = p.agentSoft
    val ok get() = p.ok; val err get() = p.err; val warn get() = p.warn; val scrim get() = p.scrim; val glass get() = p.glass
}

/** Inter (SIL Open Font License, rsms.me/inter) — bundled, so the app never downloads fonts. Display cut for large titles. */
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_semibold, FontWeight.Bold),
)
val InterDisplay = FontFamily(
    Font(R.font.inter_display_semibold, FontWeight.SemiBold),
    Font(R.font.inter_display_semibold, FontWeight.Medium),
    Font(R.font.inter_display_bold, FontWeight.Bold),
)

private val trim = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
private fun t(size: Float, line: Float, w: FontWeight = FontWeight.Normal, track: Float = 0f, fam: FontFamily = Inter) =
    TextStyle(fontFamily = fam, fontWeight = w, fontSize = size.sp, lineHeight = line.sp, letterSpacing = track.sp, lineHeightStyle = trim)

/** Type scale: tight negative tracking on display sizes, neutral on body, slightly open on micro labels. */
val CairnType = Typography(
    displayLarge = t(44f, 48f, FontWeight.SemiBold, -1.6f, InterDisplay),
    displayMedium = t(36f, 40f, FontWeight.SemiBold, -1.2f, InterDisplay),
    displaySmall = t(30f, 36f, FontWeight.SemiBold, -0.9f, InterDisplay),
    headlineMedium = t(26f, 32f, FontWeight.SemiBold, -0.6f, InterDisplay),
    headlineSmall = t(22f, 28f, FontWeight.SemiBold, -0.45f, InterDisplay),
    titleLarge = t(19f, 25f, FontWeight.SemiBold, -0.3f),
    titleMedium = t(16f, 22f, FontWeight.SemiBold, -0.15f),
    titleSmall = t(14f, 20f, FontWeight.Medium, -0.05f),
    bodyLarge = t(15.5f, 23f, track = -0.1f),
    bodyMedium = t(14f, 20f, track = -0.05f),
    bodySmall = t(12.5f, 17f),
    labelLarge = t(15f, 20f, FontWeight.SemiBold, -0.1f),
    labelMedium = t(13f, 18f, FontWeight.Medium),
    labelSmall = t(11f, 14f, FontWeight.SemiBold, 0.5f),
)

@Composable
fun CairnTheme(content: @Composable () -> Unit) {
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
    MaterialTheme(colorScheme = scheme, typography = CairnType, content = content)
}

// ---------------------------------------------------------------- Mark
private val STONES = listOf(
    "M4.3 17.4c0-2 3.4-3.3 7.9-3.3 4.4 0 7.5 1.3 7.5 3.3 0 1.8-3 2.9-7.6 2.9-4.7 0-7.8-1.1-7.8-2.9Z",
    "M6.6 11c0-1.7 2.3-2.9 5.3-2.9 3 0 4.9 1.1 4.9 2.7 0 1.6-2.1 2.6-5.1 2.6-3 0-5.1-1-5.1-2.4Z",
    "M9.3 5.75c0-1.3 1.3-2.3 3.1-2.3 1.7 0 2.7.9 2.7 2.1 0 1.2-1.2 2-2.9 2-1.8 0-2.9-.7-2.9-1.8Z",
)
private val stonePaths by lazy { STONES.map { PathParser().parsePathString(it).toPath() } }

/**
 * The Cairn mark: three balanced stones. The top stone carries the sandstone accent — it's the "agent" stone.
 * [lift] (0..1) raises the top stone slightly; the agent uses it as a breathing animation while it works.
 */
@Composable
fun Logo(size: Dp = 40.dp, modifier: Modifier = Modifier, color: Color = C.text, top: Color = C.agent, lift: Float = 0f) {
    val paths = remember { stonePaths }
    Canvas(modifier.size(size)) {
        val s = this.size.width / 24f
        scale(s, s, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            drawPath(paths[0], color); drawPath(paths[1], color)
            translate(0f, -1.1f * lift) { drawPath(paths[2], top) }
        }
    }
}

// ---------------------------------------------------------------- Icons
/** Builds a Lucide icon (24×24, 1.75 stroke, round caps) as a Compose vector. Called lazily from [Ic]. */
fun lucide(name: String, filled: Boolean, vararg d: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        d.forEach {
            addPath(PathParser().parsePathString(it).toNodes(), fill = if (filled) SolidColor(Color.Black) else null,
                stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }
    }.build()

// ---------------------------------------------------------------- Haptics
/** System haptic vocabulary (no custom buzzes): ticks for agent taps, confirm/reject for outcomes. */
object Haptics {
    var view: View? = null
    private fun fire(c: Int) { if (Prefs.haptics) view?.performHapticFeedback(c) }
    fun tick() = fire(if (Build.VERSION.SDK_INT >= 27) HapticFeedbackConstants.KEYBOARD_PRESS else HapticFeedbackConstants.VIRTUAL_KEY)
    fun agentTap() = fire(if (Build.VERSION.SDK_INT >= 23) HapticFeedbackConstants.CONTEXT_CLICK else HapticFeedbackConstants.VIRTUAL_KEY)
    fun confirm() = fire(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
    fun reject() = fire(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS)
    fun gesture() = fire(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.GESTURE_START else HapticFeedbackConstants.VIRTUAL_KEY)
    fun long() = fire(HapticFeedbackConstants.LONG_PRESS)
}
