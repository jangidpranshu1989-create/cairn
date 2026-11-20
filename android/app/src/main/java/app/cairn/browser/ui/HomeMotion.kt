package app.cairn.browser.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Motion kit for the home page: a slow aurora behind the header, a 3D Cairn orb that tilts with the phone, staggered
 * entrances and press-tilt cards. Every effect turns off when the user disables animations (Settings → Accessibility →
 * Remove animations), and nothing here blocks input or loads assets — it is all drawn with Canvas + graphicsLayer.
 */

/** Set by screenshot tests so every animation renders its resting frame. */
object MotionKit { @Volatile var still = false }

/** True unless the system animator scale is 0 ("Remove animations"). */
@Composable
fun rememberMotion(): Boolean {
    val ctx = LocalContext.current
    if (MotionKit.still || androidx.compose.ui.platform.LocalInspectionMode.current) return false   // previews / store screenshots: a still frame
    return remember { android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f }
}

/** Device tilt in -1..1 (x = roll, y = pitch), smoothed; (0,0) when motion is off or there's no accelerometer. */
@Composable
fun rememberTilt(active: Boolean): State<Offset> {
    val ctx = LocalContext.current
    val tilt = remember { mutableStateOf(Offset.Zero) }
    DisposableEffect(active) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val s = sm?.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (!active || sm == null || s == null) return@DisposableEffect onDispose { }
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val x = (-e.values[0] / 9.81f).coerceIn(-1f, 1f)
                val y = ((e.values[1] - 6.5f) / 9.81f).coerceIn(-1f, 1f)   // phones are usually held ~40° from flat
                val o = tilt.value
                tilt.value = Offset(o.x + (x - o.x) * .12f, o.y + (y - o.y) * .12f)
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sm.registerListener(l, s, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(l) }
    }
    return tilt
}

/** Three soft colour orbs drifting on slow ellipses — a living mesh gradient behind the header. */
@Composable
fun AuroraBackdrop(motion: Boolean, modifier: Modifier = Modifier, tilt: Offset = Offset.Zero) {
    val t = if (motion) {
        val tr = rememberInfiniteTransition(label = "aurora")
        tr.animateFloat(0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(22_000, easing = LinearEasing)), label = "t").value
    } else 0.8f
    val dark = C.dark
    val colors = listOf(
        Color(0xFFE0A458).copy(alpha = if (dark) .26f else .20f),
        Color(0xFF6E7BFF).copy(alpha = if (dark) .24f else .14f),
        Color(0xFF2FC6A0).copy(alpha = if (dark) .15f else .10f),
    )
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val px = tilt.x * 24.dp.toPx(); val py = tilt.y * 16.dp.toPx()
        colors.forEachIndexed { i, c ->
            val ph = t * (1f + i * .35f) + i * 2.1f
            val cx = w * (.25f + .5f * ((i + 1) % 3) / 2f) + cos(ph) * w * .18f + px * (i + 1) * .5f
            val cy = h * (.30f + .18f * i) + sin(ph * 1.3f) * h * .14f + py * (i + 1) * .5f
            val r = w * (.46f - i * .06f)
            drawCircle(Brush.radialGradient(listOf(c, c.copy(alpha = 0f)), Offset(cx, cy), r), r, Offset(cx, cy))
        }
        // Fade into the page so the aurora never fights the content below.
        drawRect(Brush.verticalGradient(0f to Color.Transparent, .62f to Color.Transparent, 1f to C.bg))
    }
}

/**
 * The 3D Cairn orb: a glass sphere with a specular highlight, an orbit ring and the three-stone mark floating inside.
 * It tilts with the phone (parallax between ring, sphere and stones), bobs gently, and the top stone "breathes".
 */
