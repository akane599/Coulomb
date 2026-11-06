// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SchemaTest : DatabaseTest() {
    @Test
    fun theVersionOneSchemaIsFrozen() {
        // Changing a v1 table changes this hash. Bump the version and add a tested migration instead
        // (SPEC 8); until the P2 gate the hash may be updated here together with schemas/1.json.
        assertThat(db.openHelper.readableDatabase.version).isEqualTo(1)
        assertThat(string("SELECT identity_hash FROM room_master_table")).isEqualTo("d1d74c331a901e28c61dd2e3de241d56")
    }

    @Test
    fun dataKeptForGoodIsNeverDeletedByACascade() {
        // SQLite cannot change a foreign key's action without rebuilding the table (ADR-0015).
        val actions =
            listOf("window_result", "reference", "capacity_estimate", "session", "insight", "action_log", "charge_detail")
                .flatMap { table ->
                    db.openHelper.readableDatabase.query("PRAGMA foreign_key_list($table)").use { cursor ->
                        buildList {
                            while (cursor.moveToNext()) {
                                add("$table.${cursor.getString(3)}->${cursor.getString(2)}:${cursor.getString(6)}")
                            }
                        }
                    }
                }
        assertThat(actions).containsExactly(
            "window_result.fromSnapshotId->snapshot:RESTRICT",
            "window_result.toSnapshotId->snapshot:RESTRICT",
            "window_result.sessionId->session:SET NULL",
            "reference.snapshotId->snapshot:RESTRICT",
            "capacity_estimate.sessionId->session:RESTRICT",
            "session.startSnapshotId->snapshot:RESTRICT",
            "session.endSnapshotId->snapshot:RESTRICT",
            "insight.windowId->window_result:SET NULL",
            "action_log.insightId->insight:SET NULL",
            "charge_detail.sessionId->session:CASCADE",
            "charge_detail.chargerProfileId->charger_profile:SET NULL",
        )
    }
}
