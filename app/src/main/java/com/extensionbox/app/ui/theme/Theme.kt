package com.extensionbox.app.ui.theme

import android.app.Activity
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
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

import androidx.compose.ui.graphics.Color
import com.extensionbox.app.ThemeHelper
import com.extensionbox.app.Prefs

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40
)

// Custom Schemes
private val GruvboxColorScheme = darkColorScheme(
    primary = Color(0xFFFABD2F),
    secondary = Color(0xFF83A598),
    tertiary = Color(0xFF8EC07C),
    surface = Color(0xFF282828),
    background = Color(0xFF1D2021),
    onSurface = Color(0xFFEBDBB2),
    onBackground = Color(0xFFEBDBB2),
    surfaceVariant = Color(0xFF3C3836)
)

private val CatppuccinColorScheme = darkColorScheme(
    primary = Color(0xFFCBA6F7),
    secondary = Color(0xFF89B4FA),
    tertiary = Color(0xFF94E2D5),
    surface = Color(0xFF1E1E2E),
    background = Color(0xFF11111B),
    onSurface = Color(0xFFCDD6F4),
    onBackground = Color(0xFFCDD6F4),
    surfaceVariant = Color(0xFF313244)
)

private val NordColorScheme = darkColorScheme(
    primary = Color(0xFF88C0D0),
    secondary = Color(0xFF81A1C1),
    tertiary = Color(0xFF8FBCBB),
    surface = Color(0xFF3B4252),
    background = Color(0xFF2E3440),
    onSurface = Color(0xFFECEFF4),
    onBackground = Color(0xFFECEFF4),
    surfaceVariant = Color(0xFF4C566A)
)

private val AmoledOverrides = darkColorScheme(
    tertiary = Color(0xFFCF6679),
    surface = Color(0xFF000000),
    background = Color(0xFF000000),
    onSurface = Color(0xFFE1E1E1),
    onBackground = Color(0xFFE1E1E1),
    surfaceVariant = Color(0xFF121212)
)

private val SolarizedColorScheme = darkColorScheme(
    primary = Color(0xFF268BD2),
    secondary = Color(0xFF2AA198),
    tertiary = Color(0xFF859900),
    surface = Color(0xFF073642),
    background = Color(0xFF002B36),
    onSurface = Color(0xFF93A1A1),
    onBackground = Color(0xFF93A1A1),
    surfaceVariant = Color(0xFF586E75)
)

private val DraculaColorScheme = darkColorScheme(
    primary = Color(0xFFBD93F9),
    secondary = Color(0xFF8BE9FD),
    tertiary = Color(0xFF50FA7B),
    surface = Color(0xFF282A36),
    background = Color(0xFF21222C),
    onSurface = Color(0xFFF8F8F2),
    onBackground = Color(0xFFF8F8F2),
    surfaceVariant = Color(0xFF44475A)
)

@Composable
fun ExtensionBoxTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val initialTheme = remember { Prefs.getInt(context, "app_theme", ThemeHelper.MONET) }
    val themeIndex by Prefs.getIntFlow(context, "app_theme", ThemeHelper.MONET).collectAsState(initial = initialTheme)
    val isDynamic by Prefs.getBoolFlow(context, "dynamic_color", true).collectAsState(initial = true)

    val colorScheme = when (themeIndex) {
        ThemeHelper.MONET -> {
            if (isDynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            } else {
                if (darkTheme) DarkColorScheme else LightColorScheme
            }
        }
        ThemeHelper.GRUVBOX -> GruvboxColorScheme
        ThemeHelper.CATPPUCCIN -> CatppuccinColorScheme
        ThemeHelper.NORD -> NordColorScheme
        ThemeHelper.AMOLED -> {
            val base = if (isDynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                dynamicDarkColorScheme(context)
            } else {
                DarkColorScheme
            }
            base.copy(
                primary = base.primary,
                secondary = base.secondary,
                surface = AmoledOverrides.surface,
                background = AmoledOverrides.background,
                surfaceVariant = AmoledOverrides.surfaceVariant,
                onSurface = AmoledOverrides.onSurface,
                onBackground = AmoledOverrides.onBackground,
                tertiary = AmoledOverrides.tertiary
            )
        }
        ThemeHelper.SOLARIZED -> SolarizedColorScheme
        ThemeHelper.DRACULA -> DraculaColorScheme
        else -> if (darkTheme) DarkColorScheme else LightColorScheme
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
