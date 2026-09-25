package io.github.jsys12.bastion.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import io.github.jsys12.bastion.data.Settings

val Brand = Color(0xFF2E6BFF)
val ShieldGreen = Color(0xFF1FA971)

private val LightColors = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE6FF),
    onPrimaryContainer = Color(0xFF001A4D),
    secondary = Color(0xFF4F5B79),
    secondaryContainer = Color(0xFFE2E7F7),
    tertiary = ShieldGreen,
    background = Color(0xFFFAFBFF),
    surface = Color(0xFFFAFBFF),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF4F6FC),
    surfaceContainer = Color(0xFFEEF1F9),
    surfaceContainerHigh = Color(0xFFE8ECF6),
    surfaceContainerHighest = Color(0xFFE2E6F1),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9DB8FF),
    onPrimary = Color(0xFF002A78),
    primaryContainer = Color(0xFF1C3F99),
    onPrimaryContainer = Color(0xFFDCE6FF),
    secondary = Color(0xFFBAC4E0),
    secondaryContainer = Color(0xFF394461),
    tertiary = Color(0xFF63D9A4),
    background = Color(0xFF101318),
    surface = Color(0xFF101318),
    surfaceContainerLowest = Color(0xFF0B0E12),
    surfaceContainerLow = Color(0xFF171A20),
    surfaceContainer = Color(0xFF1B1F26),
    surfaceContainerHigh = Color(0xFF252A32),
    surfaceContainerHighest = Color(0xFF30353E),
)

@Composable
fun BastionTheme(settings: Settings, content: @Composable () -> Unit) {
    val mode by settings.theme.flow.collectAsState()
    val dark = when (mode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark -> dynamicDarkColorScheme(context).copy(tertiary = DarkColors.tertiary)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context).copy(tertiary = LightColors.tertiary)
        dark -> DarkColors
        else -> LightColors
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as android.app.Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(colorScheme = colors, content = content)
}
