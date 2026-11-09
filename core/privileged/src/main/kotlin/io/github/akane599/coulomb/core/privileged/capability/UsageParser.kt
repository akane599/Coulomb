// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.privileged.capability

import io.github.akane599.coulomb.core.model.parse.Parsed
import io.github.akane599.coulomb.core.model.stats.UsageStatsDump
import java.io.InputStream

/**
 * S4's parser and checks (ADR-0035 §4), for the probe. `:core:parsing` holds them, which this module
 * may not depend on (SPEC 15.2), so the app binds them.
 */
public fun interface UsageParser {
    /** Reads [input] to its end. */
    public fun parse(input: InputStream): Parsed<UsageStatsDump?>
}
