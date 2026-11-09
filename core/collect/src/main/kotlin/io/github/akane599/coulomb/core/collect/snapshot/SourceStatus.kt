// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.snapshot

import io.github.akane599.coulomb.core.database.entity.SourceStatusCodes

/** How one source of a snapshot went, as stored in `Snapshot.sourceStatus` ([SourceStatusCodes]). */
internal enum class SourceStatus(
    val text: String,
) {
    OK("ok"),

    /** Some of it was read: cut short, timed out or incomplete. */
    PARTIAL("partial"),

    /** Not read: no route could serve it, or it failed before any data. */
    FAILED("failed"),

    /**
     * Not tried: the snapshot's time budget had run out (ADR-0018); for the kernel files, the
     * snapshot had no counters for them to join (ADR-0029); for S4, S5 was not `ok`, finished late,
     * or the snapshot was plugged in and not the plug-in one (ADR-0035 §3).
     */
    SKIPPED("skipped"),

    /**
     * Read whole, but the CPU slept during the read, so the instant its counters describe is
     * unknown and they cannot end a window (ADR-0018).
     */
    SLEPT("slept"),

    /** Not tried because a setting turns the source off: S4 while Component breakdown is Off (ADR-0035 §2). */
    OFF("off"),
}

/**
 * The source whose counters a snapshot holds: S5, or S6 when S5 failed, and only when that read is
 * [SourceStatus.OK]. Windows join only snapshots with the same source, since the two disagree at
 * the same instant (ADR-0018). Null when neither read is usable: the snapshot is light.
 */
internal fun counterSource(statuses: Map<String, SourceStatus>): String? = COUNTER_SOURCES.firstOrNull { statuses[it] == SourceStatus.OK }

/** [counterSource] of a stored snapshot, from its `sourceStatus` code. */
internal fun counterSource(stored: Long): String? = COUNTER_SOURCES.firstOrNull { sourceStatus(stored, it) == SourceStatus.OK }

private val COUNTER_SOURCES = listOf(HeavyReader.S5, HeavyReader.S6)

/** Source [id]'s status in a stored `sourceStatus` code, or null when it has none. */
internal fun sourceStatus(
    stored: Long,
    id: String,
): SourceStatus? = SourceStatusCodes.status(stored, id)?.let { text -> SourceStatus.entries.first { it.text == text } }

/** The stored code of [statuses]: source ids and statuses only, never values (ADR-0015, ADR-0040). */
internal fun sourceStatusCode(statuses: Map<String, SourceStatus>): Long = SourceStatusCodes.encode(statuses.mapValues { it.value.text })
