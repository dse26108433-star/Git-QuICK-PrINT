package edu.campus.printapp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import edu.campus.printapp.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The opening of XeoGo (the student app), about two seconds, once per start:
 *
 *      0 ms   night blue, the colour the app's window already has while it starts: the logo lands,
 *             a glow opens behind it, far lights come up in the corners
 *    160 ms   a light in the logo's colours goes once around it and closes into a ring
 *    520 ms   the name is set letter by letter; a glint crosses the logo
 *    880 ms   the ring lets go, and a few printed pages fly out from behind the logo and float away
 *    940 ms   "QuICK PrINT" settles under the name, and a light runs over the name
 *   1500 ms   the screen opens from the middle of the logo outwards, and the app is there,
 *             its first page arriving
 *
 * A tap anywhere opens the app at once. Everything is drawn from one clock
 * (`at`, in milliseconds), so each moment of it can be looked at on its own.
 */
object IntroTiming {
    const val TOTAL = 2150
    const val OPEN = 1500
}

/** One printed page that flies out from behind the logo: where to, how big, how it turns, how late, its colour. */
private class Flying(val angle: Float, val reach: Float, val size: Float, val turn: Float, val late: Float, val tint: Color)

// Upwards and to the sides only: the name stands below the logo, and nothing should cross it. `reach` is
// in ring radiuses, chosen so that every page stays on the screen. Most are white paper, three are the
// colours of a colour print.
private val pages = listOf(
    Flying(-166f, 1.00f, 1.00f, -70f, 0f, Color.White),
    Flying(-136f, 1.25f, 0.82f, 60f, 50f, Color(0xFFA8E9FF)),
    Flying(-109f, 1.38f, 1.10f, -50f, 110f, Color.White),
    Flying(-83f, 1.55f, 0.86f, 75f, 20f, Color(0xFFFFD79A)),
    Flying(-57f, 1.36f, 1.06f, -65f, 80f, Color.White),
    Flying(-31f, 1.24f, 0.84f, 55f, 130f, Color(0xFFFFB3D6)),
    Flying(-10f, 0.98f, 0.94f, -60f, 40f, Color.White),
)

/** A far point of light: where (0..1 of the screen), how big (dp), and its own rhythm. */
private class Far(val x: Float, val y: Float, val r: Float, val phase: Float, val pace: Float)

private val lights = List(26) { i ->
    fun any(seed: Float): Float { val v = sin((i + 1) * seed) * 43758.547f; return v - floor(v) }
    Far(any(12.9898f), any(78.233f), 0.7f + 1.1f * any(39.346f), any(11.135f), 0.6f + 0.8f * any(93.989f))
}

/**
 * onOpen: the app behind starts to show (its first page should arrive now).
 * onDone: the opening is over; take it away.
 * at: for looking at one moment of it (tests): the clock stands at this many milliseconds.
 */
