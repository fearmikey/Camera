package com.fearmikey.camera.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode

/**
 * App theme that follows the system: Material You dynamic colors from the
 * wallpaper (minSdk 31 guarantees support) and the system light/dark setting.
 *
 * Previews use a fixed fallback palette so renders are deterministic.
 */
@Composable
fun CameraAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme: ColorScheme = when {
        LocalInspectionMode.current -> if (darkTheme) FallbackDark else FallbackLight
        darkTheme -> dynamicDarkColorScheme(context)
        else -> dynamicLightColorScheme(context)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/**
 * The viewfinder is always dark (like every camera app, so the preview isn't
 * washed out), but it still picks up the system accent color.
 */
@Composable
fun ViewfinderTheme(content: @Composable () -> Unit) = CameraAppTheme(darkTheme = true, content = content)

private val FallbackDark = darkColorScheme(
    primary = Color(0xFFA8C7FA),
    onPrimary = Color(0xFF062E6F),
    primaryContainer = Color(0xFF0842A0),
    onPrimaryContainer = Color(0xFFD3E3FD),
    surfaceContainerHigh = Color(0xFF2B2D31),
)

private val FallbackLight = lightColorScheme(
    primary = Color(0xFF0B57D0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E3FD),
    onPrimaryContainer = Color(0xFF041E49),
)
