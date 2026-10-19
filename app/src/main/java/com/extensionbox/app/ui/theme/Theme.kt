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

private val DefaultLightColorScheme = lightColorScheme(
    primary = Color(0xFF3F51B5),
    onPrimary = Color.White,
    secondary = Color(0xFF455A64),
    onSecondary = Color.White,
    tertiary = Color(0xFF00695C),
    background = Color.White,
    surface = Color.White,
    surfaceVariant = Color(0xFFF3F3F3),
    onBackground = Color(0xFF171717),
    onSurface = Color(0xFF171717),
    onSurfaceVariant = Color(0xFF4A4A4A)
)

// Custom Schemes
private val CyberpunkColorScheme = darkColorScheme(
    primary = Color(0xFFFF2DAA),
    onPrimary = Color(0xFF2B001C),
    secondary = Color(0xFF00E5FF),
    onSecondary = Color(0xFF001F24),
    tertiary = Color(0xFFFFE600),
    onTertiary = Color(0xFF211E00),
    surface = Color(0xFF20113D),
    background = Color(0xFF10061F),
    onSurface = Color(0xFFF7EFFF),
    onBackground = Color(0xFFF7EFFF),
    surfaceVariant = Color(0xFF321B58),
    outline = Color(0xFFB66DFF)
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

private val MatrixColorScheme = darkColorScheme(
    primary = Color(0xFF00FF66),
    onPrimary = Color(0xFF003918),
    secondary = Color(0xFFB7FF00),
    onSecondary = Color(0xFF1C2800),
    tertiary = Color(0xFF00D9FF),
    onTertiary = Color(0xFF003640),
    surface = Color(0xFF092218),
    background = Color(0xFF020B07),
    onSurface = Color(0xFFD8FFE5),
    onBackground = Color(0xFFD8FFE5),
    surfaceVariant = Color(0xFF103D29),
    outline = Color(0xFF38D980)
)

private val AmoledOverrides = darkColorScheme(
    tertiary = Color(0xFFCF6679),
    surface = Color(0xFF000000),
    background = Color(0xFF000000),
    onSurface = Color(0xFFE1E1E1),
    onBackground = Color(0xFFE1E1E1),
    surfaceVariant = Color(0xFF121212)
)

private val SunsetColorScheme = darkColorScheme(
    primary = Color(0xFFFF7657),
    onPrimary = Color(0xFF3D0900),
    secondary = Color(0xFFFFB000),
    onSecondary = Color(0xFF382300),
    tertiary = Color(0xFFFF4D9D),
    onTertiary = Color(0xFF41001E),
    surface = Color(0xFF3A1725),
    background = Color(0xFF1D0A16),
    onSurface = Color(0xFFFFEAE3),
    onBackground = Color(0xFFFFEAE3),
    surfaceVariant = Color(0xFF562238),
    outline = Color(0xFFFF9A83)
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
    val initialTheme = remember { Prefs.getInt(context, "app_theme", ThemeHelper.DEFAULT) }
    val themeIndex by Prefs.getIntFlow(context, "app_theme", ThemeHelper.DEFAULT).collectAsState(initial = initialTheme)
    val isDynamic by Prefs.getBoolFlow(context, "dynamic_color", true).collectAsState(initial = true)

    val colorScheme = when (themeIndex) {
        ThemeHelper.DEFAULT -> {
            if (darkTheme) {
                val base = if (isDynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    dynamicDarkColorScheme(context)
                } else {
                    DarkColorScheme
                }
                base.copy(
                    surface = AmoledOverrides.surface,
                    background = AmoledOverrides.background,
                    surfaceVariant = AmoledOverrides.surfaceVariant,
                    onSurface = AmoledOverrides.onSurface,
                    onBackground = AmoledOverrides.onBackground,
                    tertiary = AmoledOverrides.tertiary
                )
            } else {
                DefaultLightColorScheme
            }
        }
        ThemeHelper.MONET -> {
            if (isDynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            } else {
                if (darkTheme) DarkColorScheme else LightColorScheme
            }
        }
        ThemeHelper.GRUVBOX -> CyberpunkColorScheme
        ThemeHelper.CATPPUCCIN -> CatppuccinColorScheme
        ThemeHelper.NORD -> MatrixColorScheme
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
        ThemeHelper.SOLARIZED -> SunsetColorScheme
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
