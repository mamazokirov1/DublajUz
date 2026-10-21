package uz.dublaj.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/** Foydalanuvchi tanlovi: 0 = tizim, 1 = yorug', 2 = qorong'i. */
object ThemeSettings {
    var mode by mutableIntStateOf(0)
    var dynamic by mutableStateOf(false)
}

object Brand {
    val Indigo = Color(0xFF4A2FD0)
    val Violet = Color(0xFF7B4DFF)
    val Teal = Color(0xFF00A6A6)
    val Coral = Color(0xFFFF6B8B)
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF5B3FD8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6DFFF),
    onPrimaryContainer = Color(0xFF1B0A5C),
    secondary = Color(0xFF00898A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCDF4F2),
    onSecondaryContainer = Color(0xFF00302F),
    tertiary = Color(0xFFD6406A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDDE5),
    onTertiaryContainer = Color(0xFF3F0016),
    background = Color(0xFFF7F5FF),
    onBackground = Color(0xFF1C1B22),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1C1B22),
    surfaceVariant = Color(0xFFECE8F8),
    onSurfaceVariant = Color(0xFF494657),
    outline = Color(0xFF7A7689),
    outlineVariant = Color(0xFFCBC6DA),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB9AAFF),
    onPrimary = Color(0xFF2A0F8F),
    primaryContainer = Color(0xFF4328B8),
    onPrimaryContainer = Color(0xFFE6DFFF),
    secondary = Color(0xFF5CE0DC),
    onSecondary = Color(0xFF003736),
    secondaryContainer = Color(0xFF00504F),
    onSecondaryContainer = Color(0xFFCDF4F2),
    tertiary = Color(0xFFFFB0C2),
    onTertiary = Color(0xFF5E1030),
    tertiaryContainer = Color(0xFF7B2A47),
    onTertiaryContainer = Color(0xFFFFDDE5),
    background = Color(0xFF110F18),
    onBackground = Color(0xFFE6E1EC),
    surface = Color(0xFF1B1824),
    onSurface = Color(0xFFE6E1EC),
    surfaceVariant = Color(0xFF2B2838),
    onSurfaceVariant = Color(0xFFC9C4D8),
    outline = Color(0xFF938EA2),
    outlineVariant = Color(0xFF494657),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private val AppTypography = Typography(
    headlineMedium = TextStyle(fontWeight = FontWeight.ExtraBold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.4).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.1.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
)

fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun DublajTheme(content: @Composable () -> Unit) {
    val dark = when (ThemeSettings.mode) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    val context = LocalContext.current
    val scheme = when {
        ThemeSettings.dynamic && Build.VERSION.SDK_INT >= 31 ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = context.findActivity()?.window ?: return@SideEffect
            val c = WindowCompat.getInsetsController(window, view)
            c.isAppearanceLightStatusBars = !dark
            c.isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
}
