// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

@file:Suppress("MagicNumber")

package io.github.akane599.coulomb.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.akane599.coulomb.core.designsystem.R

/**
 * Tabular, lining figures everywhere, so values do not jitter as they update (SPEC 11.3).
 * minikin splits the setting at commas and hands each tag to HarfBuzz
 * (frameworks/minikin libs/minikin/FontFeatureUtils.cpp:23-37, android-16.0.0_r4 and
 * android-17.0.0_r1).
 */
private const val FIGURES = "tnum,lnum"

private fun hanken(weight: Int) =
    Font(R.font.hanken_grotesk, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

/** Hanken Grotesk, variable, at the three weights the roles use (SPEC 11.3). */
public val HankenGrotesk: FontFamily = FontFamily(hanken(400), hanken(500), hanken(600))

/** Spline Sans Mono, variable, for raw identifiers (SPEC 11.3). */
public val SplineSansMono: FontFamily =
    FontFamily(
        Font(R.font.spline_sans_mono, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    )

/**
 * Prose breaks its lines over the whole paragraph, so a last line is not left with one word, and
 * headings balance their lines (DESIGN.md: prose wraps "pretty").
 *
 * Every role resolves its paragraph direction from the text's first strong character, falling back
 * to the layout direction: TextView's default heuristic. Compose's unspecified direction instead
 * forces the layout direction, which reorders the punctuation of English fallback strings under an
 * RTL locale ("yet." drawn as ".yet").
 */
private fun role(
    size: Int,
    lineHeight: Int,
    weight: Int,
    tracking: Double = 0.0,
    family: FontFamily = HankenGrotesk,
    lineBreak: LineBreak = LineBreak.Unspecified,
) = TextStyle(
    fontFamily = family,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    fontWeight = FontWeight(weight),
    letterSpacing = tracking.em,
    fontFeatureSettings = FIGURES,
    textDirection = TextDirection.Content,
    lineBreak = lineBreak,
)

/**
 * The type roles of SPEC 11.3 and their weight variants (ADR-0023 §3). There are no other sizes;
 * 14 sp is not a role. Chart text uses these at native size too (ADR-0022).
 */
@Immutable
public data class CoulombTypography(
    /** One hero number per screen. */
    val display: TextStyle = role(56, 60, 600, tracking = -0.02),
    /** The unit beside a [display] number. */
    val displayUnit: TextStyle = role(28, 34, 400),
    /** Screen titles on expanded layouts. */
    val headline: TextStyle = role(28, 34, 600, lineBreak = LineBreak.Heading),
    /** Section and app bar titles; the onboarding title on compact widths. */
    val title: TextStyle = role(20, 26, 600, lineBreak = LineBreak.Heading),
    /** Story sentences, descriptions, switch labels. */
    val body: TextStyle = role(16, 24, 400, lineBreak = LineBreak.Paragraph),
    /** Row names. */
    val bodyName: TextStyle = role(16, 24, 500),
    /** Subheads and card titles. */
    val bodySubhead: TextStyle = role(16, 24, 600),
    /** Table values. */
    val data: TextStyle = role(15, 20, 500),
    /** A bold lead word in a data line. */
    val dataLead: TextStyle = role(15, 20, 600),
    /** Column headers, chips, selection controls. */
    val label: TextStyle = role(13, 18, 500, tracking = 0.01),
    /** Buttons. */
    val labelButton: TextStyle = role(13, 18, 600, tracking = 0.01),
    /** Units, provenance, footnotes, secondary notes. */
    val caption: TextStyle = role(12, 16, 400, lineBreak = LineBreak.Paragraph),
    /** Navigation bar labels. */
    val captionNav: TextStyle = role(12, 16, 500),
    /** Wakelock tags, kernel names, commands. */
    val mono: TextStyle = role(13, 18, 400, family = SplineSansMono),
)

/**
 * Material 3's slots, each filled with the nearest Coulomb role so unstyled Material components
 * never fall back to a size outside SPEC 11.3. Coulomb's own components pick roles directly.
 */
internal fun CoulombTypography.toMaterial(): Typography =
    Typography(
        displayLarge = display,
        displayMedium = display,
        displaySmall = display,
        headlineLarge = headline,
        headlineMedium = headline,
        headlineSmall = headline,
        titleLarge = title,
        titleMedium = bodySubhead,
        titleSmall = labelButton,
        bodyLarge = body,
        bodyMedium = body,
        bodySmall = caption,
        labelLarge = labelButton,
        labelMedium = captionNav,
        labelSmall = caption,
    )
