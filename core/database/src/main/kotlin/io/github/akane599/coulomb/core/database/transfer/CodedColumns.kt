// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.database.transfer

import io.github.akane599.coulomb.core.database.entity.DetailCodes
import io.github.akane599.coulomb.core.database.entity.RecordCodes
import io.github.akane599.coulomb.core.database.entity.SourceStatusCodes

/**
 * The columns stored as position codes (ADR-0040) with the names their codes stand for, as
 * `table.column`. The JSON bundle keeps the codes and carries these lists in its manifest; the CSV
 * export writes the names (ADR-0062 §2, §7); the import refuses a code outside its list, which a
 * screen would otherwise meet as a crash. A new position-coded column is added here with its
 * list; `TransferTest` holds every entry against the schema. The columns that store a code of
 * their own (`privilegeMode`, `kernelCoverage`, a kernel log event's kind) stay numbers in both
 * exports.
 */
public object CodedColumns {
    private val provenances = DetailCodes.PROVENANCES.map { it.name }
    private val chargeSources = RecordCodes.CHARGE_SOURCES.map { it.name }

    public val NAMES: Map<String, List<String>> =
        mapOf(
            "session.kind" to RecordCodes.SESSION_KINDS.map { it.name },
            "session.closeReason" to RecordCodes.CLOSE_REASONS.map { it.name },
            "session.chargeSource" to chargeSources,
            "snapshot.kind" to RecordCodes.SNAPSHOT_KINDS.map { it.name },
            "snapshot.trigger" to RecordCodes.TRIGGERS.map { it.name },
            "window_result.kind" to RecordCodes.WINDOW_KINDS.map { it.name },
            "window_result.chargeSource" to chargeSources,
            "window_uid.provenance" to provenances,
            "window_stat.provenance" to provenances,
        )

    /** The names of [table]'s [column], or null when it is not coded. */
    public fun names(
        table: String,
        column: String,
    ): List<String>? = NAMES["$table.$column"]

    /** `snapshot.sourceStatus` in words, "S5=ok S7=partial": the one column that packs several codes. */
    public fun sourceStatus(code: Long): String = SourceStatusCodes.decode(code).entries.joinToString(" ") { "${it.key}=${it.value}" }
}
