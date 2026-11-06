// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.model

// Kinds of the stored records in SPEC 8. The database keeps them by name, so entries may be added
// but never renamed or removed.

/** SPEC 8 `Session.kind`. */
public enum class SessionKind { DISCHARGE, CHARGE, SCREEN_ON, SCREEN_OFF }

/** Why a session ended. */
public enum class SessionCloseReason {
    /** The state that defines the session ended: plug in or out, screen on or off. */
    STATE_CHANGED,

    /** A reboot closes every open session (SPEC 7.8). */
    REBOOT,

    /** The data needed to follow the session stopped, for example a history gap. */
    DATA_GAP,

    /** The user switched monitoring off or to Lite mode. */
    MONITORING_STOPPED,
}

/** SPEC 8 `Snapshot.kind` (SPEC 7.2). */
public enum class SnapshotKind { LIGHT, HEAVY }

/** What started a snapshot (SPEC 7.2). */
public enum class SnapshotTrigger {
    SCREEN_OFF,
    SCREEN_ON,
    USER_PRESENT,
    POWER_CONNECTED,
    POWER_DISCONNECTED,
    LEVEL_CHANGED,
    SCREEN_ON_PERIODIC,
    APP_OPENED,
    PULL_TO_REFRESH,
    BOOT_COMPLETED,
    PACKAGE_REPLACED,
    PACKAGE_CHANGED,
    LITE_PERIODIC,

    /** The collector's service started, or the system restarted it, with no other trigger (ADR-0018). */
    SERVICE_STARTED,
}

/** SPEC 8 `WakeStat.kind`. */
public enum class WakeKind { PARTIAL, KERNEL, WAKEUP_REASON }

/** SPEC 8 `Reference.origin`. */
public enum class ReferenceOrigin { AUTO, USER }

/** SPEC 8 `Insight.severity`. */
public enum class InsightSeverity { INFO, WATCH, ACT }

/** SPEC 8 `Insight.state`. */
public enum class InsightState { NEW, SEEN, SNOOZED, DISMISSED, ACTED }

/** SPEC 8 `ActionLog.origin`. */
public enum class ActionOrigin { USER, INSIGHT }

/** What a stored window spans; it decides how long the window is kept (ADR-0015). */
public enum class WindowKind {
    /** Between two consecutive heavy snapshots: the Timeline's steps. Kept 30 days. */
    CONSECUTIVE,

    /** One session, from its start snapshot to its end snapshot. Kept for good. */
    SESSION,

    /** One local day. Kept for good. */
    DAY,

    /** From a reference to the latest snapshot; replaced at every heavy snapshot. */
    REFERENCE,
}

/** Where a measured charge came from (SPEC 6.1); only the charge counter is Measured. */
public enum class ChargeSource {
    /** Coulomb counter (`CHARGE_COUNTER`) delta: Measured. */
    CHARGE_COUNTER,

    /** Calibrated current integrated over the samples: Estimated. */
    INTEGRATED_CURRENT,

    /** Level change times design capacity: Estimated. */
    LEVEL_DELTA,
}

/** The automatic references of SPEC 7.6; each has one row that moves rather than a new row each time. */
public enum class AutoReference {
    SINCE_BOOT,
    SINCE_UNPLUGGED,
    SINCE_FULL_CHARGE,
    SINCE_SCREEN_OFF,
    TODAY,

    /**
     * Not shown. The last heavy snapshot before the latest statistics reset, kept so a window that
     * spans the reset still counts its earlier part (ADR-0018). The platform resets on unplugging
     * at a high level, so most days have one.
     */
    BEFORE_RESET,
}

/** Why a stretch of the battery history is not known event by event (ADR-0014). */
public enum class HistoryGapReason {
    /** The platform's history buffer overflowed (`*OVERFLOW*`). */
    OVERFLOW,

    /** A read started after the cursor, so records in between were never read. */
    CURSOR_SKIPPED,

    /** A record's end was lost in a read; nothing after it was used. */
    READ_DESYNC,

    /** A read's restated state differed from the stored one: a change was merged away. */
    STATE_MISMATCH,
}