@Composable
fun Intro(onOpen: () -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier, at: Float? = null) {
    val opening by rememberUpdatedState(onOpen)
    val finished by rememberUpdatedState(onDone)
    var t by remember { mutableFloatStateOf(at ?: 0f) }
    var skip by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (at != null) return@LaunchedEffect
        // One clock for the whole opening, read once per frame.
        val start = withFrameNanos { it }
        var ahead = 0f                                // a tap jumps the clock forward to the opening
        var opened = false
        while (true) {
            var ms = (withFrameNanos { it } - start) / 1_000_000f + ahead
            if (skip && ms < IntroTiming.OPEN) {
                ahead += IntroTiming.OPEN - ms
                ms = IntroTiming.OPEN.toFloat()
            }
            t = ms.coerceAtMost(IntroTiming.TOTAL.toFloat())
            if (!opened && ms >= IntroTiming.OPEN) {
                opened = true
                opening()
            }
            if (ms >= IntroTiming.TOTAL) break
        }
        finished()
    }

    // ---- where everything is at this moment
    val open = IntroTiming.OPEN.toFloat()
    val ground = stretch(t, 0f, 340f)                                   // the flat start colour becomes the night sky
    val land = stretch(t, 0f, 560f, EaseOutBack)                        // the logo arrives
    val logoIn = stretch(t, 0f, 200f)
    val glow = stretch(t, 0f, 760f, EaseOutSoft)
    val ring = stretch(t, 160f, 880f, EaseInOutSoft)                    // the light goes round
    val ringGo = stretch(t, 880f, 1300f, EaseOutSoft)                   // ...and the ring lets go
    val glint = stretch(t, 560f, 1080f, EaseInOutSoft)                  // a glint over the logo
    val tag = stretch(t, 940f, 1340f, EaseOutSoft)
    val sheen = stretch(t, 1040f, 1460f, EaseInOutSoft)                 // a light over the name
    val credit = stretch(t, 1060f, 1400f)
    val leave = stretch(t, open, 1820f, EaseInOutSoft)                  // logo and words step back
    val hole = stretch(t, open, IntroTiming.TOTAL.toFloat(), EaseInOutSoft)   // the screen opens

    BoxWithConstraints(
        modifier.fillMaxSize()
            .semantics { contentDescription = "XeoGo is opening" }
            .testTag("intro")
            .pointerInput(Unit) {
                detectTapGestures { skip = true }      // a tap: straight to the opening (and nothing behind is touched)
            }
    ) {
        val density = LocalDensity.current
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        // Drawn for a phone held upright; smaller where there is less room (a phone on its side), a little
        // bigger on a tablet. The name is part of the picture, so it does not follow the phone's text size.
        val k = min(maxWidth.value / 360f, maxHeight.value / 660f).coerceIn(0.5f, 1.3f)
        val tall = maxHeight >= 480.dp
        val logoDp = 128.dp * k
        val logoPx = with(density) { logoDp.toPx() }
        val ringR = logoPx * 0.72f
        // the logo, the name and the line under it stand together a little above the middle of the screen
        val middle = Offset(w / 2, h * 0.46f - with(density) { 62.dp.toPx() } * k)
        val nameSize = with(density) { (46.dp * k).toSp() }
        val tagSize = with(density) { (14.dp * k).toSp() }

        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    val still = 1f - leave
                    // the ground: it starts as the one colour the window had, then the night blue of the logo
                    // spreads over it, a little lighter where the logo sits, with two far lights in the corners
                    drawRect(Glow.Navy)
                    drawRect(Brush.verticalGradient(listOf(Glow.Night, Glow.Navy, Glow.Deep)), alpha = ground)
                    Offset(w * 0.04f, h * 0.06f).let { c ->
                        drawCircle(Brush.radialGradient(listOf(Glow.Cyan.copy(alpha = 0.15f * ground), Glow.Cyan.copy(alpha = 0f)), c, w * 0.95f),
                            radius = w * 0.95f, center = c)
                    }
                    Offset(w * 1.02f, h * 0.96f).let { c ->
                        drawCircle(Brush.radialGradient(listOf(Glow.Pink.copy(alpha = 0.13f * ground), Glow.Pink.copy(alpha = 0f)), c, w * 1.0f),
                            radius = w * 1.0f, center = c)
                    }
                    drawCircle(Brush.radialGradient(listOf(Glow.Blue.copy(alpha = 0.50f * glow * still), Glow.Blue.copy(alpha = 0f)),
                        middle, logoPx * 2.2f), radius = logoPx * 2.2f, center = middle)

                    // far points of light, each with its own slow rhythm (none where the logo and the name stand)
                    val lit = stretch(t, 120f, 700f) * still
                    if (lit > 0f) for (s in lights) {
                        val x = s.x * w
                        val y = s.y * h
                        if (abs(x - middle.x) < logoPx * 1.15f && y > middle.y - logoPx * 1.0f && y < middle.y + logoPx * 1.9f) continue
                        val beat = 0.5f + 0.5f * sin((t / 1000f * s.pace + s.phase) * 2f * PI.toFloat())
                        drawCircle(Glow.Ice.copy(alpha = (0.07f + 0.38f * beat) * lit), radius = s.r.dp.toPx() * k, center = Offset(x, y))
                    }

                    // printed pages fly out from behind the logo, turning, and float away
                    for (p in pages) {
                        // each one is thrown out fast, then hangs in the air, sinks a little and is gone
                        val life = stretch(t, 850f + p.late, 1500f + p.late * 0.35f)
                        if (life <= 0f || life >= 1f) continue
                        val go = EaseOutSoft.transform(life)
                        val a = p.angle * PI.toFloat() / 180f
                        val far = ringR * (0.5f + p.reach * go)
                        val place = Offset(middle.x + cos(a) * far, middle.y + sin(a) * far + ringR * 0.30f * life * life)
                        val seen = min(life / 0.10f, 1f) * (1f - stretch(life, 0.45f, 1f)) * still
                        sheet(place, 18.dp.toPx() * k * p.size * (0.6f + 0.4f * min(life / 0.18f, 1f)),
                            p.angle + 90f + p.turn * go + p.turn * 0.3f * life, p.tint, 0.88f * seen)
                    }

                    // the ring: a light goes once around the logo, leaving the logo's colours behind it
                    if (ring > 0f && ringGo < 1f) {
                        val stroke = 3.6.dp.toPx() * k
                        val r = ringR * (1f + 0.13f * ringGo)
                        val corner = Offset(middle.x - r, middle.y - r)
                        val from = -90f + 100f * ring                  // it also travels while it grows: one sweep
                        rotate(from, pivot = middle) {
                            drawArc(Brush.sweepGradient(Glow.sweep, middle), 0f, 360f * ring, false, topLeft = corner, size = Size(r * 2, r * 2),
                                style = Stroke(stroke, cap = StrokeCap.Round), alpha = 1f - ringGo)
                        }
                        val head = 1f - stretch(ring, 0.84f, 1f)
                        if (head > 0f) {
                            val a = (from + 360f * ring) * PI.toFloat() / 180f
                            val c = Offset(middle.x + cos(a) * r, middle.y + sin(a) * r)
                            drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.95f * head), Glow.Cyan.copy(alpha = 0.35f * head),
                                Glow.Cyan.copy(alpha = 0f)), c, stroke * 3.4f), radius = stroke * 3.4f, center = c)
                        }
                        // as it lets go, a second, thinner ring runs ahead of it and is gone
                        if (ringGo > 0f) {
                            val r2 = ringR * (1f + 0.42f * ringGo)
                            drawCircle(Glow.Ice.copy(alpha = 0.34f * (1f - ringGo)), radius = r2, center = middle, style = Stroke(stroke * 0.42f))
                        }
                    }

                    drawContent()

                    // the opening: a hole that grows from the middle of the logo until nothing of this screen is left,
                    // its edge glowing a little
                    if (hole > 0f) {
                        val far = hypot(maxOf(middle.x, w - middle.x), maxOf(middle.y, h - middle.y))
                        val r = far * hole * 1.04f
                        val rim = 34.dp.toPx()
                        val clear = Glow.Cyan.copy(alpha = 0f)
                        drawCircle(Brush.radialGradient(0f to clear, (r / (r + rim)).coerceIn(0.001f, 0.999f) to Glow.Cyan.copy(alpha = 0.62f * (1f - hole)),
                            1f to clear, center = middle, radius = r + rim), radius = r + rim, center = middle)
                        drawCircle(Color.Black, radius = r, center = middle, blendMode = BlendMode.Clear)
                    }
                }
        ) {
            // ---- the logo
            Box(
                Modifier.offset { IntOffset((middle.x - logoPx / 2).roundToInt(), (middle.y - logoPx / 2).roundToInt()) }
                    .size(logoDp)
                    .graphicsLayer {
                        val s = (0.5f + 0.5f * land) * (1f + 0.16f * leave)
                        scaleX = s; scaleY = s
                        alpha = logoIn * (1f - leave)
                    }
                    .clip(RoundedCornerShape(logoDp * 0.235f))
            ) {
                Image(painterResource(R.drawable.logo), contentDescription = null, modifier = Modifier.fillMaxSize())
                // a glint crosses it once, from the top left
                Box(Modifier.fillMaxSize().drawBehind {
                    if (glint <= 0f || glint >= 1f) return@drawBehind
                    val band = size.width * 0.30f
                    val x = -band * 1.6f + (size.width + band * 2.2f) * glint
                    drawRect(Brush.linearGradient(listOf(Color.White.copy(alpha = 0f), Color.White.copy(alpha = 0.36f), Color.White.copy(alpha = 0f)),
                        start = Offset(x, 0f), end = Offset(x + band, band * 0.55f)))
                })
            }
            // ---- the name, letter by letter, and the line under it
            Column(
                Modifier.fillMaxWidth()
                    .offset { IntOffset(0, (middle.y + ringR + 20.dp.toPx() * k).roundToInt()) }
                    .graphicsLayer {
                        alpha = 1f - leave
                        translationY = leave * 16.dp.toPx()
                    },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
                        drawContent()
                        // a light runs over the letters once (it only touches the letters themselves)
                        if (sheen > 0f && sheen < 1f) {
                            val band = size.width * 0.34f
                            val x = -band + (size.width + band * 2f) * sheen
                            drawRect(Brush.linearGradient(listOf(Glow.Cyan.copy(alpha = 0f), Glow.Cyan.copy(alpha = 0.95f), Color.White,
                                Color.White.copy(alpha = 0f)), start = Offset(x - band, 0f), end = Offset(x, size.height * 0.5f)),
                                blendMode = BlendMode.SrcAtop)
                        }
                    }.padding(bottom = 30.dp * k),              // room below: the letters rise from there
                    verticalAlignment = Alignment.Bottom
                ) {
                    "XeoGo".forEachIndexed { i, ch ->
                        val seen = stretch(t, 520f + i * 68f, 760f + i * 68f)
                        val up = stretch(t, 520f + i * 68f, 940f + i * 68f, EaseOutBack)
                        Text(ch.toString(), fontSize = nameSize, fontWeight = FontWeight.Bold, color = if (i < 3) Color.White else Glow.Cyan,
                            modifier = Modifier.graphicsLayer {
                                alpha = seen
                                translationY = (1f - up) * 26.dp.toPx() * k
                            })
                    }
                }
                Text("QuICK PrINT", fontSize = tagSize, fontWeight = FontWeight.SemiBold, color = Glow.Ice,
                    letterSpacing = tagSize * (0.36f + 0.36f * (1f - tag)),
                    modifier = Modifier.offset(y = -(24.dp * k)).graphicsLayer { alpha = tag * 0.92f })
            }
            if (tall) {
                Text("by Vedant Pravin Surve", fontSize = with(density) { 12.dp.toSp() }, color = Color.White.copy(alpha = 0.5f),
                    letterSpacing = with(density) { 0.6.dp.toSp() },
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 26.dp)
                        .graphicsLayer { alpha = credit * (1f - leave) })
            }
        }
    }
}
