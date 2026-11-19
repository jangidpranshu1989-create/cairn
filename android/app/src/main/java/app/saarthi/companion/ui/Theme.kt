package app.saarthi.companion.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
import androidx.compose.foundation.Canvas

object C {
    val bg = Color(0xFF0B0C0F); val chrome = Color(0xFF0F1115); val surface = Color(0xFF15171D); val surface2 = Color(0xFF1C1F27)
    val line = Color(0x14FFFFFF); val text = Color(0xFFECE9E2); val muted = Color(0xFF9A978F); val faint = Color(0xFF6B6962)
    val accent = Color(0xFFE9A23B); val accentInk = Color(0xFF1B1205); val agent = Color(0xFF8B97FF)
    val ok = Color(0xFF5BC48A); val err = Color(0xFFF0776B); val warn = Color(0xFFE9C46A)
}

private val scheme = darkColorScheme(
    primary = C.accent, onPrimary = C.accentInk, secondary = C.agent, background = C.bg, onBackground = C.text,
    surface = C.surface, onSurface = C.text, surfaceVariant = C.surface2, onSurfaceVariant = C.muted, error = C.err, outline = C.line,
)

private val type = Typography(
    displaySmall = TextStyle(fontWeight = FontWeight.ExtraBold, fontSize = 34.sp, lineHeight = 38.sp, letterSpacing = (-0.8).sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.4).sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 0.8.sp),
)

@Composable
fun SaarthiTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme, typography = type, content = content)

/** The Saarthi hexagon-S mark drawn natively. */
@Composable
fun Logo(size: Dp = 40.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.width / 32f
        val hex = Path().apply { moveTo(16 * s, 2.5f * s); lineTo(27.7f * s, 9.25f * s); lineTo(27.7f * s, 22.75f * s); lineTo(16 * s, 29.5f * s); lineTo(4.3f * s, 22.75f * s); lineTo(4.3f * s, 9.25f * s); close() }
        drawPath(hex, C.accent, style = Stroke(2f * s, join = StrokeJoin.Round))
        val p = Path().apply {
            moveTo(20.5f * s, 11.5f * s)
            cubicTo(19.5f * s, 10.2f * s, 17.9f * s, 9.5f * s, 16.1f * s, 9.5f * s)
            cubicTo(13.5f * s, 9.5f * s, 11.7f * s, 10.9f * s, 11.7f * s, 12.8f * s)
            cubicTo(11.7f * s, 17.2f * s, 20.7f * s, 15.1f * s, 20.7f * s, 19.4f * s)
            cubicTo(20.7f * s, 21.4f * s, 18.8f * s, 22.7f * s, 16.1f * s, 22.7f * s)
            cubicTo(14.2f * s, 22.7f * s, 12.5f * s, 21.9f * s, 11.5f * s, 20.5f * s)
        }
        drawPath(p, C.text, style = Stroke(2.3f * s, cap = StrokeCap.Round))
    }
}