@Composable
fun CairnOrb(size: Dp, motion: Boolean, tilt: Offset, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val tr = rememberInfiniteTransition(label = "orb")
    val bob = if (motion) tr.animateFloat(-1f, 1f, infiniteRepeatable(tween(3200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bob").value else 0f
    val spin = if (motion) tr.animateFloat(0f, 360f, infiniteRepeatable(tween(14_000, easing = LinearEasing)), label = "spin").value else 30f
    val lift = if (motion) tr.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "lift").value else 0f
    val density = LocalDensity.current
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val sc by animateFloatAsState(if (pressed) .92f else 1f, spring(.5f, 400f), label = "sc")
    val agent = C.agent; val dark = C.dark
    Box(modifier.size(size).graphicsLayer {
        rotationY = tilt.x * 18f; rotationX = -tilt.y * 14f
        translationY = bob * 4.dp.toPx(); scaleX = sc; scaleY = sc
        cameraDistance = 10f * density.density
    }.then(if (onClick != null) Modifier.clickableNoRipple(press, onClick) else Modifier), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = this.size.minDimension / 2f; val c = center
            // Contact shadow
            drawOval(Brush.radialGradient(listOf(Color.Black.copy(alpha = if (dark) .45f else .18f), Color.Transparent), Offset(c.x, c.y + r * 1.02f), r * .7f),
                topLeft = Offset(c.x - r * .7f, c.y + r * .86f), size = androidx.compose.ui.geometry.Size(r * 1.4f, r * .3f))
            // Glass sphere
            drawCircle(Brush.radialGradient(listOf(agent.copy(alpha = .55f), agent.copy(alpha = .18f), (if (dark) Color(0xFF121214) else Color.White).copy(alpha = .9f)),
                Offset(c.x - r * .25f + tilt.x * r * .15f, c.y - r * .3f + tilt.y * r * .15f), r * 1.25f), r * .86f, c)
            drawCircle(Brush.linearGradient(listOf(Color.White.copy(alpha = .5f), Color.White.copy(alpha = 0f)), Offset(c.x, c.y - r), Offset(c.x, c.y + r * .3f)), r * .86f, c, style = Stroke(1.2.dp.toPx()))
            // Specular highlight that slides opposite to the tilt (sells the depth)
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = .55f), Color.Transparent), Offset(c.x - r * .32f - tilt.x * r * .2f, c.y - r * .42f - tilt.y * r * .2f), r * .32f),
                r * .32f, Offset(c.x - r * .32f - tilt.x * r * .2f, c.y - r * .42f - tilt.y * r * .2f))
            // Orbit ring with a travelling spark
            val rx = r * .98f; val ry = r * .3f
            rotate(-14f + tilt.x * 10f, c) {
                drawOval(agent.copy(alpha = .55f), Offset(c.x - rx, c.y - ry), androidx.compose.ui.geometry.Size(rx * 2, ry * 2), style = Stroke(1.4.dp.toPx()))
                val a = Math.toRadians(spin.toDouble())
                val sp = Offset(c.x + (rx * cos(a)).toFloat(), c.y + (ry * sin(a)).toFloat())
                drawCircle(Brush.radialGradient(listOf(Color.White, agent.copy(alpha = 0f)), sp, 5.dp.toPx()), 5.dp.toPx(), sp)
            }
        }
        Logo(size * .42f, Modifier.graphicsLayer { translationX = tilt.x * 5.dp.toPx(); translationY = tilt.y * 4.dp.toPx() - 1.dp.toPx() },
            color = if (dark) Color(0xFFF4F1EA) else Color(0xFF1B1B1D), lift = lift)
    }
}

private fun Modifier.clickableNoRipple(src: MutableInteractionSource, onClick: () -> Unit): Modifier =
    clickable(interactionSource = src, indication = null) { Haptics.tick(); onClick() }

/** Fade + rise into place, staggered by [index], once per composition. */
fun Modifier.enter(index: Int, motion: Boolean): Modifier = if (!motion) this else composed {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(60L * index); a.animateTo(1f, tween(520, easing = FastOutSlowInEasing)) }
    graphicsLayer { alpha = a.value; translationY = (1f - a.value) * 18.dp.toPx() }
}

/** Press feedback for cards: the card tips back in 3D and shrinks a little, springing back on release. */
fun Modifier.pressTilt(src: MutableInteractionSource, motion: Boolean): Modifier = if (!motion) this else composed {
    val pressed by src.collectIsPressedAsState()
    val p by animateFloatAsState(if (pressed) 1f else 0f, spring(.55f, 500f), label = "pt")
    val d = LocalDensity.current.density
    graphicsLayer { rotationX = p * 8f; scaleX = 1f - p * .035f; scaleY = 1f - p * .035f; cameraDistance = 12f * d }
}
