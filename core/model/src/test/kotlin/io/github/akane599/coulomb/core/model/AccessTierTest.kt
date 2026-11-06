// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AccessTierTest {
    @Test
    fun `tiers are ordered from least to most privileged`() {
        assertThat(AccessTier.entries)
            .containsExactly(
                AccessTier.T0_UNPRIVILEGED,
                AccessTier.T1_DURABLE_GRANTS,
                AccessTier.T2_PRIVILEGED_WORKER,
                AccessTier.T3_ROOT,
            ).inOrder()
        assertThat(AccessTier.T2_PRIVILEGED_WORKER).isGreaterThan(AccessTier.T1_DURABLE_GRANTS)
    }
}
