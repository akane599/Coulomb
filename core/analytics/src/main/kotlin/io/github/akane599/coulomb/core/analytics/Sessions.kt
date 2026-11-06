// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.analytics

import io.github.akane599.coulomb.core.model.SessionCloseReason
import io.github.akane599.coulomb.core.model.SessionKind

/** A session that is open, as the rules see it. */
public data class OpenSession(
    val id: Long,
    val kind: SessionKind,
    val bootSeq: Int,
)

/** The device as the collector last saw it. */
public data class DeviceState(
    val bootSeq: Int,
    val plugged: Boolean,
    val screenOn: Boolean,
    /**
     * False when Coulomb was not watching since its last look, as after its process was stopped:
     * a change found now happened at an unknown time.
     */
    val watchedSinceLastLook: Boolean = true,
    /** False once the user stops monitoring. */
    val monitoring: Boolean = true,
    /**
     * Kinds whose state changed while Coulomb was not watching even though it now looks the same,
     * as an unplug and replug found in the battery history: their sessions end with a data gap.
     */
    val changedUnseen: Set<SessionKind> = emptySet(),
)

/** What the collector does to the stored sessions. */
public sealed interface SessionChange {
    public data class Close(
        val id: Long,
        val reason: SessionCloseReason,
    ) : SessionChange

    public data class Open(
        val kind: SessionKind,
    ) : SessionChange
}

/**
 * SPEC 7.2 and 7.8 sessions: a discharge or charge session is open while unplugged or plugged, and
 * a screen-on or screen-off session while the screen is. [reconcile] compares the open sessions with
 * the device and returns the changes, so a repeated or missed broadcast cannot leave two sessions
 * of a kind open.
 */
public object SessionRules {
    public fun wanted(state: DeviceState): Set<SessionKind> =
        if (!state.monitoring) {
            emptySet()
        } else {
            setOf(
                if (state.plugged) SessionKind.CHARGE else SessionKind.DISCHARGE,
                if (state.screenOn) SessionKind.SCREEN_ON else SessionKind.SCREEN_OFF,
            )
        }

    /**
     * Closes, in this order of reasons: sessions from an earlier boot ([SessionCloseReason.REBOOT]);
     * every session once monitoring stops; a second session of a kind, and one whose state changed
     * unseen ([SessionCloseReason.DATA_GAP]); and sessions the state no longer wants
     * ([SessionCloseReason.STATE_CHANGED], or [SessionCloseReason.DATA_GAP] when the change was not
     * watched). Then opens each wanted kind that is not open.
     */
    public fun reconcile(
        open: List<OpenSession>,
        state: DeviceState,
    ): List<SessionChange> {
        val wanted = wanted(state)
        val closes = mutableListOf<SessionChange.Close>()
        val kept = mutableSetOf<SessionKind>()
        for (session in open.sortedBy { it.id }) {
            val reason =
                when {
                    session.bootSeq != state.bootSeq -> {
                        SessionCloseReason.REBOOT
                    }

                    !state.monitoring -> {
                        SessionCloseReason.MONITORING_STOPPED
                    }

                    session.kind in kept || session.kind in state.changedUnseen -> {
                        SessionCloseReason.DATA_GAP
                    }

                    session.kind !in wanted -> {
                        if (state.watchedSinceLastLook) SessionCloseReason.STATE_CHANGED else SessionCloseReason.DATA_GAP
                    }

                    else -> {
                        null
                    }
                }
            if (reason == null) kept += session.kind else closes += SessionChange.Close(session.id, reason)
        }
        return closes + (wanted - kept).sorted().map { SessionChange.Open(it) }
    }
}
