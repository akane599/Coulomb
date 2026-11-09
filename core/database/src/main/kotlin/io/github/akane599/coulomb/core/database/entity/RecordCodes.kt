// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.database.entity

import androidx.room.TypeConverter
import io.github.akane599.coulomb.core.model.ChargeSource
import io.github.akane599.coulomb.core.model.SessionCloseReason
import io.github.akane599.coulomb.core.model.SessionKind
import io.github.akane599.coulomb.core.model.SnapshotKind
import io.github.akane599.coulomb.core.model.SnapshotTrigger
import io.github.akane599.coulomb.core.model.WindowKind

/**
 * Schema version 5 stores the kinds of the records kept for good (`session`, `snapshot`,
 * `window_result`) as small integer codes, not by name (ADR-0040). A code is the entry's position
 * in its list, which is the enum's order at version 5: append new entries at the end, never
 * reorder or remove one. The constants are the codes SQL compares with. `RecordCodesTest` fails
 * when an entry has no code or a constant moves. Room needs a converter pair for each of the six
 * enums, hence the twelve functions.
 */
@Suppress("TooManyFunctions")
public object RecordCodes {
    internal val SESSION_KINDS: List<SessionKind> =
        listOf(SessionKind.DISCHARGE, SessionKind.CHARGE, SessionKind.SCREEN_ON, SessionKind.SCREEN_OFF)
    internal val CLOSE_REASONS: List<SessionCloseReason> =
        listOf(
            SessionCloseReason.STATE_CHANGED,
            SessionCloseReason.REBOOT,
            SessionCloseReason.DATA_GAP,
            SessionCloseReason.MONITORING_STOPPED,
        )
    internal val SNAPSHOT_KINDS: List<SnapshotKind> = listOf(SnapshotKind.LIGHT, SnapshotKind.HEAVY)
    internal val TRIGGERS: List<SnapshotTrigger> =
        listOf(
            SnapshotTrigger.SCREEN_OFF,
            SnapshotTrigger.SCREEN_ON,
            SnapshotTrigger.USER_PRESENT,
            SnapshotTrigger.POWER_CONNECTED,
            SnapshotTrigger.POWER_DISCONNECTED,
            SnapshotTrigger.LEVEL_CHANGED,
            SnapshotTrigger.SCREEN_ON_PERIODIC,
            SnapshotTrigger.APP_OPENED,
            SnapshotTrigger.PULL_TO_REFRESH,
            SnapshotTrigger.BOOT_COMPLETED,
            SnapshotTrigger.PACKAGE_REPLACED,
            SnapshotTrigger.PACKAGE_CHANGED,
            SnapshotTrigger.LITE_PERIODIC,
            SnapshotTrigger.SERVICE_STARTED,
        )
    internal val WINDOW_KINDS: List<WindowKind> =
        listOf(WindowKind.CONSECUTIVE, WindowKind.SESSION, WindowKind.DAY, WindowKind.REFERENCE)
    internal val CHARGE_SOURCES: List<ChargeSource> =
        listOf(ChargeSource.CHARGE_COUNTER, ChargeSource.INTEGRATED_CURRENT, ChargeSource.LEVEL_DELTA)

    // The codes the DAOs' SQL compares with.
    public const val SESSION_CHARGE: Int = 1
    public const val CHARGE_COUNTER: Int = 0
    public const val SNAPSHOT_LIGHT: Int = 0
    public const val SNAPSHOT_HEAVY: Int = 1
    public const val WINDOW_CONSECUTIVE: Int = 0
    public const val WINDOW_SESSION: Int = 1
    public const val WINDOW_REFERENCE: Int = 3

    @TypeConverter
    public fun sessionKindCode(kind: SessionKind): Int = code(SESSION_KINDS, kind)

    @TypeConverter
    public fun sessionKind(code: Int): SessionKind = entry(SESSION_KINDS, code)

    @TypeConverter
    public fun closeReasonCode(reason: SessionCloseReason): Int = code(CLOSE_REASONS, reason)

