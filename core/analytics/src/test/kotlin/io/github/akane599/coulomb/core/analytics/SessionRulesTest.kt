// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.analytics

import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.analytics.SessionChange.Close
import io.github.akane599.coulomb.core.analytics.SessionChange.Open
import io.github.akane599.coulomb.core.model.SessionCloseReason
import io.github.akane599.coulomb.core.model.SessionKind.CHARGE
import io.github.akane599.coulomb.core.model.SessionKind.DISCHARGE
import io.github.akane599.coulomb.core.model.SessionKind.SCREEN_OFF
import io.github.akane599.coulomb.core.model.SessionKind.SCREEN_ON
import org.junit.Test

class SessionRulesTest {
    private val unpluggedScreenOn = DeviceState(bootSeq = 1, plugged = false, screenOn = true)

    @Test
    fun theFirstLookOpensADischargeAndAScreenSession() {
        assertThat(SessionRules.reconcile(emptyList(), unpluggedScreenOn)).containsExactly(Open(DISCHARGE), Open(SCREEN_ON)).inOrder()
    }

    @Test
    fun plugAndScreenChangesSwapTheirSessions() {
        val open = listOf(OpenSession(1, DISCHARGE, 1), OpenSession(2, SCREEN_ON, 1))
        assertThat(SessionRules.reconcile(open, unpluggedScreenOn.copy(plugged = true)))
            .containsExactly(Close(1, SessionCloseReason.STATE_CHANGED), Open(CHARGE))
            .inOrder()
        assertThat(SessionRules.reconcile(open, unpluggedScreenOn.copy(screenOn = false)))
            .containsExactly(Close(2, SessionCloseReason.STATE_CHANGED), Open(SCREEN_OFF))
            .inOrder()
        // A repeated broadcast changes nothing.
        assertThat(SessionRules.reconcile(open, unpluggedScreenOn)).isEmpty()
    }

    @Test
    fun aRebootClosesEverySessionOfTheEarlierBoot() {
        val open = listOf(OpenSession(1, DISCHARGE, 1), OpenSession(2, SCREEN_OFF, 1))
        assertThat(SessionRules.reconcile(open, unpluggedScreenOn.copy(bootSeq = 2)))
            .containsExactly(Close(1, SessionCloseReason.REBOOT), Close(2, SessionCloseReason.REBOOT), Open(DISCHARGE), Open(SCREEN_ON))
            .inOrder()
    }

    @Test
    fun aChangeNobodyWatchedIsADataGap() {
        val open = listOf(OpenSession(1, DISCHARGE, 1), OpenSession(2, SCREEN_ON, 1))
        assertThat(SessionRules.reconcile(open, DeviceState(1, plugged = true, screenOn = true, watchedSinceLastLook = false)))
            .containsExactly(Close(1, SessionCloseReason.DATA_GAP), Open(CHARGE))
            .inOrder()
    }

    @Test
    fun stoppingMonitoringClosesEverythingAndOpensNothing() {
        val open = listOf(OpenSession(1, DISCHARGE, 1), OpenSession(2, SCREEN_ON, 1))
        assertThat(SessionRules.reconcile(open, unpluggedScreenOn.copy(monitoring = false)))
            .containsExactly(Close(1, SessionCloseReason.MONITORING_STOPPED), Close(2, SessionCloseReason.MONITORING_STOPPED))
    }

    @Test
    fun aSecondSessionOfAKindIsClosed() {
        val open = listOf(OpenSession(1, DISCHARGE, 1), OpenSession(3, DISCHARGE, 1), OpenSession(2, SCREEN_ON, 1))
        assertThat(SessionRules.reconcile(open, unpluggedScreenOn)).containsExactly(Close(3, SessionCloseReason.DATA_GAP))
    }

    @Test
    fun anUnplugAndReplugNobodySawEndsTheChargeSession() {
        val open = listOf(OpenSession(1, CHARGE, 1), OpenSession(2, SCREEN_ON, 1))
        val state = unpluggedScreenOn.copy(plugged = true, watchedSinceLastLook = false, changedUnseen = setOf(CHARGE))
        assertThat(SessionRules.reconcile(open, state))
            .containsExactly(Close(1, SessionCloseReason.DATA_GAP), Open(CHARGE))
            .inOrder()
    }
}
