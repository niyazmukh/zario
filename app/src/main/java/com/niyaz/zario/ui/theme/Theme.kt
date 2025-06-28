package com.niyaz.zario.ui.theme


import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color // <<< Ensure this import is present
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat


// Define the Light Color Scheme using the colors from Color.kt
private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40,
    background = Color(0xFFFFFBFE), // Standard light background
    surface = Color(0xFFFFFBFE), // Standard light surface
    onPrimary = Color.White, // Text/icons on Primary color background
    onSecondary = Color.White, // Text/icons on Secondary color background
    onTertiary = Color.White, // Text/icons on Tertiary color background
    onBackground = Color(0xFF1C1B1F), // Text/icons on main Background color
    onSurface = Color(0xFF1C1B1F), // Text/icons on Surface color (like Cards)
    error = ErrorRed, // Added standard error color
    onError = Color.White // Text/icons on Error color background
    /* Other default colors to override... */
)


// Define the Dark Color Scheme using the colors from Color.kt
private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80,
    background = Color(0xFF1C1B1F), // Standard dark background
    surface = Color(0xFF1C1B1F), // Standard dark surface
    onPrimary = Purple40, // Text/icons on Primary color background (CORRECTED - Use a contrast color)
    onSecondary = PurpleGrey40, // Text/icons on Secondary color background (CORRECTED - Use a contrast color)
    onTertiary = Pink40, // Text/icons on Tertiary color background (CORRECTED - Use a contrast color)
    onBackground = Color(0xFFE6E1E5), // Text/icons on main Background color
    onSurface = Color(0xFFE6E1E5), // Text/icons on Surface color
    error = ErrorRed, // Added standard error color
    onError = Color(0xFF690005) // Text/icons on Error color background (darker theme usually needs contrast)
    /* Other default colors to override... */
)


@Composable
fun ZarioTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Keep false for consistent study colors
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }


    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect // Added safe cast and early return
            // Set status bar color
            window.statusBarColor = colorScheme.background.toArgb() // Use background color for status bar
            // Set status bar icons appearance (light/dark)
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            // Optional: Set navigation bar color too
            // window.navigationBarColor = colorScheme.background.toArgb()
            // WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
        }
    }


    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}