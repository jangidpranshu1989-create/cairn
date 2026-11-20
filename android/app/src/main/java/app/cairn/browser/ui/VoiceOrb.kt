package app.cairn.browser.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Fluid voice orb. Three translucent, softly deforming blobs orbit each other; their size and wobble follow the
 * live microphone level (listening) or a synthetic speech envelope (speaking). "thinking" slows into a breathing
 * pulse. Pure Canvas, so it costs almost nothing per frame.
 */
@Composable
fun VoiceOrb(level: Float, mode: String = "listen", size: Dp = 132.dp, modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition(label = "orb")
    val t by inf.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(if (mode == "think") 9000 else 5200, easing = LinearEasing)), label = "t")
    val breathe by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "b")
    // Speaking has no mic: drive it with a pseudo-random speech-like envelope.
    val speak by inf.animateFloat(0f, 1f, infiniteRepeatable(keyframes { durationMillis = 1300; 0.2f at 0; 0.85f at 180; 0.35f at 360; 0.95f at 560; 0.25f at 800; 0.7f at 1000; 0.2f at 1300 }), label = "s")
    val target = when (mode) { "speak" -> speak; "think" -> .25f + .2f * breathe; else -> level }
    val lvl by animateFloatAsState(target.coerceIn(0f, 1f), spring(dampingRatio = .55f, stiffness = 260f), label = "lvl")
    val a = C.agent; val b = C.accent
    Canvas(modifier.size(size)) {
        val c = center; val r0 = this.size.minDimension * .26f
        // Outer glow
        drawCircle(Brush.radialGradient(listOf(a.copy(alpha = .28f + .25f * lvl), Color.Transparent), c, r0 * (2.1f + .5f * lvl)), r0 * (2.1f + .5f * lvl), c)
        blob(c, r0 * (1.18f + .38f * lvl), t, 0f, 0.10f + .16f * lvl, Brush.radialGradient(listOf(b.copy(alpha = .55f), a.copy(alpha = .15f)), c, r0 * 1.6f))
        blob(c, r0 * (1.05f + .3f * lvl), -t * 1.3f, 2.1f, 0.12f + .18f * lvl, Brush.radialGradient(listOf(a.copy(alpha = .75f), a.copy(alpha = .25f)), c, r0 * 1.4f))
        blob(c, r0 * (.92f + .22f * lvl), t * 1.7f, 4.2f, 0.08f + .12f * lvl, Brush.radialGradient(listOf(Color.White.copy(alpha = .9f), a), Offset(c.x - r0 * .3f, c.y - r0 * .35f), r0 * 1.3f))
    }
}

private fun DrawScope.blob(c: Offset, r: Float, t: Float, phase: Float, wobble: Float, brush: Brush) {
    val p = Path(); val n = 64
    for (i in 0..n) {
        val th = (i.toFloat() / n) * 2 * PI.toFloat()
        val k = 1f + wobble * (sin(3 * th + t + phase) * .6f + sin(5 * th - t * 1.4f + phase) * .3f + cos(2 * th + t * .7f) * .25f)
        val x = c.x + cos(th) * r * k; val y = c.y + sin(th) * r * k
        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    p.close(); drawPath(p, brush)
}

/** Compact live bars for inline indicators (read-aloud bar, composer while listening). */
@Composable
fun VoiceBars(active: Boolean, modifier: Modifier = Modifier, bars: Int = 5) {
    val inf = rememberInfiniteTransition(label = "bars")
    val vals = (0 until bars).map { i -> inf.animateFloat(.25f, 1f, infiniteRepeatable(tween(420 + i * 90, easing = FastOutSlowInEasing), RepeatMode.Reverse, StartOffset(i * 70)), label = "b$i") }
    val col = C.agent
    Canvas(modifier.size((bars * 6).dp, 18.dp)) {
        val w = this.size.width / (bars * 2 - 1)
        vals.forEachIndexed { i, v -> val h = this.size.height * (if (active) v.value else .2f)
            drawRoundRect(col, Offset(i * 2 * w, (this.size.height - h) / 2), androidx.compose.ui.geometry.Size(w, h), androidx.compose.ui.geometry.CornerRadius(w / 2)) }
    }
}
