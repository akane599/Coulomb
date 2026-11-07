// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Coulomb's colour roles (SPEC 11.4). The values come from `DESIGN.md` through the generated
 * `ColorTokens.kt`: [LightColors], [DarkColors] and [BlackColors].
 *
 * Rules the roles carry:
 * - [accent] marks one emphasis per region and never carries text; fills that carry text or an
 *   icon use [accentFill] with [onAccentFill] (ADR-0022, ADR-0023).
 * - [textSecondary] never sits on a coloured fill.
 * - A data hue names a power component and nothing else (ADR-0022); [dataOther] is neutral.
 */
@Immutable
public data class CoulombColors(
    val bg: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val hairline: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accent: Color,
    val good: Color,
    val caution: Color,
    val critical: Color,
    val accentFill: Color,
    val onAccentFill: Color,
    val onGood: Color,
    val onCaution: Color,
    val onCritical: Color,
    val dataScreen: Color,
    val dataCpu: Color,
    val dataRadio: Color,
    val dataWifi: Color,
    val dataGnss: Color,
    val dataBluetooth: Color,
    val dataSensors: Color,
    val dataOther: Color,
)
