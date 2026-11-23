// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.parsing.batterystats

import io.github.akane599.coulomb.core.model.parse.Parsed
import io.github.akane599.coulomb.core.model.stats.BatteryStatsDump
import io.github.akane599.coulomb.core.model.stats.StatsHeader
import io.github.akane599.coulomb.core.parsing.ParseContext
import io.github.akane599.coulomb.core.parsing.io.Line
import io.github.akane599.coulomb.core.parsing.io.LineReader
import io.github.akane599.coulomb.core.parsing.io.MeteredSource
import java.io.IOException
import java.io.InputStream
import java.util.TreeMap

/**
 * `dumpsys batterystats -c` (S6, the fallback when the proto is missing) into a [BatteryStatsDump]
 * in the proto parser's shape (ADR-0013). Never throws on bad input.
 *
 * The output differs from [BatteryStatsProtoParser] for the same dump only where checkin carries
 * less:
 * - UIDs with no line of their own (nothing but a package list) are missing.
 * - Entries with zero time (jobs, syncs, sensors, processes, BLE) are missing.
 * - Timer maxima are null except for kernel wakelocks and partial wakelocks; brightness bins
 *   have no count.
 * - Wakelock and alarm names have `,` (and for wakelocks line breaks) replaced by `_`.
 * - The `jbc` columns carry no codes, so an OEM's extra stop reasons are counted as unknown
 *   columns rather than named.
 *
 * The device section comes before the UIDs. A stream cut inside a line keeps the device totals if
 * the UID section had started, and every UID before the one being read. A cut exactly at a line
 * boundary is invisible here; the transport reports it (ADR-0009). Where [CheckinLines] loses the
 * end of a record, reading stops the same way, since a later line may be an app's name dressed as
 * a row. So it does at a row that cannot stand where the writer puts it (`outOfOrder`): job, sync
 * and process names are quoted without escaping, and such a row came out of one.
 */
public object BatteryStatsCheckinParser {
    public const val SOURCE: String = "batterystats.checkin"

    /** @param sdkInt the device's SDK level, which fixes the `jbc` stop-reason columns. */
    public fun parse(
        input: InputStream,
        sdkInt: Int,
    ): Parsed<BatteryStatsDump> {
        val meter = MeteredSource(input)
        val ctx = ParseContext(SOURCE)
        val walk = CheckinWalk(CheckinLines(LineReader(meter)), ctx, JobStopReasons.codes(sdkInt))
        val complete = walk.run()
        return Parsed(walk.result(), ctx.report(meter.offset, complete))
    }
}

