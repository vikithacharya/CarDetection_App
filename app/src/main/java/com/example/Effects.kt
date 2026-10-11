package com.example

import android.os.Build
import android.os.SystemClock
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.*

val RAINBOW = listOf(
    Color(0xFF4F8BFF),
    Color(0xFF8E6BFF),
    Color(0xFFE76BD6),
    Color(0xFFFF7A59),
    Color(0xFFFFC94D),
    Color(0xFF3DDC97),
    Color(0xFF4F8BFF)
)

val DONE_GREEN = Color(0xFF34D399)

fun Modifier.safeBlur(radius: Dp): Modifier =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        this.blur(radius, BlurredEdgeTreatment.Unbounded)
    } else {
        this
    }

/* ───────── dotted orb ───────── */
private class Dot(val x: Float, val y: Float, val z: Float, val u: Float, val seed: Float)

private val DOTS: Array<Dot> = run {
    val rnd = java.util.Random(7)
    val rings = 16
    buildList {
        for (k in 0 until rings) {
            val y = 1 - (k + 0.5f) / rings * 2
            val r = sqrt(1 - y * y)
            val m = max(4, (30 * r).roundToInt())
            for (j in 0 until m) {
                val a = j.toFloat() / m * 2 * PI.toFloat() + k * 0.35f
                add(Dot(cos(a) * r, y, sin(a) * r, (1 - y) / 2, rnd.nextFloat() * 6.283f))
            }
        }
    }.toTypedArray()
}

/**
 * A spinning 3D sphere of dots. [program] controls lighting animation:
 * 0 sweeping comet, 1 two roaming spotlights, 2 scanning band, 3 fast comet.
 */
@Composable
fun ThinkingOrb(program: Int, color: Color, modifier: Modifier = Modifier) {
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { time = (it - start) / 1e9f }
    }

    // Assemble: dots grow in from top down
    val k = remember { Animatable(0f) }
    LaunchedEffect(Unit) { k.animateTo(1f, tween(800, easing = EaseOutQuint)) }

    // Cross-fade between light programs
    val w = List(4) { q -> animateFloatAsState(if (program % 4 == q) 1f else 0f, tween(350), label = "w$q") }

    Canvas(modifier) {
        val t = time
        val radius = size.minDimension * 0.42f
        val scale = radius / 66f
        val yaw = t * 0.9f
        val cyw = cos(yaw)
        val syw = sin(yaw)
        val cp = cos(0.35f)
        val sp = sin(0.35f)
        val n = DOTS.size
        val head0 = (t * 300f) % n
        val head3 = (t * 480f) % n
        val a1 = t * 0.8f
        val b1 = sin(t * 0.5f) * 0.9f
        val f1x = cos(b1) * cos(a1)
        val f1y = sin(b1)
        val f1z = cos(b1) * sin(a1)
        val a2 = t * 0.55f + 2.1f
        val b2 = cos(t * 0.42f) * 0.9f
        val f2x = cos(b2) * cos(a2)
        val f2y = sin(b2)
        val f2z = cos(b2) * sin(a2)
        val lat = sin(t * 2.2f)
        val kv = k.value

        fun comet(i: Int, head: Float, width: Float): Float {
            var d = abs(i - head)
            if (d > n - d) d = n - d
            val v = max(0f, 1 - d / width)
            return v * v
        }

        for (pass in 0..1) for (i in 0 until n) {
            val d = DOTS[i]
            val x1 = d.x * cyw + d.z * syw
            val z1 = -d.x * syw + d.z * cyw
            val y2 = d.y * cp - z1 * sp
            val z2 = d.y * sp + z1 * cp
            val depth = (z2 + 1) / 2
            if ((depth >= 0.5f) != (pass == 1)) continue

            val ki = (kv * 1.6f - 0.6f * d.u).coerceIn(0f, 1f)
            if (ki <= 0.001f) continue

            var lit = 0f
            if (w[0].value > 0.001f) lit = max(lit, comet(i, head0, 16f) * w[0].value)
            if (w[1].value > 0.001f) {
                val v = max(
                    max(0f, (d.x * f1x + d.y * f1y + d.z * f1z - 0.72f) / 0.28f),
                    max(0f, (d.x * f2x + d.y * f2y + d.z * f2z - 0.72f) / 0.28f)
                )
                lit = max(lit, v * v * w[1].value)
            }
            if (w[2].value > 0.001f) {
                val e = d.y - lat
                val v = max(0f, 1 - e * e / 0.02f)
                lit = max(lit, v * v * w[2].value)
            }
            if (w[3].value > 0.001f) lit = max(lit, comet(i, head3, 22f) * w[3].value)

            val f = 2.8f / (2.8f - z2)
            val alpha = (0.1f + 0.035f * sin(d.seed + t * 1.6f) + 0.32f * depth * depth + 0.75f * lit).coerceIn(0f, 1f) * ki
            val r = (1.15f * (0.45f + 0.75f * depth) * f + 0.9f * lit) * (0.4f + 0.6f * ki) * scale
            drawCircle(
                color.copy(alpha = alpha),
                r,
                Offset(center.x + x1 * radius * ki * f, center.y - y2 * radius * ki * f)
            )
        }
    }
}

