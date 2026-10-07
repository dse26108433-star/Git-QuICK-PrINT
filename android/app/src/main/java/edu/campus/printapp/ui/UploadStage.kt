package edu.campus.printapp.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import edu.campus.printapp.core.plural
import edu.campus.printapp.flow.DocStatus
import edu.campus.printapp.flow.OrderSession
import edu.campus.printapp.flow.SessionState
import kotlin.math.sin

/**
 * The top of "Your files" in the student app while files are on their way.
 *
 * Sending takes a while (a big PDF on mobile data), so this is where the app
 * shows that it is alive: a card in the logo's night blue with a ring that
 * fills in its colours, the per cent counting up, what is happening in words
 * ("Reading your files", "Sending your files", "Checking your files", and
 * for a Word file "Turning it into pages" while the Xerox center's computer
 * does that), which file it is at, and a bar with a light moving along it. Sheets of paper
 * drift up behind, very faintly. While the files are still being read (no per
 * cent yet) a light goes round the ring, around a small page.
 *
 * When every file is ready the card turns into a calm white one: a green
 * tick that draws itself, a small burst of the logo's colours around it,
 * "All 3 files ready".
 *
 * The numbers are the same as in the plain version (WorkspaceScreen's
 * QueueProgress, which the staff app shows): only how they are shown differs.
 */
