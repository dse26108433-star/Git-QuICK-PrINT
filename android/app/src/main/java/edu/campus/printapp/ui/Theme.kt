package edu.campus.printapp.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** The website's colours: ink navy and paper white; the green "ready" lamp of a copier is the only accent. */
object CP {
    val Bg = Color(0xFFF5F6F3)
    val Surface = Color.White
    val Surface2 = Color(0xFFFAFBF9)
    val Sunk = Color(0xFFEEF0EB)
    val Ink = Color(0xFF0E1A2B)
    val Ink2 = Color(0xFF22324A)
    val Muted = Color(0xFF5B6879)
    val Faint = Color(0xFF8C97A4)
    val Line = Color(0xFFE3E6E0)
    val Line2 = Color(0xFFD2D7CF)
    val Accent = Color(0xFF1F8A55)
    val AccentSoft = Color(0xFFE3F3EA)
    val Warn = Color(0xFFB26B12)
    val WarnSoft = Color(0xFFFBF1E2)
    val Danger = Color(0xFFB4382A)
    val DangerSoft = Color(0xFFFCECE9)
    val Focus = Color(0xFF3B6FF0)
    val Viewer = Color(0xFF29323D)
}

private val colors = lightColorScheme(
    primary = CP.Ink, onPrimary = Color.White,
    secondary = CP.Ink2, onSecondary = Color.White,
    background = CP.Bg, onBackground = CP.Ink,
    surface = CP.Surface, onSurface = CP.Ink,
    surfaceVariant = CP.Sunk, onSurfaceVariant = CP.Muted,
    outline = CP.Line2, outlineVariant = CP.Line,
    error = CP.Danger
)

// No colours in the text styles: text takes the colour of what it is on (white on the ink buttons).
private val type = Typography(
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
)

@Composable
fun CampusTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = colors, typography = type, content = content)
