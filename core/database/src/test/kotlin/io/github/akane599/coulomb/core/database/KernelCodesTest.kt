// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.database

import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.database.entity.KernelCodes
import io.github.akane599.coulomb.core.model.PrivilegeMode
import io.github.akane599.coulomb.core.model.kernel.KernelCoverage
import io.github.akane599.coulomb.core.model.kernel.KernelCoverage.Coverage
import io.github.akane599.coulomb.core.model.kernel.KernelLogEvent
import io.github.akane599.coulomb.core.model.kernel.SeqGap
import io.github.akane599.coulomb.core.model.kernel.WakeupCause
import io.github.akane599.coulomb.core.model.kernel.WakeupIrq
import org.junit.Test

class KernelCodesTest {
    @Test
    fun everyStoredEventComesBackAsItWent() {
        val events =
            listOf(
                KernelLogEvent.ResumeIrq(WakeupIrq(200, "qcom,smp2p-modem")),
                KernelLogEvent.ResumeOther("ws: alarmtimer"),
                KernelLogEvent.ResumeUnknown,
                KernelLogEvent.Abort("Pending Wakeup Sources: wlan_rx_wake"),
                KernelLogEvent.DevicesFailed,
                KernelLogEvent.DeviceFailed("alarmtimer alarmtimer.0.auto", "suspend", -16),
                KernelLogEvent.FreezeFailed("user space tasks", aborted = false),
                KernelLogEvent.FreezeFailed("remaining freezable tasks", aborted = true),
            )
        for (event in events) {
            assertThat(KernelCodes.decodeEvent(checkNotNull(KernelCodes.encodeEvent(event)))).isEqualTo(event)
        }
        assertThat(events.map { KernelCodes.encodeEvent(it)!!.kind }).containsExactly(0, 1, 2, 3, 4, 5, 6, 7).inOrder()
    }

    @Test
    fun suspendEntryAndExitAreNotStored() {
        assertThat(KernelCodes.encodeEvent(KernelLogEvent.SuspendEntry("deep"))).isNull()
        assertThat(KernelCodes.encodeEvent(KernelLogEvent.SuspendExit)).isNull()
    }

    @Test
    fun coverageUsesTwoBitsPerSourceInSpecOrder() {
        for (s25 in Coverage.entries) {
            for (s9 in Coverage.entries) {
                for (s27 in Coverage.entries) {
                    val coverage = KernelCoverage(s25, s9, s27)
                    assertThat(KernelCodes.coverage(KernelCodes.coverageCode(coverage))).isEqualTo(coverage)
                }
            }
        }
        // S25 lowest, then S9, then S27 (ADR-0029 §3): these stored values never move.
        assertThat(KernelCoverage(Coverage.WHOLE, Coverage.NONE, Coverage.NONE).code).isEqualTo(0b00_00_10)
        assertThat(KernelCoverage(Coverage.NONE, Coverage.PARTIAL, Coverage.NONE).code).isEqualTo(0b00_01_00)
        assertThat(KernelCoverage(Coverage.NONE, Coverage.NONE, Coverage.WHOLE).code).isEqualTo(0b10_00_00)
        assertThat(KernelCodes.coverageCode(null)).isNull()
    }

    @Test
    fun privilegeModeCodesAreFrozen() {
        assertThat(PrivilegeMode.entries.associateWith { KernelCodes.privilegeModeCode(it) })
            .containsExactly(PrivilegeMode.SHELL, 1, PrivilegeMode.ROOT, 2, PrivilegeMode.OTHER, 3)
        assertThat(KernelCodes.privilegeMode(null)).isNull()
        assertThat(PrivilegeMode.ofUid(0)).isEqualTo(PrivilegeMode.ROOT)
        assertThat(PrivilegeMode.ofUid(2000)).isEqualTo(PrivilegeMode.SHELL)
        assertThat(PrivilegeMode.ofUid(10_123)).isEqualTo(PrivilegeMode.OTHER)
    }

    @Test
    fun gapsAndCausesRoundTrip() {
        val gaps = listOf(SeqGap(5, 5), SeqGap(10, 4_000_000_000L))
        assertThat(KernelCodes.encodeGaps(gaps)).isEqualTo("5-5,10-4000000000")
        assertThat(KernelCodes.decodeGaps(KernelCodes.encodeGaps(gaps))).isEqualTo(gaps)
        assertThat(KernelCodes.decodeGaps("")).isEmpty()
        val causes =
            listOf(
                WakeupCause.Irqs(listOf(WakeupIrq(170, "qpnp_rtc_alarm"), WakeupIrq(3, "a b c"))),
                WakeupCause.Abort("Last active Wakeup Source: eventpoll"),
                WakeupCause.Abnormal("unknown wakeup"),
                WakeupCause.None,
            )
        for (cause in causes) assertThat(KernelCodes.decodeCause(KernelCodes.encodeCause(cause))).isEqualTo(cause)
    }
}
