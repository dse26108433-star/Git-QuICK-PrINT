package edu.campus.printapp.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * XeoGo's colours. The blue of the logo is the colour of everything that can
 * be pressed to go on (Brand); text is a deep navy (Ink) on a cool, very light
 * ground; green, amber and red are kept for what they mean on a copier:
 * ready, working, attention.
 */
object CP {
    val Bg = Color(0xFFF3F5FA)
    val Surface = Color.White
    val Surface2 = Color(0xFFF8FAFD)
    val Sunk = Color(0xFFEDF0F7)
    val Ink = Color(0xFF0B1437)
    val Ink2 = Color(0xFF263154)
    val Muted = Color(0xFF5E6A85)
    val Faint = Color(0xFF94A0B8)
    val Line = Color(0xFFE2E7F0)
    val Line2 = Color(0xFFCCD4E2)
    /** The logo's blue: buttons, what is chosen, links. */
    val Brand = Color(0xFF2B59F3)
    val BrandDeep = Color(0xFF1B3FC9)
    val BrandSoft = Color(0xFFE8EEFF)
    val Accent = Color(0xFF16995B)
    val AccentSoft = Color(0xFFE1F5EA)
    val Warn = Color(0xFFB4690E)
    val WarnSoft = Color(0xFFFDF2E0)
    val Danger = Color(0xFFC4372C)
    val DangerSoft = Color(0xFFFDECEA)
    val Focus = Color(0xFF2B59F3)
    val Viewer = Color(0xFF1A2342)

    /** The night blue of the logo, for the few dark panels (the first page, your prints, paying). */
    val Night: Brush get() = Brush.linearGradient(listOf(Color(0xFF040D3A), Color(0xFF0A1F78), Color(0xFF1437B8)))
}

private val colors = lightColorScheme(
    primary = CP.Brand, onPrimary = Color.White,
    primaryContainer = CP.BrandSoft, onPrimaryContainer = CP.Ink,
    secondary = CP.Ink2, onSecondary = Color.White,
    background = CP.Bg, onBackground = CP.Ink,
    surface = CP.Surface, onSurface = CP.Ink,
    surfaceVariant = CP.Sunk, onSurfaceVariant = CP.Muted,
    surfaceContainerHigh = CP.Surface, surfaceContainer = CP.Surface, surfaceContainerHighest = CP.Sunk,
    outline = CP.Line2, outlineVariant = CP.Line,
    error = CP.Danger
)

// No colours in the text styles: text takes the colour of what it is on (white on the buttons).
private val type = Typography(
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
)

@Composable
fun CampusTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = colors, typography = type, content = content)
