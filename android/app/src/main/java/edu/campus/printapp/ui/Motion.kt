package edu.campus.printapp.ui

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * How XeoGo moves (the student app only: the staff app stays still).
 *
 * One small set of pieces, used by the opening (Intro.kt), the first page and
 * the uploads (UploadStage.kt), so everything moves in the same way: things
 * arrive quickly and settle softly, nothing bounces for long, and whatever
 * keeps moving (a light across a bar, a turning ring) does so slowly.
 */

/** The colours of the logo: its dark blue ground, and the swoosh from cyan to orange. */
object Glow {
    val Night = Color(0xFF010A33)
    val Navy = Color(0xFF02124A)
    val Deep = Color(0xFF031B66)
    val Royal = Color(0xFF05278C)
    val Cyan = Color(0xFF35CCFF)
    val Blue = Color(0xFF2F6BFF)
    val Violet = Color(0xFF7A4DFF)
    val Pink = Color(0xFFFF3D9A)
    val Orange = Color(0xFFFFA62B)
    val Ice = Color(0xFFBFE3FF)

    /** Once around the ring: ends where it starts, so a turning ring has no seam. */
    val sweep = listOf(Cyan, Blue, Violet, Pink, Orange, Cyan)

    /** The bar of a file on its way. */
    val flow = listOf(Blue, Cyan)
}

/** Arrives fast, settles softly. */
val EaseOutSoft: Easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
/** Starts and ends gently. */
val EaseInOutSoft: Easing = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)
/** Goes a little too far and comes back: for things that pop in. */
val EaseOutBack: Easing = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)

/**
 * True in the student app when the phone shows animations. False in the staff
 * app, and when the phone is set to "remove animations" (then things simply
 * appear: nothing here is needed to use the app).
 */
val LocalLively = staticCompositionLocalOf { false }

/** The phone's own setting: no animations (accessibility, or battery saver on some phones). */
@Composable
fun animationsOff(): Boolean {
    val context = LocalContext.current
    return remember {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
            .getOrDefault(false)
    }
}

/** Where a value is on its way from `from` to `to` (0..1) when the whole is at t, eased. */
fun stretch(t: Float, from: Float, to: Float, easing: Easing = LinearEasing): Float =
    easing.transform(((t - from) / (to - from)).coerceIn(0f, 1f))

/**
 * Part of a page arriving: it rises a little and fades in. Pieces with a
 * higher `index` follow one after the other. Without `LocalLively` it is
 * simply there.
 */
@Composable
fun Modifier.riseIn(shown: Boolean, index: Int = 0, distance: Dp = 20.dp): Modifier {
    if (!LocalLively.current) return this
    val t by animateFloatAsState(if (shown) 1f else 0f,
        tween(durationMillis = 560, delayMillis = if (shown) 80 * index else 0, easing = EaseOutSoft), label = "riseIn")
    val px = with(LocalDensity.current) { distance.toPx() }
    return graphicsLayer {
        alpha = t
        translationY = (1f - t) * px
    }
}

/**
 * A progress bar that glides to its new length, with a light moving along
 * the filled part so it never looks stuck. `fraction` null: no length is
 * known yet (reading, checking): a short piece travels back and forth.
 * `colors`: the filled part runs from the first to the last of them.
 */
@Composable
fun FlowBar(fraction: Float?, modifier: Modifier = Modifier, color: Color = CP.Ink, track: Color = CP.Line, height: Dp = 6.dp,
            colors: List<Color>? = null) {
    val shown by animateFloatAsState((fraction ?: 0f).coerceIn(0f, 1f), tween(380, easing = EaseOutSoft), label = "flowBar")
    val loop = rememberInfiniteTransition(label = "flowBar")
    val light by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(1500, easing = LinearEasing)), label = "light")
    val travel by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = EaseInOutSoft), RepeatMode.Reverse), label = "travel")
    Box(modifier.fillMaxWidth().height(height).drawBehind {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(track, cornerRadius = r)
        if (fraction == null) {
            val w = size.width * 0.3f
            val x = (size.width - w) * travel
            drawRoundRect(if (colors != null) Brush.horizontalGradient(colors, startX = x, endX = x + w) else SolidColor(color),
                topLeft = Offset(x, 0f), size = Size(w, size.height), cornerRadius = r)
        } else if (shown > 0f) {
            val w = (size.width * shown).coerceAtLeast(size.height)
            drawRoundRect(if (colors != null) Brush.horizontalGradient(colors, startX = 0f, endX = w) else SolidColor(color),
                size = Size(w, size.height), cornerRadius = r)
            // the light: a soft white band that crosses the filled part
            val band = size.width * 0.28f
            val x = -band + (w + band) * light
            clipRect(right = w) {
                drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.42f), Color.Transparent),
                    startX = x, endX = x + band), topLeft = Offset(x, 0f), size = Size(band, size.height))
            }
        }
    })
}

