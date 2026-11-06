// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.snapshot

import io.github.akane599.coulomb.core.model.history.BatteryHistory
import io.github.akane599.coulomb.core.model.history.HistoryRecord
import io.github.akane599.coulomb.core.model.parse.Parsed
import io.github.akane599.coulomb.core.model.stats.BatteryStatsDump
import io.github.akane599.coulomb.core.parsing.batterystats.BatteryHistoryParser
import io.github.akane599.coulomb.core.parsing.batterystats.BatteryStatsCheckinParser
import io.github.akane599.coulomb.core.parsing.batterystats.BatteryStatsProtoParser
import io.github.akane599.coulomb.core.privileged.BatteryStatsMode
import io.github.akane599.coulomb.core.privileged.DumpResult
import io.github.akane599.coulomb.core.privileged.PrivilegedAccess
import io.github.akane599.coulomb.core.privileged.RoutePreference
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream

/** The S5 (or S6) read of a heavy snapshot. */
internal data class StatsRead(
    val dump: BatteryStatsDump?,
    /** How S5 went, and S6 when it stood in for a failed S5. */
    val statuses: Map<String, SourceStatus>,
    /** The raw proto, for a blob; null when S6 was used or the copy passed [HeavyReader.MAX_RAW_BYTES]. */
    val rawProto: ByteArray?,
    /** The raw text of an incomplete read, for diagnosis (ADR-0018). */
    val rawIncomplete: ByteArray?,
)

/** The S7 read. [history] is null when the dump gave nothing parseable. */
internal data class HistoryRead(
    val records: List<HistoryRecord>,
    val history: BatteryHistory?,
    val status: SourceStatus,
)

/**
 * The privileged part of a heavy snapshot (SPEC 7.2, ADR-0018): S5, with S6 when S5 fails, then
 * the incremental history read. Dumps go in-app first: the background collector should not
 * start the worker process for every snapshot. Never throws for an expected failure.
 */
internal class HeavyReader(
    private val access: PrivilegedAccess,
    private val sdkInt: Int,
) {
    suspend fun stats(timeoutMs: Long): StatsRead {
        val copy = CappedCopy(MAX_RAW_BYTES)
        val proto =
            access.dumpBatteryStats<Parsed<BatteryStatsDump>>(
                BatteryStatsMode.PROTO,
                timeoutMs = timeoutMs,
                isComplete = { it.report.complete },
                route = RoutePreference.IN_APP_FIRST,
            ) { BatteryStatsProtoParser.parse(copy.wrap(it)) }
        val protoValue = proto.parsed()
        if (protoValue?.value?.device != null) {
            val status = if (proto is DumpResult.Complete) SourceStatus.OK else SourceStatus.PARTIAL
            return StatsRead(protoValue.value, mapOf(S5 to status), copy.bytes(), copy.bytes().takeIf { status != SourceStatus.OK })
        }
        val textCopy = CappedCopy(MAX_RAW_BYTES)
        val checkin =
            access.dumpBatteryStats<Parsed<BatteryStatsDump>>(
                BatteryStatsMode.CHECKIN,
                timeoutMs = timeoutMs,
                isComplete = { it.report.complete },
                route = RoutePreference.IN_APP_FIRST,
            ) { BatteryStatsCheckinParser.parse(textCopy.wrap(it), sdkInt) }
        val checkinValue = checkin.parsed()
        val s6 =
            when {
                checkinValue?.value?.device == null -> SourceStatus.FAILED
                checkin is DumpResult.Complete -> SourceStatus.OK
                else -> SourceStatus.PARTIAL
            }
        return StatsRead(
            dump = checkinValue?.value?.takeIf { s6 != SourceStatus.FAILED },
            statuses = mapOf(S5 to SourceStatus.FAILED, S6 to s6),
            rawProto = null,
            rawIncomplete = (textCopy.bytes() ?: copy.bytes()).takeIf { s6 != SourceStatus.OK },
        )
    }

    suspend fun history(
        cursorMs: Long,
        timeoutMs: Long,
    ): HistoryRead {
        val records = ArrayList<HistoryRecord>()
        val result =
            access.dumpBatteryStats<Parsed<BatteryHistory>>(
                BatteryStatsMode.CHECKIN_HISTORY,
                historyStart = cursorMs,
                timeoutMs = timeoutMs,
                isComplete = { it.report.complete },
                route = RoutePreference.IN_APP_FIRST,
            ) { input -> BatteryHistoryParser.parse(input, cursorMs) { batch -> records += batch } }
        val parsed = result.parsed()
        val status =
            when {
                parsed == null -> SourceStatus.FAILED
                result is DumpResult.Complete && parsed.report.complete -> SourceStatus.OK
                else -> SourceStatus.PARTIAL
            }
        // A read cut short has no NEXT: and cannot be placed; the ingest keeps the cursor for it.
        return HistoryRead(records, parsed?.value, status)
    }

    private fun <R> DumpResult<R>.parsed(): R? =
        when (this) {
            is DumpResult.Complete -> value
            is DumpResult.Partial -> value
            is DumpResult.Unavailable -> null
        }

    /** Copies what a parser reads, up to [cap] bytes; past that the copy is dropped, not cut. */
    private class CappedCopy(
        private val cap: Int,
    ) {
        private val out = ByteArrayOutputStream()
        private var overflow = false

        fun bytes(): ByteArray? = if (overflow || out.size() == 0) null else out.toByteArray()

        fun wrap(input: InputStream): InputStream =
            object : FilterInputStream(input) {
                override fun read(): Int = super.read().also { if (it >= 0) keep(byteArrayOf(it.toByte()), 0, 1) }

                override fun read(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ): Int = super.read(b, off, len).also { if (it > 0) keep(b, off, it) }
            }

        private fun keep(
            b: ByteArray,
            off: Int,
            len: Int,
        ) {
            if (overflow) return
            if (out.size() + len > cap) {
                overflow = true
                out.reset()
            } else {
                out.write(b, off, len)
            }
        }
    }

    companion object {
        const val S5: String = "S5"
        const val S6: String = "S6"
        const val S7: String = "S7"

        /** The largest raw dump copied for a blob; the phone's proto is 273 KB. */
        const val MAX_RAW_BYTES: Int = 8 * 1024 * 1024
    }
}