    @TypeConverter
    public fun closeReason(code: Int): SessionCloseReason = entry(CLOSE_REASONS, code)

    @TypeConverter
    public fun snapshotKindCode(kind: SnapshotKind): Int = code(SNAPSHOT_KINDS, kind)

    @TypeConverter
    public fun snapshotKind(code: Int): SnapshotKind = entry(SNAPSHOT_KINDS, code)

    @TypeConverter
    public fun triggerCode(trigger: SnapshotTrigger): Int = code(TRIGGERS, trigger)

    @TypeConverter
    public fun trigger(code: Int): SnapshotTrigger = entry(TRIGGERS, code)

    @TypeConverter
    public fun windowKindCode(kind: WindowKind): Int = code(WINDOW_KINDS, kind)

    @TypeConverter
    public fun windowKind(code: Int): WindowKind = entry(WINDOW_KINDS, code)

    @TypeConverter
    public fun chargeSourceCode(source: ChargeSource): Int = code(CHARGE_SOURCES, source)

    @TypeConverter
    public fun chargeSource(code: Int): ChargeSource = entry(CHARGE_SOURCES, code)

    private fun <T> code(
        entries: List<T>,
        value: T,
    ): Int = entries.indexOf(value).also { check(it >= 0) { "No code for $value" } }

    private fun <T> entry(
        entries: List<T>,
        code: Int,
    ): T = entries.getOrNull(code) ?: error("Unknown code $code")
}

/**
 * A snapshot's source outcomes as one integer (ADR-0040): [BITS] bits per source, in the order of
 * [SOURCES], each holding 0 for no outcome or the outcome's position in [STATUSES] plus one. Both
 * lists are frozen: append, never reorder or remove. Source ids and outcomes are the collector's
 * (`HeavyReader`, `KernelReader`, `SourceStatus`); this module only stores them.
 */
public object SourceStatusCodes {
    /** S5, S6 and S7 come first: every heavy snapshot has them, so the code stays small. */
    internal val SOURCES: List<String> = listOf("S5", "S6", "S7", "S4", "S9", "S25", "S26", "S27")
    internal val STATUSES: List<String> = listOf("ok", "partial", "failed", "skipped", "slept", "off")
    internal const val BITS: Int = 3
    private const val MASK = (1L shl BITS) - 1

    /** Version 4's form, `{"S5":"ok","S7":"partial"}`, as the collector wrote it. */
    private val PAIR = Regex("\"([^\"]+)\":\"([^\"]+)\"")

    public fun encode(statuses: Map<String, String>): Long =
        statuses.entries.fold(0L) { code, (id, status) ->
            val slot = SOURCES.indexOf(id)
            val value = STATUSES.indexOf(status)
            check(slot >= 0) { "No slot for source $id" }
            check(value >= 0) { "No code for status $status" }
            code or ((value + 1L) shl (slot * BITS))
        }

    public fun decode(code: Long): Map<String, String> =
        SOURCES.withIndex().mapNotNull { (slot, id) -> status(code, slot)?.let { id to it } }.toMap()

    /** Source [id]'s outcome in [code], or null when it has none. */
    public fun status(
        code: Long,
        id: String,
    ): String? = SOURCES.indexOf(id).takeIf { it >= 0 }?.let { status(code, it) }

    private fun status(
        code: Long,
        slot: Int,
    ): String? = ((code shr (slot * BITS)) and MASK).toInt().takeIf { it > 0 }?.let { STATUSES.getOrNull(it - 1) }

    /**
     * The code of a version 4 `sourceStatusJson`. The collector only ever wrote the ids and outcomes
     * of the two lists; should another appear, an unknown outcome becomes `failed` (not read, so no
     * value is trusted) and an unknown id is dropped (nothing reads it), so the migration never stops.
     */
    public fun fromJson(json: String): Long =
        encode(
            PAIR
                .findAll(json)
                .map { it.groupValues[1] to it.groupValues[2] }
                .filter { (id, _) -> id in SOURCES }
                .associate { (id, status) -> id to if (status in STATUSES) status else FAILED },
        )

    private const val FAILED = "failed"
}
