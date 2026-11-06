// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.model

/** Privilege tiers (SPEC 4.1). Every feature declares the minimum tier it needs. Stored by name: add, never rename. */
public enum class AccessTier {
    /** Normal and runtime permissions, plus usage access toggled by the user. */
    T0_UNPRIVILEGED,

    /** `pm grant` / `appops set` run once; survive reboots and Shizuku stopping. */
    T1_DURABLE_GRANTS,

    /** Shizuku or Sui UserService running as shell (uid 2000) or root. */
    T2_PRIVILEGED_WORKER,

    /** Root through Shizuku, Sui or another root manager. Optional; no core screen needs it. */
    T3_ROOT,
}
