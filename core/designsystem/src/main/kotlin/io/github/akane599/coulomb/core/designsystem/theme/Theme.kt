// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.LocalRippleThemeConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.RippleDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// The theme's locals are the design system's own, as MaterialTheme's are for Material roles.
// build.gradle.kts exempts this file from the Compose CompositionLocal allowlist rule.
private val LocalCoulombColors = staticCompositionLocalOf { LightColors }
private val LocalCoulombTypography = staticCompositionLocalOf { CoulombTypography() }
internal val LocalReducedMotion = staticCompositionLocalOf { false }

/**
 * Coulomb's theme: its colour and type roles, also mapped onto Material 3 for the Material
 * components Coulomb restyles (SPEC 11.7). Springs are in [CoulombMotion], which Material's
 * components also follow through [CoulombMotionScheme].
 *
 * Keyboard focus draws Material's inset ring in `accent` over a `bg` band, the copper focus ring of
 * DESIGN.md, so it reads on any fill.
 *
 * @param trueBlack the opt-in OLED theme; only applies when [darkTheme] is true (SPEC 11.4).
 * @param reducedMotion SPEC 11.6's reduced motion, read from the system by default.
 */
@Composable
public fun CoulombTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    trueBlack: Boolean = false,
    reducedMotion: Boolean = rememberReducedMotion(),
    content: @Composable () -> Unit,
) {
    val colors =
        when {
            !darkTheme -> LightColors
            trueBlack -> BlackColors
            else -> DarkColors
        }
    val typography = CoulombTypography()
    CompositionLocalProvider(
        LocalCoulombColors provides colors,
        LocalCoulombTypography provides typography,
        LocalReducedMotion provides reducedMotion,
    ) {
        MaterialTheme(
            colorScheme = colors.toMaterial(darkTheme),
            motionScheme = CoulombMotionScheme,
            shapes = CoulombShapes,
            typography = typography.toMaterial(),
        ) {
            CompositionLocalProvider(
                LocalRippleThemeConfiguration provides RippleDefaults.InsetFocusRingThemeConfiguration,
                LocalRippleConfiguration provides
                    RippleConfiguration(focus = RippleConfiguration.Focus.InsetRing(colors.accent, colors.bg)),
                content = content,
            )
        }
    }
}

/** Reads the current theme's roles. */
public object CoulombTheme {
    public val colors: CoulombColors
        @Composable @ReadOnlyComposable
        get() = LocalCoulombColors.current

    public val typography: CoulombTypography
        @Composable @ReadOnlyComposable
        get() = LocalCoulombTypography.current

    /** SPEC 11.6: every transition becomes a 150 ms crossfade, and nothing else moves. */
    public val reducedMotion: Boolean
        @Composable @ReadOnlyComposable
        get() = LocalReducedMotion.current
}

/**
 * Material 3's colour slots on Coulomb's roles. Every slot Material draws text or icons in is
 * ink, never [CoulombColors.accent], which carries no text (SPEC 11.4): `primary` is
 * `textPrimary`, so an unstyled text button stays legible. Material's text-bearing fills
 * (filled button, navigation pill, selected chip and segment) use `accentFill` through the
 * container slots. Coulomb's own components set their colours themselves.
 */
internal fun CoulombColors.toMaterial(dark: Boolean): ColorScheme {
    val scheme = if (dark) darkColorScheme() else lightColorScheme()
    return scheme.copy(
        primary = textPrimary,
        onPrimary = bg,
        primaryContainer = accentFill,
        onPrimaryContainer = onAccentFill,
        inversePrimary = accentFill,
        secondary = textSecondary,
        onSecondary = bg,
        secondaryContainer = accentFill,
        onSecondaryContainer = onAccentFill,
        tertiary = textPrimary,
        onTertiary = bg,
        tertiaryContainer = surfaceRaised,
        onTertiaryContainer = textPrimary,
        background = bg,
        onBackground = textPrimary,
        surface = bg,
        onSurface = textPrimary,
        surfaceVariant = surface,
        onSurfaceVariant = textSecondary,
        surfaceTint = Color.Transparent,
        inverseSurface = textPrimary,
        inverseOnSurface = bg,
        error = critical,
        onError = onCritical,
        errorContainer = surfaceRaised,
        onErrorContainer = textPrimary,
        outline = textSecondary,
        outlineVariant = hairline,
        scrim = Color.Black,
        surfaceBright = surface,
        surfaceDim = bg,
        surfaceContainerLowest = bg,
        surfaceContainerLow = surface,
        surfaceContainer = surface,
        surfaceContainerHigh = surfaceRaised,
        surfaceContainerHighest = surfaceRaised,
    )
}
