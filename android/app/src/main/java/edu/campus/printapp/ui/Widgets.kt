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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
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
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CP.Sunk).padding(4.dp)
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
    val shape = RoundedCornerShape(9.dp)
    Column(
        Modifier.weight(1f).heightIn(min = 48.dp)
            .then(if (it.on) Modifier.shadow(2.dp, shape).background(CP.Surface, shape) else Modifier)
            .clip(shape)
            .clickable(enabled = it.enabled && !it.on, role = Role.RadioButton) { it.onClick() }
            .semantics { selected = it.on; role = Role.RadioButton }
            .alpha(if (it.enabled) 1f else 0.38f)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(it.label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
            color = if (it.on) CP.Ink else if (it.conflict) CP.Faint else CP.Muted, lineHeight = 17.sp)
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
        Text(label, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = CP.Ink2, modifier = Modifier.weight(1f))
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
            Icon(if (open) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = CP.Ink2)
            Spacer(Modifier.width(6.dp))
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = CP.Ink2)
            Spacer(Modifier.weight(1f))
            Text(summary, fontSize = 13.sp, color = CP.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp))
        }
        if (open) Column(Modifier.padding(start = 12.dp), content = content)
        Divider()
    }
}

@Composable
fun Lamp(color: Color, size: Dp = 10.dp) = Box(Modifier.size(size).clip(CircleShape).background(color))

@Composable
fun Chip(text: String, warn: Boolean = false) {
    Text(
        text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (warn) CP.Warn else CP.Muted,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(if (warn) CP.WarnSoft else CP.Sunk)
            .padding(horizontal = 8.dp, vertical = 3.dp)
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
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(background)
            .border(1.dp, border, RoundedCornerShape(16.dp)).padding(padding),
        content = content
    )
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 52.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = CP.Ink, disabledContainerColor = CP.Line2,
            disabledContentColor = Color.White),
        contentPadding = PaddingValues(horizontal = 22.dp)
    ) { Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
}

@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, small: Boolean = false, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = if (small) 40.dp else 48.dp),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, CP.Line2),
        contentPadding = PaddingValues(horizontal = if (small) 12.dp else 18.dp, vertical = 4.dp)
    ) { Text(text, fontSize = if (small) 13.5.sp else 15.sp, fontWeight = FontWeight.SemiBold, color = CP.Ink) }
}

@Composable
fun ProgressBar(fraction: Float, color: Color = CP.Ink, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(CP.Line)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(color))
    }
}

/** 1. Add & set up · 2. Review & pay (staff: Review & print) · 3. Collect */
@Composable
fun StepBar(now: Int, staff: Boolean = false) {
    val labels = listOf("1. Add & set up", if (staff) "2. Review & print" else "2. Review & pay", "3. Collect")
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { i, label ->
            Column(Modifier.weight(1f)) {
                val bar by animateColorAsState(if (i < now) CP.Accent else if (i == now) CP.Ink else CP.Line2,
                    tween(if (LocalLively.current) 420 else 0), label = "step")
                Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(bar))
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
    Surface(shape = RoundedCornerShape(10.dp), color = CP.Surface, border = androidx.compose.foundation.BorderStroke(1.dp, CP.Line2),
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
