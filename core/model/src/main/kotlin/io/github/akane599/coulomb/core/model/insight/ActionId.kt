// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.model.insight

/**
 * What an insight's card can offer (SPEC 9.2 "Action offered"): an Action Center action for the
 * app, or a place to open. "Deny wakelocks" is not among them: Android does not act on it
 * (ADR-0060 §7).
 */
public enum class ActionId {
    RESTRICT_BACKGROUND,
    STANDBY_BUCKET,
    OPEN_SESSION,
    OPEN_NIGHT_REPORT,
    OPEN_LOCATION_PERMISSION,
    OPEN_SCANNING_SETTINGS,
    OPEN_APP_DETAIL,
    OPEN_HEALTH,
    OPEN_BATTERY_PROTECTION,
    OPEN_DOZE,
    OPEN_SELF_DIAGNOSTIC,
    SWITCH_TO_LITE,
}