/**
 * A ring that fills as work gets done, in the colours of the logo, turning
 * slowly, with a bright head. `fraction` null: nothing is known yet (the
 * files are still being read): a piece of the ring goes round and round.
 * `done`: it closes, and turns the calm green of "ready". Whatever is put
 * inside sits in its middle.
 */
@Composable
fun ProgressRing(fraction: Float?, done: Boolean, modifier: Modifier = Modifier, size: Dp = 68.dp, stroke: Dp = 6.dp,
                 track: Color = Color.White.copy(alpha = 0.14f), content: @Composable BoxScope.() -> Unit = {}) {
    val shown by animateFloatAsState(if (done) 1f else (fraction ?: 0f).coerceIn(0f, 1f), tween(450, easing = EaseOutSoft), label = "ring")
    val settle by animateFloatAsState(if (done) 1f else 0f, tween(500, easing = EaseOutSoft), label = "ringDone")
    val search by animateFloatAsState(if (fraction == null && !done) 1f else 0f, tween(320), label = "ringSearch")
    val loop = rememberInfiniteTransition(label = "ring")
    val turn by loop.animateFloat(0f, 360f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "turn")
    val spin by loop.animateFloat(0f, 360f, infiniteRepeatable(tween(1150, easing = LinearEasing)), label = "spin")
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val w = stroke.toPx()
            val inset = w / 2
            val arc = Size(this.size.width - w, this.size.height - w)
            val corner = Offset(inset, inset)
            fun head(degrees: Float, alpha: Float) {
                val a = degrees * PI.toFloat() / 180f
                val c = Offset(center.x + (arc.width / 2) * cos(a), center.y + (arc.height / 2) * sin(a))
                drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.9f * alpha), Color.White.copy(alpha = 0f)), c, w * 1.6f),
                    radius = w * 1.6f, center = c)
            }
            drawArc(track, 0f, 360f, false, topLeft = corner, size = arc, style = Stroke(w))
            // nothing is known yet: a piece of the ring goes round, bright at its head, fading behind
            if (search > 0f) {
                val clear = Glow.Cyan.copy(alpha = 0f)
                rotate(spin) {
                    drawArc(Brush.sweepGradient(0f to clear, 0.30f to Glow.Cyan, 0.33f to Glow.Cyan, 0.34f to clear, 1f to clear),
                        0f, 108f, false, topLeft = corner, size = arc, style = Stroke(w, cap = StrokeCap.Round), alpha = search)
                }
                head(spin + 108f, search)
            }
            // while it works: the logo's colours, flowing round the ring (the arc itself starts at the top
            // and grows clockwise, so its length always reads as "this much is done"); when done: green
            val working = (1f - settle) * (1f - search)
            if (working > 0f) {
                val sweep = 360f * shown.coerceAtLeast(0.02f)
                rotate(turn) {
                    drawArc(Brush.sweepGradient(Glow.sweep), -90f - turn, sweep, false, topLeft = corner, size = arc,
                        style = Stroke(w, cap = StrokeCap.Round), alpha = working)
                }
                head(-90f + sweep, working)
            }
            if (settle > 0f) {
                drawArc(CP.Accent, -90f, 360f, false, topLeft = corner, size = arc, style = Stroke(w), alpha = settle)
            }
        }
        content()
    }
}

