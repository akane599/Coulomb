// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.database

import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.database.entity.RecordCodes
import io.github.akane599.coulomb.core.database.entity.SourceStatusCodes
import io.github.akane599.coulomb.core.model.ChargeSource
import io.github.akane599.coulomb.core.model.SessionCloseReason
import io.github.akane599.coulomb.core.model.SessionKind
import io.github.akane599.coulomb.core.model.SnapshotKind
import io.github.akane599.coulomb.core.model.SnapshotTrigger
import io.github.akane599.coulomb.core.model.WindowKind
import org.junit.Test

/** ADR-0040: every entry has a frozen code, and the constants SQL uses are those codes. */
class RecordCodesTest {
    @Test
    fun everyEntryHasACodeThatRoundTrips() {
        SessionKind.entries.forEach { assertThat(RecordCodes.sessionKind(RecordCodes.sessionKindCode(it))).isEqualTo(it) }
        SessionCloseReason.entries.forEach { assertThat(RecordCodes.closeReason(RecordCodes.closeReasonCode(it))).isEqualTo(it) }
        SnapshotKind.entries.forEach { assertThat(RecordCodes.snapshotKind(RecordCodes.snapshotKindCode(it))).isEqualTo(it) }
        SnapshotTrigger.entries.forEach { assertThat(RecordCodes.trigger(RecordCodes.triggerCode(it))).isEqualTo(it) }
        WindowKind.entries.forEach { assertThat(RecordCodes.windowKind(RecordCodes.windowKindCode(it))).isEqualTo(it) }
        ChargeSource.entries.forEach { assertThat(RecordCodes.chargeSource(RecordCodes.chargeSourceCode(it))).isEqualTo(it) }
    }

    @Test
    fun theCodesAreFrozen() {
        // Stored rows depend on these: a change here needs a migration.
        assertThat(RecordCodes.SESSION_KINDS.map { it.name }).containsExactly("DISCHARGE", "CHARGE", "SCREEN_ON", "SCREEN_OFF").inOrder()
        assertThat(RecordCodes.WINDOW_KINDS.map { it.name }).containsExactly("CONSECUTIVE", "SESSION", "DAY", "REFERENCE").inOrder()
        assertThat(RecordCodes.SNAPSHOT_KINDS.map { it.name }).containsExactly("LIGHT", "HEAVY").inOrder()
        assertThat(RecordCodes.TRIGGERS.first()).isEqualTo(SnapshotTrigger.SCREEN_OFF)
        assertThat(RecordCodes.TRIGGERS.last()).isEqualTo(SnapshotTrigger.SERVICE_STARTED)
        assertThat(SourceStatusCodes.SOURCES).containsExactly("S5", "S6", "S7", "S4", "S9", "S25", "S26", "S27").inOrder()
        assertThat(SourceStatusCodes.STATUSES).containsExactly("ok", "partial", "failed", "skipped", "slept", "off").inOrder()
    }

    @Test
    fun theSqlConstantsAreTheCodes() {
        assertThat(RecordCodes.sessionKindCode(SessionKind.CHARGE)).isEqualTo(RecordCodes.SESSION_CHARGE)
        assertThat(RecordCodes.snapshotKindCode(SnapshotKind.LIGHT)).isEqualTo(RecordCodes.SNAPSHOT_LIGHT)
        assertThat(RecordCodes.snapshotKindCode(SnapshotKind.HEAVY)).isEqualTo(RecordCodes.SNAPSHOT_HEAVY)
        assertThat(RecordCodes.windowKindCode(WindowKind.CONSECUTIVE)).isEqualTo(RecordCodes.WINDOW_CONSECUTIVE)
        assertThat(RecordCodes.windowKindCode(WindowKind.SESSION)).isEqualTo(RecordCodes.WINDOW_SESSION)
        assertThat(RecordCodes.windowKindCode(WindowKind.REFERENCE)).isEqualTo(RecordCodes.WINDOW_REFERENCE)
        assertThat(RecordCodes.chargeSourceCode(ChargeSource.CHARGE_COUNTER)).isEqualTo(RecordCodes.CHARGE_COUNTER)
    }

    @Test
    fun sourceStatusesRoundTripAndStaySmall() {
        val shell = mapOf("S4" to "off", "S5" to "ok", "S6" to "skipped", "S7" to "failed")
        val code = SourceStatusCodes.encode(shell)
        assertThat(SourceStatusCodes.decode(code)).isEqualTo(shell)
        assertThat(SourceStatusCodes.status(code, "S9")).isNull()
        assertThat(SourceStatusCodes.status(code, "S99")).isNull()
        // Two bytes in SQLite for a shell snapshot's four sources.
        assertThat(code).isLessThan(1L shl 15)
        val all = SourceStatusCodes.SOURCES.associateWith { "slept" }
        assertThat(SourceStatusCodes.decode(SourceStatusCodes.encode(all))).isEqualTo(all)
        assertThat(SourceStatusCodes.encode(emptyMap())).isEqualTo(0)
    }

    @Test
    fun theVersionFourJsonParses() {
        assertThat(SourceStatusCodes.decode(SourceStatusCodes.fromJson("""{"S4":"off","S5":"ok","S7":"failed"}""")))
            .containsExactly("S4", "off", "S5", "ok", "S7", "failed")
        assertThat(SourceStatusCodes.fromJson("{}")).isEqualTo(0)
    }
}
