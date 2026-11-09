// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.model.AccessTier
import io.github.akane599.coulomb.core.model.Capability
import io.github.akane599.coulomb.core.model.CapabilityId
import io.github.akane599.coulomb.core.model.CapabilityState
import io.github.akane599.coulomb.core.model.FixAction
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CapabilityStoreTest {
    @Test
    fun aCapabilityRoundTripsThroughItsRow() {
        val capability =
            Capability(
                id = CapabilityId.BATTERYSTATS_PROTO,
                state = CapabilityState.DEGRADED,
                tier = AccessTier.T2_PRIVILEGED_WORKER,
                reason = "worker unavailable",
                fixAction = FixAction.RUN_BOOTSTRAP,
                probedAtWallMs = 1_762_700_000_000L,
                fingerprint = "brand/device:17/BUILD",
                detail = mapOf("flags" to "--proto,-c", "sections" to "12"),
            )
        assertThat(capability.toEntity().toModel()).isEqualTo(capability)
    }

    @Test
    fun aRowWithAnIdThisBuildDoesNotKnowIsDroppedAndBadDetailIsEmpty() {
        val row = sample().toEntity()
        assertThat(row.copy(id = "s99.gone").toModel()).isNull()
        assertThat(row.copy(detailJson = "not json").toModel()?.detail).isEmpty()
    }

    private fun sample() = Capability(CapabilityId.CURRENT_NOW, CapabilityState.AVAILABLE, AccessTier.T0_UNPRIVILEGED, null, null, 0L, "f")
}