/** A tick that draws itself. `shown` false: nothing. */
@Composable
fun DrawnCheck(shown: Boolean, modifier: Modifier = Modifier, size: Dp = 22.dp, color: Color = Color.White, stroke: Dp = 2.6.dp,
               delayMillis: Int = 0) {
    val t by animateFloatAsState(if (shown) 1f else 0f, tween(if (shown) 380 else 0, delayMillis = if (shown) delayMillis else 0,
        easing = EaseOutSoft), label = "check")
    Canvas(modifier.size(size)) {
        if (t <= 0f) return@Canvas
        val s = this.size.minDimension
        val whole = Path().apply {
            moveTo(s * 0.20f, s * 0.53f)
            lineTo(s * 0.42f, s * 0.74f)
            lineTo(s * 0.80f, s * 0.30f)
        }
        val measure = PathMeasure().apply { setPath(whole, false) }
        val part = Path()
        measure.getSegment(0f, measure.length * t, part, true)
        drawPath(part, color, style = Stroke(stroke.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** A filled circle that pops in with a tick drawing itself inside: "this one is done". */
@Composable
fun CheckPop(shown: Boolean, modifier: Modifier = Modifier, size: Dp = 20.dp, color: Color = CP.Accent) {
    val pop by animateFloatAsState(if (shown) 1f else 0f,
        if (shown) spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium) else tween(0), label = "checkPop")
    Box(modifier.size(size).graphicsLayer { scaleX = pop; scaleY = pop; alpha = pop.coerceIn(0f, 1f) }
        .drawBehind { drawCircle(color) }, contentAlignment = Alignment.Center) {
        DrawnCheck(shown, size = size * 0.78f, stroke = (size.value * 0.12f).dp, delayMillis = 120)
    }
}

/**
 * A small burst in the logo's colours, once, when `shown` turns true: from
 * the middle of whatever it is put on, outwards, and gone. For the moment
 * something is finished. It is drawn over the content and takes no room.
 */
@Composable
fun Modifier.sparks(shown: Boolean, reach: Dp = 26.dp): Modifier {
    if (!LocalLively.current) return this
    val t by animateFloatAsState(if (shown) 1f else 0f,
        tween(if (shown) 820 else 0, delayMillis = if (shown) 160 else 0, easing = LinearEasing), label = "sparks")
    return drawWithContent {
        drawContent()
        if (t <= 0f || t >= 1f) return@drawWithContent
        val far = reach.toPx()
        val out = EaseOutSoft.transform(t)
        for (i in 0 until 10) {
            // ten of them, every second one a little shorter and turned, so it is no neat circle
            val a = (i * 36f + (if (i % 2 == 0) 0f else 15f) - 90f) * PI.toFloat() / 180f
            val d = size.minDimension * 0.45f + far * (if (i % 2 == 0) 1f else 0.66f) * out
            drawCircle(Glow.sweep[i % 5].copy(alpha = 1f - t * t), radius = (2.5f - 1.5f * t).dp.toPx(),
                center = Offset(center.x + cos(a) * d, center.y + sin(a) * d))
        }
    }
}

/** Three dots that take turns: "working on it", without a length. */
@Composable
fun Dots(modifier: Modifier = Modifier, color: Color = CP.Muted, dot: Dp = 5.dp) {
    val loop = rememberInfiniteTransition(label = "dots")
    val t by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(1050, easing = LinearEasing)), label = "dots")
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        for (i in 0..2) {
            Canvas(Modifier.size(dot * 1.9f, dot * 2.4f)) {
                // each dot rises a third of a turn after the one before
                val phase = ((t - i * 0.16f) % 1f + 1f) % 1f
                val lift = if (phase < 0.4f) sin(phase / 0.4f * PI.toFloat()) else 0f
                drawCircle(color.copy(alpha = 0.45f + 0.55f * lift), radius = dot.toPx() / 2,
                    center = Offset(size.width / 2, size.height * 0.68f - lift * size.height * 0.36f))
            }
        }
    }
}

/**
 * A soft ring that grows out from behind something and fades, again and
 * again: "tap here". Drawn behind the content it is put on.
 */
@Composable
fun Modifier.pulseBehind(color: Color, enabled: Boolean = true): Modifier {
    if (!enabled || !LocalLively.current) return this
    val loop = rememberInfiniteTransition(label = "pulse")
    val t by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = EaseOutSoft)), label = "pulse")
    return drawBehind {
        val r = size.minDimension / 2
        drawCircle(color.copy(alpha = 0.22f * (1f - t)), radius = r * (1f + 0.55f * t))
    }
}

/** A light that passes over a grey box while its picture is being made. */
@Composable
fun Modifier.shimmer(enabled: Boolean): Modifier {
    if (!enabled || !LocalLively.current) return this
    val loop = rememberInfiniteTransition(label = "shimmer")
    val t by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(1300, easing = LinearEasing)), label = "shimmer")
    return drawBehind {
        val band = size.width * 0.9f
        val x = -band + (size.width + band) * t
        drawRect(CP.Sunk)
        drawRect(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.85f), Color.Transparent),
            start = Offset(x, 0f), end = Offset(x + band, size.height)))
    }
}

/**
 * A sheet of paper, as the opening and the uploads draw it: a rounded page,
 * `width` wide, turned by `turn` degrees around its middle, with three lines
 * of "writing" when it is big enough to show them.
 */
fun DrawScope.sheet(middle: Offset, width: Float, turn: Float, tint: Color, alpha: Float, lines: Boolean = true) {
    if (alpha <= 0f || width <= 0f) return
    val height = width * 1.32f
    rotate(turn, pivot = middle) {
        drawRoundRect(tint.copy(alpha = alpha), topLeft = Offset(middle.x - width / 2, middle.y - height / 2),
            size = Size(width, height), cornerRadius = CornerRadius(width * 0.16f))
        if (lines) {
            val ink = Glow.Night.copy(alpha = alpha * 0.42f)
            for (i in 0..2) {
                val y = middle.y - height * 0.22f + i * height * 0.21f
                drawLine(ink, Offset(middle.x - width * 0.28f, y), Offset(middle.x + width * (if (i == 2) 0.04f else 0.28f), y),
                    strokeWidth = width * 0.085f, cap = StrokeCap.Round)
            }
        }
    }
}