@Composable
fun UploadStage(st: SessionState, session: OrderSession) {
    val docs = st.docs
    val working = docs.filter { it.busy }
    val bad = docs.count { it.status == DocStatus.ERROR || it.status == DocStatus.CANCELLED }
    val ready = docs.count { it.status == DocStatus.READY }
    var total = 0L
    var sent = 0.0
    for (d in docs) {
        if (d.status == DocStatus.ERROR || d.status == DocStatus.CANCELLED) continue
        val size = d.size.coerceAtLeast(1)
        total += size
        sent += when (d.status) {
            DocStatus.UPLOADING -> size * d.progress.toDouble()
            DocStatus.CHECKING, DocStatus.CONVERTING, DocStatus.READY -> size.toDouble()
            else -> 0.0
        }
    }
    val pct = if (total > 0) (sent / total * 100).toInt().coerceIn(0, 100) else 0
    val many = docs.size > 1

    // What the card says now. When the last file is through there is nothing to say any more, but the card
    // is still on screen for a moment while it leaves: it keeps what it said last, with its ring closed.
    val live = if (working.isEmpty()) null else {
        val sending = working.any { it.status == DocStatus.UPLOADING }
        val checking = working.any { it.status == DocStatus.CHECKING }
        // a Word file with the Xerox center's computer, which turns it into pages
        val turning = working.filter { it.status == DocStatus.CONVERTING }
        val now = working.firstOrNull { it.status == DocStatus.UPLOADING } ?: working.firstOrNull { it.status == DocStatus.CHECKING }
            ?: working.first()
        Sending(
            headline = when {
                sending -> if (many) "Sending your files" else "Sending your file"
                checking -> if (many) "Checking your files" else "Checking your file"
                turning.isNotEmpty() -> if (turning.size > 1) "Turning Word files into pages" else "Turning it into pages"
                working.any { it.status == DocStatus.READING } -> if (many) "Reading your files" else "Reading your file"
                else -> "Getting ready"
            },
            line = (if (many) "${(ready + 1).coerceAtMost(docs.size)} of ${docs.size} · " else "") + now.name,
            pct = pct,
            known = sending || checking || turning.isNotEmpty() || sent > 0
        )
    }
    val kept = remember { arrayOfNulls<Sending>(1) }
    val worked = remember { booleanArrayOf(false) }       // this card has shown files on their way (so "ready" is news)
    if (live != null) {
        kept[0] = live
        worked[0] = true
    }

    AnimatedContent(
        targetState = live != null,
        transitionSpec = {
            (fadeIn(tween(320, delayMillis = 90)) + scaleIn(tween(380, delayMillis = 90, easing = EaseOutSoft), initialScale = 0.96f))
                .togetherWith(fadeOut(tween(160))) using SizeTransform(clip = false)
        },
        modifier = Modifier.padding(bottom = 10.dp).testTag("queueTop"),
        label = "uploadStage"
    ) { busy ->
        if (busy) {
            val s = live ?: kept[0]?.let { Sending(it.headline, it.line, 100, true) } ?: return@AnimatedContent
            val shownPct by animateIntAsState(s.pct, tween(420, easing = EaseOutSoft), label = "pct")
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
                    .background(Brush.linearGradient(listOf(Glow.Night, Glow.Navy, Glow.Royal)))
                    .paperDrift()
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProgressRing(if (s.known) s.pct / 100f else null, done = false, size = 72.dp, stroke = 6.dp) {
                        Crossfade(s.known, animationSpec = tween(240), label = "ringMiddle") { known ->
                            if (known) Text("$shownPct%", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            else Canvas(Modifier.size(30.dp)) {                 // a page: the file being read
                                sheet(center, 19.dp.toPx(), 0f, Color.White, 0.94f)
                            }
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(s.headline, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            Spacer(Modifier.width(6.dp))
                            Dots(color = Glow.Ice)
                        }
                        Text(s.line, color = Glow.Ice.copy(alpha = 0.9f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp))
                        Spacer(Modifier.size(10.dp))
                        FlowBar(if (s.known) s.pct / 100f else null, color = Glow.Cyan, track = Color.White.copy(alpha = 0.16f),
                            colors = listOf(Glow.Blue, Glow.Cyan))
                        Text("Cancel all", color = Color.White.copy(alpha = 0.72f), fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(8.dp)).clickable { session.cancelAll() }
                                .padding(horizontal = 2.dp, vertical = 4.dp).testTag("cancelAll"))
                    }
                }
            }
        } else {
            // Every file is through. If this card was showing them on their way, that is news: the tick draws
            // itself, once, with a small burst around it. If it only comes back on screen, it is simply there.
            val news = worked[0]
            var play by remember { mutableStateOf(!news) }
            LaunchedEffect(Unit) { play = true }
            val allReady = docs.isNotEmpty() && ready == docs.size
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(CP.Surface)
                    .border(1.dp, if (allReady) CP.Accent.copy(alpha = 0.35f) else CP.Line, RoundedCornerShape(20.dp)).padding(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (allReady) {
                        CheckPop(play, Modifier.sparks(play && news), size = 26.dp)
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(if (allReady) (if (docs.size == 1) "Your file is ready" else "All ${docs.size} files ready")
                        else "$ready of ${docs.size} files ready", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = CP.Ink,
                        modifier = Modifier.weight(1f))
                    if (bad > 0) Text(plural(bad, "needs", "need") + " attention", fontSize = 13.sp, color = CP.Muted)
                }
                Spacer(Modifier.size(10.dp))
                ProgressBar(ready / docs.size.toFloat().coerceAtLeast(1f), CP.Accent)
            }
        }
    }
}

/** What the night-blue card says: the words, the per cent, and whether a per cent is known yet. */
private class Sending(val headline: String, val line: String, val pct: Int, val known: Boolean)

/** Sheets of paper drifting up behind the card, very faintly: the files on their way. */
@Composable
private fun Modifier.paperDrift(): Modifier {
    val loop = rememberInfiniteTransition(label = "paper")
    val t by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(7000, easing = LinearEasing)), label = "paper")
    return drawBehind {
        val wide = size.height * 0.17f
        for (i in 0 until 6) {
            // each sheet has its own lane, its own start, and comes round again
            val phase = (t + i * 0.37f) % 1f
            val x = size.width * (0.30f + 0.12f * i) + size.height * 0.10f * sin((phase + i) * 6.283f)
            val y = size.height * (1.25f - 1.5f * phase)
            val seen = (if (phase < 0.2f) phase / 0.2f else if (phase > 0.75f) (1f - phase) / 0.25f else 1f) * 0.10f
            sheet(Offset(x, y), wide, -14f + 28f * ((i * 0.31f + phase) % 1f), Color.White, seen, lines = false)
        }
    }
}
