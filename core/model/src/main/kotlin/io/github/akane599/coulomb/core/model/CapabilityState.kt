// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.model

/** SPEC 1.2 and 4.6: every feature shows one of these states. Stored by name: add, never rename. */
public enum class CapabilityState {
    AVAILABLE,

    /** Works with reduced detail; the UI shows the reason. */
    DEGRADED,

    /** Does not work; the UI shows the fix. */
    UNAVAILABLE,
}
