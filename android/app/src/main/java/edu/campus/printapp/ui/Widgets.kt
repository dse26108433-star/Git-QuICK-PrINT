package edu.campus.printapp.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * The shadow under cards and bars: wide and faint, in the navy of the text, so a card seems to lie a little
 * above the page. (Android 8 and 8.1 cannot tint a shadow: there it is kept very small.)
 */
fun Modifier.softShadow(shape: Shape, elevation: Dp = 10.dp, color: Color = Color(0xFF0B1437)): Modifier =
    if (android.os.Build.VERSION.SDK_INT >= 28)
        shadow(elevation, shape, clip = false, ambientColor = color.copy(alpha = 0.10f), spotColor = color.copy(alpha = 0.22f))
    else shadow(1.dp, shape, clip = false)

/** One button of a segmented choice. note: a second small line in orange (what else it changes). */
data class SegItem(
    val label: String,
    val sub: String? = null,
    val on: Boolean = false,
    val conflict: Boolean = false,
    val note: String? = null,
    val enabled: Boolean = true,
    val onClick: () -> Unit
)

/**
 * Buttons in a row, one chosen (like the website's .seg). perRow: wrap into
 * rows of that many (paper sizes, margins); all in one row otherwise.
 */
@Composable
fun Seg(items: List<SegItem>, perRow: Int = items.size, label: String = "") {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CP.Sunk).padding(4.dp)
            .semantics { if (label.isNotEmpty()) contentDescription = label },
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items.chunked(perRow.coerceAtLeast(1)).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { SegButton(it) }
                repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun RowScope.SegButton(it: SegItem) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier.weight(1f).heightIn(min = 48.dp)
            .then(if (it.on) Modifier.softShadow(shape, 4.dp).background(CP.Surface, shape) else Modifier)
            .clip(shape)
            .clickable(enabled = it.enabled && !it.on, role = Role.RadioButton) { it.onClick() }
            .semantics { selected = it.on; role = Role.RadioButton }
            .alpha(if (it.enabled) 1f else 0.38f)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(it.label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
            color = if (it.on) CP.Brand else if (it.conflict) CP.Faint else CP.Ink2, lineHeight = 17.sp)
        it.sub?.let { sub ->
            Text(sub, fontSize = 11.5.sp, textAlign = TextAlign.Center, lineHeight = 14.sp,
                color = if (it.conflict && it.note == null) CP.Warn else if (it.on) CP.Muted else CP.Faint)
        }
        it.note?.let { note -> Text(note, fontSize = 11.5.sp, textAlign = TextAlign.Center, lineHeight = 14.sp, color = CP.Warn) }
    }
}

@Composable
fun FieldHead(label: String, note: String? = null) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = CP.Ink, modifier = Modifier.weight(1f))
        note?.let { Text(it, fontSize = 13.sp, color = CP.Muted) }
    }
}

@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, fontSize = 13.sp, color = CP.Muted, lineHeight = 18.sp, modifier = modifier.padding(top = 6.dp))
}

@Composable
fun ErrorLine(text: String, modifier: Modifier = Modifier) {
    Row(modifier.padding(top = 6.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Filled.Warning, null, tint = CP.Danger, modifier = Modifier.size(16.dp).padding(top = 2.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 13.5.sp, color = CP.Danger, fontWeight = FontWeight.Medium, lineHeight = 18.sp)
    }
}

/** A setting with its label, separated from the next by a thin line. */
@Composable
fun Field(label: String, note: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
        FieldHead(label, note)
        content()
    }
    Divider()
}

@Composable
fun Divider() = Box(Modifier.fillMaxWidth().height(1.dp).background(CP.Line))

/** A group that opens and closes (Layout, Picture, Finishing...), with a short summary when closed. */
@Composable
fun Group(title: String, summary: String, startOpen: Boolean, content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(startOpen) }
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(if (open) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = CP.Muted)
            Spacer(Modifier.width(6.dp))
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = CP.Ink)
            Spacer(Modifier.weight(1f))
            Text(summary, fontSize = 13.sp, color = CP.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp))
        }
        if (open) Column(Modifier.padding(start = 12.dp), content = content)
        Divider()
    }
}

@Composable
fun Lamp(color: Color, size: Dp = 10.dp) = Box(Modifier.size(size).drawBehind {
    // the lamp, with a faint ring of its own colour around it (as on the Xerox center's screen)
    drawCircle(color.copy(alpha = 0.18f), radius = this.size.minDimension / 2 + 3.dp.toPx())
    drawCircle(color)
})

