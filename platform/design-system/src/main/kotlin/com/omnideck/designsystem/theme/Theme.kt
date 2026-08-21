package com.omnideck.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val BrandSeedLight = lightColorScheme(
    primary = Color(0xFF2A5DB0),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD8E2FF),
    onPrimaryContainer = Color(0xFF001A41),
    secondary = Color(0xFF565E71),
    tertiary = Color(0xFF715573),
    error = Color(0xFFBA1A1A),
    background = Color(0xFFFDFBFF),
    surface = Color(0xFFFDFBFF),
    surfaceVariant = Color(0xFFE1E2EC),
    outline = Color(0xFF757780),
)

private val BrandSeedDark = darkColorScheme(
    primary = Color(0xFFADC6FF),
    onPrimary = Color(0xFF002E69),
    primaryContainer = Color(0xFF004494),
    onPrimaryContainer = Color(0xFFD8E2FF),
    secondary = Color(0xFFBEC6DC),
    tertiary = Color(0xFFDEBCDF),
    error = Color(0xFFFFB4AB),
    background = Color(0xFF1B1B1F),
    surface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFF44464F),
    outline = Color(0xFF8E9099),
)

/**
 * The single theme every module renders inside (goal G4).
 *
 * Modules must not define their own `MaterialTheme` — a lint rule flags it. Uniform
 * theming is what stops a super-app from feeling like a folder full of other people's
 * apps, and it is also what makes a platform-wide accessibility or rebrand change a
 * one-file diff.
 */
@Composable
fun OmniDeckTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /** Material You. Off in screenshot tests so Roborazzi diffs stay deterministic. */
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> BrandSeedDark
        else -> BrandSeedLight
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = OmniDeckTypography,
        shapes = OmniDeckShapes,
        content = content,
    )
}