/** Three dots that blink in sequence */
@Composable
fun BlinkingDots(color: Color, modifier: Modifier = Modifier) {
    val t by rememberInfiniteTransition(label = "dots").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "t"
    )
    Canvas(modifier) {
        val r = size.height / 2
        for (k in 0..2) {
            val phase = ((t - k * 0.16f) % 1f + 1f) % 1f
            val a = if (phase < 0.4f) 0.25f + 0.75f * (phase / 0.4f)
            else if (phase < 0.8f) 1f - 0.75f * ((phase - 0.4f) / 0.4f)
            else 0.25f
            drawCircle(color.copy(alpha = a), r, Offset(r + k * r * 3.2f, r))
        }
    }
}

/* ───────── rainbow glow ───────── */
private fun DrawScope.rainbowRing(angle: Float, corner: Dp) {
    clipPath(Path().apply {
        addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(corner.toPx())))
    }) {
        rotate(angle) {
            drawCircle(Brush.sweepGradient(RAINBOW), radius = size.maxDimension)
        }
    }
}

/** Rotating rainbow edge + soft blurred glow behind card */
@Composable
fun RainbowGlow(corner: Dp, modifier: Modifier = Modifier) {
    val angle by rememberInfiniteTransition(label = "glow").animateFloat(
        0f, 360f, infiniteRepeatable(tween(3500, easing = LinearEasing)), label = "angle"
    )
    Box(modifier) {
        Canvas(Modifier.matchParentSize().graphicsLayer { alpha = 0.7f }.safeBlur(24.dp)) {
            rainbowRing(angle, corner)
        }
        Canvas(Modifier.matchParentSize()) {
            rainbowRing(angle, corner)
        }
    }
}

/* ───────── staggered reveal ───────── */
@Composable
fun Reveal(delayMs: Int, content: @Composable () -> Unit) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(delayMs.toLong())
        p.animateTo(1f, tween(450, easing = EaseOutQuint))
    }
    Box(
        Modifier
            .graphicsLayer {
                alpha = p.value
                translationY = (1 - p.value) * 8.dp.toPx()
            }
            .safeBlur(((1 - p.value) * 6).dp)
    ) {
        content()
    }
}

/* ───────── agent-style processing steps ───────── */
val STEP_TITLES = listOf(
    "Preparing photo",
    "Uploading to Gemini",
    "Recognising make & model",
    "Reading the answer"
)

@Composable
fun ProcessingSteps(stage: Int, starts: List<Long?>, note: String?, modifier: Modifier = Modifier) {
    val now by produceState(SystemClock.elapsedRealtime()) {
        while (true) {
            delay(100)
            value = SystemClock.elapsedRealtime()
        }
    }
    val fg = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier) {
        STEP_TITLES.forEachIndexed { i, title ->
            Reveal(i * 70) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                        when {
                            i < stage -> DoneCheck()
                            i == stage -> RainbowSpinner(Modifier.fillMaxSize())
                            else -> Box(
                                Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(muted.copy(alpha = 0.35f))
                            )
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        when {
                            i == stage -> ShimmerText(title, fg, muted)
                            i < stage -> Text(
                                title,
                                style = MaterialTheme.typography.bodyLarge,
                                color = fg.copy(alpha = 0.75f)
                            )
                            else -> Text(
                                title,
                                style = MaterialTheme.typography.bodyLarge,
                                color = muted.copy(alpha = 0.5f)
                            )
                        }
                        AnimatedVisibility(
                            visible = (i == stage && note != null),
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically()
                        ) {
                            Text(
                                note.orEmpty(),
                                style = MaterialTheme.typography.labelMedium,
                                color = muted,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }

                    val start = starts.getOrNull(i)
                    val end = starts.getOrNull(i + 1)
                    if (i < stage && start != null && end != null) {
                        Text(
                            "%.1fs".format((end - start) / 1000f),
                            style = MaterialTheme.typography.labelMedium,
                            color = muted
                        )
                    }
                }
            }
        }

        val t0 = starts.firstOrNull()
        if (t0 != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                "%.1fs elapsed".format((now - t0) / 1000f),
                style = MaterialTheme.typography.labelMedium,
                color = muted.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun ShimmerText(text: String, fg: Color, muted: Color) {
    val x by rememberInfiniteTransition(label = "shimmer").animateFloat(
        -300f, 900f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "x"
    )
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge.copy(
            brush = Brush.linearGradient(
                listOf(muted, fg, muted),
                start = Offset(x, 0f),
                end = Offset(x + 300f, 0f)
            )
        ),
        fontWeight = FontWeight.Medium
    )
}

@Composable
private fun RainbowSpinner(modifier: Modifier) {
    val angle by rememberInfiniteTransition(label = "spin").animateFloat(
        0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "a"
    )
    Canvas(modifier) {
        val w = 2.dp.toPx()
        rotate(angle) {
            drawArc(
                Brush.sweepGradient(RAINBOW),
                0f,
                280f,
                false,
                topLeft = Offset(w, w),
                size = Size(size.width - 2 * w, size.height - 2 * w),
                style = Stroke(w, cap = StrokeCap.Round)
            )
        }
    }
}

@Composable
private fun DoneCheck() {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        p.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 500f))
    }
    Canvas(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = p.value
                scaleY = p.value
            }
    ) {
        drawCircle(DONE_GREEN.copy(alpha = 0.18f))
        val s = size.width
        drawPath(
            Path().apply {
                moveTo(s * 0.3f, s * 0.52f)
                lineTo(s * 0.45f, s * 0.66f)
                lineTo(s * 0.71f, s * 0.38f)
            },
            DONE_GREEN,
            style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}
