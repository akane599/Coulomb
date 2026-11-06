// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.snapshot

/** How one source of a snapshot went, as stored in `Snapshot.sourceStatusJson`. */
internal enum class SourceStatus(
    val text: String,
) {
    OK("ok"),

    /** Some of it was read: cut short, timed out or incomplete. */
    PARTIAL("partial"),

    /** Not read: no route could serve it, or it failed before any data. */
    FAILED("failed"),

    /** Not tried: the snapshot's time budget had run out (ADR-0018). */
    SKIPPED("skipped"),
}

/** `{"S5":"ok","S7":"partial"}`: source ids and statuses only, never values (ADR-0015). */
internal fun sourceStatusJson(statuses: Map<String, SourceStatus>): String =
    statuses.entries.sortedBy { it.key }.joinToString(",", "{", "}") { (id, status) -> "\"$id\":\"${status.text}\"" }