@Composable
fun Chip(text: String, warn: Boolean = false) {
    Text(
        text, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = if (warn) CP.Warn else CP.Ink2,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (warn) CP.WarnSoft else CP.Sunk)
            .padding(horizontal = 9.dp, vertical = 3.dp)
    )
}

@Composable
fun CardBox(
    modifier: Modifier = Modifier,
    border: Color = CP.Line,
    background: Color = CP.Surface,
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier.fillMaxWidth().softShadow(shape).clip(shape).background(background)
            .border(1.dp, border, shape).padding(padding),
        content = content
    )
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val shape = RoundedCornerShape(16.dp)
    Button(
        onClick = onClick, enabled = enabled,
        modifier = modifier.heightIn(min = 54.dp).then(if (enabled) Modifier.softShadow(shape, 8.dp, CP.Brand) else Modifier),
        shape = shape,
        colors = ButtonDefaults.buttonColors(containerColor = CP.Brand, contentColor = Color.White,
            disabledContainerColor = Color(0xFFD2D8E5), disabledContentColor = Color.White),
        contentPadding = PaddingValues(horizontal = 22.dp)
    ) { Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
}

@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, small: Boolean = false, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = if (small) 40.dp else 48.dp),
        shape = RoundedCornerShape(if (small) 12.dp else 14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (enabled) CP.Line2 else CP.Line),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = CP.Surface, contentColor = CP.Ink,
            disabledContainerColor = CP.Surface2, disabledContentColor = CP.Faint),
        contentPadding = PaddingValues(horizontal = if (small) 12.dp else 18.dp, vertical = 4.dp)
    ) { Text(text, fontSize = if (small) 13.5.sp else 15.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun ProgressBar(fraction: Float, color: Color = CP.Brand, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(4.dp)).background(CP.Sunk)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(color))
    }
}

/** 1. Add & set up · 2. Review & pay (staff: Review & print) · 3. Collect */
@Composable
fun StepBar(now: Int, staff: Boolean = false) {
    val labels = listOf("1. Add & set up", if (staff) "2. Review & print" else "2. Review & pay", "3. Collect")
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { i, label ->
            Column(Modifier.weight(1f)) {
                val bar by animateColorAsState(if (i < now) CP.Accent else if (i == now) CP.Brand else CP.Line,
                    tween(if (LocalLively.current) 420 else 0), label = "step")
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(bar))
                Text(label, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (i < now) CP.Accent else if (i == now) CP.Ink else CP.Faint,
                    fontWeight = if (i == now) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

/**
 * A number box that only takes whole numbers in [min, max]; tells onValue
 * after a short pause, so typing "15" does not first apply "1".
 */
@Composable
fun NumberBox(value: Int, min: Int, max: Int, label: String, onValue: (Int) -> Unit, width: Dp = 88.dp) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    val focus = LocalFocusManager.current
    LaunchedEffect(text) {
        delay(450)
        val v = text.toIntOrNull()
        if (v != null && v in min..max && v != value) onValue(v)
    }
    Surface(shape = RoundedCornerShape(12.dp), color = CP.Surface, border = androidx.compose.foundation.BorderStroke(1.dp, CP.Line2),
        modifier = Modifier.width(width).defaultMinSize(minHeight = 44.dp)) {
        Box(Modifier.padding(horizontal = 10.dp, vertical = 11.dp), contentAlignment = Alignment.Center) {
            BasicTextField(
                value = text,
                onValueChange = { t -> text = t.filter { it.isDigit() }.take(4) },
                singleLine = true,
                textStyle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = CP.Ink, textAlign = TextAlign.Center),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                modifier = Modifier.fillMaxWidth().widthIn(min = 24.dp).semantics { contentDescription = label }
            )
        }
    }
}

/**
 * The bar at the bottom of a page: what it comes to, and the button that goes on. A white sheet with rounded
 * top corners, lying over the page.
 */
@Composable
fun BottomBar(content: @Composable RowScope.() -> Unit) {
    val shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
    Row(
        Modifier.fillMaxWidth().softShadow(shape, 18.dp).clip(shape).background(CP.Surface)
            .navigationBarsPadding().padding(start = 18.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically, content = content
    )
}

/** A dashed outline with rounded corners: "something can be added here". */
fun Modifier.dashedBorder(color: Color, corner: Dp, width: Dp = 1.5.dp): Modifier = drawBehind {
    drawRoundRect(color, cornerRadius = CornerRadius(corner.toPx()),
        style = Stroke(width.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))))
}
