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

    /**
     * Not tried: the snapshot's time budget had run out (ADR-0018), or, for the kernel files, the
     * snapshot had no counters for them to join (ADR-0029).
     */
    SKIPPED("skipped"),

    /**
     * Read whole, but the CPU slept during the read, so the instant its counters describe is
     * unknown and they cannot end a window (ADR-0018).
     */
    SLEPT("slept"),
}

/**
 * The source whose counters a snapshot holds: S5, or S6 when S5 failed, and only when that read is
 * [SourceStatus.OK]. Windows join only snapshots with the same source, since the two disagree at
 * the same instant (ADR-0018). Null when neither read is usable: the snapshot is light.
 */
internal fun counterSource(statuses: Map<String, SourceStatus>): String? = COUNTER_SOURCES.firstOrNull { statuses[it] == SourceStatus.OK }

/** [counterSource] of a stored snapshot, from its `sourceStatusJson`. */
internal fun counterSource(json: String): String? = COUNTER_SOURCES.firstOrNull { "\"$it\":\"${SourceStatus.OK.text}\"" in json }

private val COUNTER_SOURCES = listOf(HeavyReader.S5, HeavyReader.S6)

/** Source [id]'s status in a stored `sourceStatusJson`, or null when it has none. */
internal fun sourceStatus(
    json: String,
    id: String,
): SourceStatus? = SourceStatus.entries.firstOrNull { "\"$id\":\"${it.text}\"" in json }

/** `{"S5":"ok","S7":"partial"}`: source ids and statuses only, never values (ADR-0015). */
internal fun sourceStatusJson(statuses: Map<String, SourceStatus>): String =
    statuses.entries.sortedBy { it.key }.joinToString(",", "{", "}") { (id, status) -> "\"$id\":\"${status.text}\"" }
