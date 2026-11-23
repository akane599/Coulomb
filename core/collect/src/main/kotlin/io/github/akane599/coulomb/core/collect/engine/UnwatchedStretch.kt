// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.engine

import io.github.akane599.coulomb.core.collect.history.ReadResult
import io.github.akane599.coulomb.core.collect.snapshot.LightReading
import io.github.akane599.coulomb.core.collect.snapshot.SourceStatus
import io.github.akane599.coulomb.core.database.CoulombDatabase
import io.github.akane599.coulomb.core.database.entity.BootEntity
import io.github.akane599.coulomb.core.model.SessionKind
import io.github.akane599.coulomb.core.model.history.HistoryKind

/** What a stretch Coulomb did not watch may hide from its sessions. */
internal data class Unwatched(
    /** Kinds whose state is known to have changed: the history or the stored readings show it. */
    val changedUnseen: Set<SessionKind>,
    /** Kinds nothing followed, so a change is possible and not known ([WriteContext.notFollowed]). */
    val notFollowed: Set<SessionKind>,
) {
    companion object {
        val NONE = Unwatched(emptySet(), emptySet())
    }
}

/**
 * The stretch since the last snapshot when the service's receivers were not attached through it:
 * after a process start, and between two looks in Lite (ADR-0064 §5, audit BUG-007 and BUG-001).
 */
internal class UnwatchedStretch(
    private val db: CoulombDatabase,
) {
    /** [historyStatus] and [ingest] are this snapshot's history read, null when none was made. */
    suspend fun since(
        historyStatus: SourceStatus?,
        ingest: ReadResult?,
        reading: LightReading,
        boot: BootEntity,
    ): Unwatched =
        Unwatched(
            changedUnseen = unseenKinds(ingest) + chargedUnseen(reading, boot),
            notFollowed = if (followed(historyStatus, ingest)) emptySet() else SCREEN_KINDS,
        )

    /** Plug and screen kinds whose history state flipped in a read Coulomb was not watching. */
    private fun unseenKinds(ingest: ReadResult?): Set<SessionKind> {
        val transitions = ingest?.transitions.orEmpty()
        return buildSet {
            if (HistoryKind.PLUGGED in transitions) addAll(PLUG_KINDS)
            if (HistoryKind.SCREEN in transitions) addAll(SCREEN_KINDS)
        }
    }

    /**
     * Whether the history read followed the whole stretch. A read that ended, was placed and left
     * no gap starts at the stored cursor, which the last snapshot or an earlier one set, so its
     * flips are every change there was. No read at all (a light snapshot, no privileges), one
     * skipped for the time budget, a failed or unplaced one, or one with a gap followed nothing.
     * An unplaced read's records are not used either: after lost record boundaries they may be an
     * app's text (ADR-0014). The screen has no other witness; the plug has [chargedUnseen].
     */
    private fun followed(
        status: SourceStatus?,
        result: ReadResult?,
    ): Boolean = status == SourceStatus.OK && result != null && result.nextCursorMs != null && result.gaps.isEmpty()

    /**
     * What the stored readings show of the plug, in the same boot. A level well above the last
     * unplugged one: the device charged, even when the history read does not show it. On a charger
     * at both looks but another one: it was unplugged in between. Across a reboot the gauge may
     * re-estimate the level, so nothing is inferred there.
     */
    private suspend fun chargedUnseen(
        reading: LightReading,
        boot: BootEntity,
    ): Set<SessionKind> {
        val level = reading.levelPct ?: return emptySet()
        val snapshot = db.snapshotDao().latest()
        val sample = db.sampleDao().latest()
        val sampleIsLater =
            sample != null &&
                (
                    snapshot == null ||
                        sample.bootSeq > snapshot.bootSeq ||
                        (sample.bootSeq == snapshot.bootSeq && sample.elapsedNs > snapshot.elapsedNs)
                )
        // The latest stored reading, as (boot, plug type, level).
        val last =
            when {
                sampleIsLater -> Triple(sample.bootSeq, sample.plugType, sample.levelPct)
                snapshot != null -> Triple(snapshot.bootSeq, snapshot.plugType, snapshot.levelPct)
                else -> return emptySet()
            }
        val (lastBoot, lastPlug, lastLevel) = last
        if (lastBoot != boot.bootSeq) return emptySet()
        val changed =
            if (reading.plugged) {
                lastPlug != null && lastPlug != 0 && lastPlug != reading.plugType
            } else {
                lastPlug == 0 && lastLevel != null && level > lastLevel + LEVEL_NOISE
            }
        return if (changed) PLUG_KINDS else emptySet()
    }

    private companion object {
        /** A level this far above the last one stored is a charge, not gauge noise. */
        const val LEVEL_NOISE = 1
        val PLUG_KINDS = setOf(SessionKind.CHARGE, SessionKind.DISCHARGE)
        val SCREEN_KINDS = setOf(SessionKind.SCREEN_ON, SessionKind.SCREEN_OFF)
    }
}