private class CheckinWalk(
    private val lines: CheckinLines,
    private val ctx: ParseContext,
    private val stopReasonCodes: IntArray,
) {
    private var header: StatsHeader? = null
    private val device = CheckinDevice(ctx)
    private val uids = TreeMap<Int, CheckinUid>()
    private val packagesByAppId = HashMap<Int, MutableList<String>>()

    /** The UID whose lines are being read; the UID section has started once it is set. */
    private var blockUid: Int? = null
    private var cut = false
    private var otherVersion = false

    /** A row broke the writer's order, so rows already read may be forged too: no UID is kept. */
    private var disordered = false

    /** An `l` line was read; every `i` line comes before them. */
    private var sawSinceCharged = false

    /** The device sections the writer prints once, as read so far. */
    private val deviceOnce = HashSet<String>()

    /** The single-line sections read for [blockUid]. */
    private val blockSections = HashSet<String>()

    @Suppress("TooGenericExceptionCaught") // The parser boundary: bad input must never throw (SPEC 5.1).
    fun run(): Boolean {
        try {
            val stopped = readRows()
            // After a row out of order the rest is drained, not read, so the writer is not left
            // blocked and the byte count is whole.
            if (cut) {
                drain()
            } else if (stopped != null) {
                endedAt(stopped)
            }
        } catch (e: IOException) {
            cut = true
            ctx.issue(lines.offset, BatteryStatsCheckinParser.SOURCE, "ended early: ${e.javaClass.simpleName}")
        } catch (e: RuntimeException) {
            cut = true
            ctx.issue(lines.offset, BatteryStatsCheckinParser.SOURCE, "malformed: ${e.javaClass.simpleName}")
        }
        if (header == null) ctx.issue(lines.offset, BatteryStatsCheckinParser.SOURCE, "no vers line")
        if (!device.sawBattery) ctx.issue(lines.offset, BatteryStatsCheckinParser.SOURCE, "no bt line")
        return complete
    }

    /** Reads whole lines as rows until one breaks the order. The line it stopped at, or null at the end. */
    private fun readRows(): Line? {
        var line = lines.next()
        while (line != null && line.terminated && !line.truncated) {
            read(line)
            if (cut) return line
            line = lines.next()
        }
        return line
    }

    /** The input ended at [line], which is not a whole line. */
    private fun endedAt(line: Line) {
        if (line.truncated) {
            desync(line)
            drain()
        } else {
            cut = true
            ctx.issue(line.offset, BatteryStatsCheckinParser.SOURCE, "ended inside a line")
        }
    }

    private fun drain() {
        while (lines.next() != null) continue
    }

    /** Read to the end, in the known format, with the version and device sections present. */
    private val complete: Boolean get() = !cut && !otherVersion && header != null && device.sawBattery

    /**
     * Where a record ends is lost ([CheckinLines.desync]), so a later line may be part of an
     * app-controlled name dressed as a row: reading stops there, as at a cut.
     */
    private fun desync(line: Line) {
        val why =
            when (lines.desync) {
                CheckinLines.Desync.UNESCAPED_TAG -> "history wakelock tag printed without escaping"
                else -> "record longer than ${LineReader.MAX_LINE_BYTES} characters"
            }
        ctx.issue(line.offset, BatteryStatsCheckinParser.SOURCE, "$why; the rest of the dump is not read")
        cut = true
    }

    private fun isHistory(text: String) = text.startsWith(CheckinLines.HISTORY_PREFIX) || text.startsWith(CheckinLines.POOL_PREFIX)

    private fun read(line: Line) {
        // History records are most of the lines; S7 reads them (ADR-0014).
        if (line.text.isEmpty() || isHistory(line.text)) return
        val row = row(line) ?: return
        outOfOrder(row)?.let { return breakOff(row, it) }
        when (row.category) {
            "i" -> info(row)
            "l" -> sinceCharged(row)
            else -> ctx.unknown("category.${row.category}")
        }
    }

    /**
     * Why [row] cannot stand where the writer puts it, or null. The writer prints the `i` lines,
     * then the device section, then each UID's lines in ascending UID order (a `SparseArray`), with
     * each single-line section at most once (BatteryStats.java `dumpCheckin` and
     * `dumpCheckinLocked`: android-17.0.0_r1 L7832-7905 and L4260-4970, android-16.0.0_r4
     * L7800-7873 and L4239-4949). Job, sync and process names are quoted without escaping, so a name
     * can end its own row and carry others; those break this order where the real lines resume
     * (audit SEC-001). A `wua` name keeps its line breaks but has its commas replaced, so its lines
     * can never be rows and need no rule. A section this parser does not know is not placed: it is
     * counted and never read.
     */
    private fun outOfOrder(row: CheckinRow): String? {
        if (row.category == "i") return "information line after the since-charged section".takeIf { sawSinceCharged }
        if (row.category != "l") return null
        sawSinceCharged = true
        val type = row.type
        val block = blockUid
        return when {
            type in UID_TYPES || type in UID_UNMAPPED -> uidOutOfOrder(row, block)
            type !in DEVICE_TYPES && type !in DEVICE_UNMAPPED -> null
            block != null -> "device line after the UID section"
            else -> "second $type line".takeIf { type in DEVICE_ONCE && !deviceOnce.add(type) }
        }
    }

    /** [outOfOrder] for a line of a UID's own, when the UID being read is [block]. */
    private fun uidOutOfOrder(
        row: CheckinRow,
        block: Int?,
    ): String? {
        if (block != null && row.uid < block) return "UID lines out of order"
        if (row.uid != block) blockSections.clear()
        return "second ${row.type} line for one UID".takeIf { row.type in ONCE_PER_UID && !blockSections.add(row.type) }
    }

    /**
     * Reading stops at [row] as at a cut. Rows before it may have come out of the same name, so no
     * UID is kept; the device section is, once the UID section had begun, since a device line after
     * that point stops the read before it is taken. The messages name sections, never dump text.
     */
    private fun breakOff(
        row: CheckinRow,
        why: String,
    ) {
        ctx.issue(row.offset, BatteryStatsCheckinParser.SOURCE, "$why; the rest of the dump is not read")
        cut = true
        disordered = true
    }

    /** [line] as a row, or null once the reason it is not one is recorded. */
    private fun row(line: Line): CheckinRow? {
        val fields = CheckinLines.split(line.text)
        val uid = fields.getOrNull(1)?.toIntOrNull()
        when {
            fields.size < CheckinRow.HEADER || !isVersion(fields[0]) -> issue(line, "not a checkin line")
            fields[0] != FORMAT_VERSION -> otherFormat(line, fields[0])
            uid == null -> issue(line, "uid is not a number")
            else -> return CheckinRow(line.offset, uid, fields[CATEGORY], fields[TYPE], fields, ctx)
        }
        return null
    }

    /** A short number; anything else is not a format version, and is never echoed into an issue. */
    private fun isVersion(field: String) = field.length <= VERSION_DIGITS && field.isNotEmpty() && field.all { it in '0'..'9' }

    /** The column layout here is version 9's; another version is not guessed at. Reported once. */
    private fun otherFormat(
        line: Line,
        version: String,
    ) {
        if (!otherVersion) issue(line, "checkin format $version")
        otherVersion = true
    }

    private fun issue(
        line: Line,
        message: String,
    ) = ctx.issue(line.offset, BatteryStatsCheckinParser.SOURCE, message)

    private fun info(row: CheckinRow) {
        when (row.type) {
            "vers" -> version(row)
            "uid" -> appPackage(row)
            else -> if (row.type !in INFO_UNMAPPED) ctx.unknown(row.type)
        }
    }

    private fun sinceCharged(row: CheckinRow) {
        val type = row.type
        if (type == "pwi" && row.text(PowerItemCol.LABEL) == APP_LABEL) {
            row.expect(PowerItemCol.entries.size)
            uid(row.uid).powerMah = row.double(PowerItemCol.MAH)
        } else if (type in UID_TYPES || type in UID_UNMAPPED) {
            blockUid = row.uid
            uid(row.uid).accept(row)
        } else if (!device.accept(row) && type !in DEVICE_UNMAPPED) {
            ctx.unknown(type)
        }
    }

    private fun version(row: CheckinRow) {
        row.expect(VersCol.entries.size)
        val version = row.long(VersCol.CHECKIN_VERSION)?.toInt() ?: return
        val parcel = row.long(VersCol.PARCEL_VERSION) ?: return
        header = StatsHeader(version, parcel, row.text(VersCol.START_PLATFORM_VERSION), row.text(VersCol.END_PLATFORM_VERSION))
    }

    private fun appPackage(row: CheckinRow) {
        row.expect(UidDataCol.entries.size)
        val appId = row.long(UidDataCol.APP_ID)?.toInt() ?: return
        val name = row.text(UidDataCol.PACKAGE) ?: return
        packagesByAppId.getOrPut(appId) { ArrayList() } += name
    }

    private fun uid(uid: Int): CheckinUid = uids.getOrPut(uid) { CheckinUid(uid, stopReasonCodes) }

    fun result(): BatteryStatsDump {
        // A cut inside a line: the device section is whole once the UID section has started, and
        // UIDs come in ascending order, so every UID below the one being read is whole.
        val last = blockUid
        val kept =
            if (!cut) {
                uids.values
            } else if (last == null || disordered) {
                emptyList()
            } else {
                uids.headMap(last).values
            }
        val stats =
            kept.map { u ->
                packagesByAppId[appId(u.uid)]?.let(u::addPackages)
                u.build()
            }
        return BatteryStatsDump(
            header = header,
            device = if (device.sawBattery && (!cut || last != null)) device.build() else null,
            uids = stats,
            uidsComplete = complete,
        )
    }

    private companion object {
        const val FORMAT_VERSION = "9"
        const val CATEGORY = 2
        const val TYPE = 3
        const val VERSION_DIGITS = 4

        /** `pwi` label of an app's own total. */
        const val APP_LABEL = "uid"

        /** `UserHandle.PER_USER_RANGE` (core/java/android/os/UserHandle.java android-16.0.0_r4 L49, android-17.0.0_r1 L51). */
        const val PER_USER_RANGE = 100_000

        /** `UserHandle.getAppId` (android-16.0.0_r4 L405-407, android-17.0.0_r1 L436-438). */
        fun appId(uid: Int): Int = uid % PER_USER_RANGE

        /** Per-UID sections [CheckinUid] maps. */
        val UID_TYPES =
            setOf(
                "cpu",
                "wl",
                "awl",
                "wua",
                "jb",
                "jbc",
                "sy",
                "sr",
                "nt",
                "wfl",
                "blem",
                "st",
                "pr",
                "apk",
                "fg",
                "fgs",
                "aud",
                "vid",
                "cam",
                "fla",
                "vib",
            )

        /**
         * Per-UID sections printed at most once for a UID: one `dumpLine`, `dumpTimer` or
         * `dumpControllerActivityLine` call outside any loop over names (L4569-4970).
         */
        val ONCE_PER_UID =
            setOf(
                "nt",
                "mcd",
                "wfl",
                "wfcd",
                "blem",
                "ble",
                "ua",
                "awl",
                "jbd",
                "fla",
                "cam",
                "vid",
                "aud",
                "vib",
                "fg",
                "fgs",
                "st",
                "cpu",
            )

        /** Device sections [CheckinDevice] maps. */
        val DEVICE_TYPES = setOf("bt", "dc", "m", "kwl", "wr", "br", "sgt", "sgc", "sst", "pws", "pwi")

        /** Of those, the ones printed once (L4309-4524). */
        val DEVICE_ONCE = setOf("bt", "dc", "m", "br", "sgt", "sgc", "sst", "pws")

        /** Known per-UID sections the model has no field for: controllers, user activity, multicast, deferred jobs, CPU by frequency. */
        val UID_UNMAPPED = setOf("mcd", "wfcd", "ble", "ua", "wmc", "jbd", "ctf")

        /** Known device sections the model has no field for. */
        val DEVICE_UNMAPPED =
            setOf(
                "gn",
                "gmcd",
                "gwfl",
                "gwfcd",
                "gble",
                "gcf",
                "rpm",
                "lv",
                "dct",
                "dcc",
                "wst",
                "wsc",
                "wsst",
                "wssc",
                "wsgt",
                "wsgc",
                "wmct",
            )

        /** Known `i` lines the model has no field for: step durations and time remaining. */
        val INFO_UNMAPPED = setOf("dsd", "csd", "dtr", "ctr")
    }
}
