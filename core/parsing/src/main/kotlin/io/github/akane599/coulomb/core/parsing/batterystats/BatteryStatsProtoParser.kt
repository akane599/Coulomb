// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.parsing.batterystats

import com.squareup.wire.ProtoAdapter
import com.squareup.wire.ProtoReader
import io.github.akane599.coulomb.core.model.parse.Parsed
import io.github.akane599.coulomb.core.model.stats.BatteryStatsDump
import io.github.akane599.coulomb.core.model.stats.DeviceStats
import io.github.akane599.coulomb.core.model.stats.StatsHeader
import io.github.akane599.coulomb.core.model.stats.UidStats
import io.github.akane599.coulomb.core.parsing.ParseContext
import io.github.akane599.coulomb.core.parsing.aosp.os.SystemProto
import io.github.akane599.coulomb.core.parsing.aosp.os.UidProto
import io.github.akane599.coulomb.core.parsing.io.MeteredSource
import java.io.IOException
import java.io.InputStream

/**
 * `dumpsys batterystats --proto` (S5) into a [BatteryStatsDump] (ADR-0013).
 *
 * The two outer messages are walked field by field; each UID and the system message are decoded on
 * their own, so a cut stream keeps every complete UID and a broken entry costs only itself. Never
 * throws on bad input.
 */
public object BatteryStatsProtoParser {
    public const val SOURCE: String = "batterystats.proto"

    // Field numbers: BatteryStatsServiceDumpProto (frameworks/base/core/proto/android/service/
    // batterystats.proto) and BatteryStatsProto (core/proto/android/os/batterystats.proto),
    // android-17.0.0_r1, identical in android-16.0.0_r4.
    private const val DUMP_BATTERYSTATS = 1
    private const val REPORT_VERSION = 1
    private const val PARCEL_VERSION = 2
    private const val START_PLATFORM_VERSION = 3
    private const val END_PLATFORM_VERSION = 4
    private const val UIDS = 5
    private const val SYSTEM = 6

    public fun parse(input: InputStream): Parsed<BatteryStatsDump> {
        val meter = MeteredSource(input)
        val ctx = ParseContext(SOURCE)
        val walk = Walk(meter, ctx)
        val complete = walk.run()
        return Parsed(walk.result(), ctx.report(meter.offset, complete))
    }

    private class Walk(
        private val meter: MeteredSource,
        private val ctx: ParseContext,
    ) {
        private val mapper = ProtoMapper(ctx)
        private var reportVersion: Int? = null
        private var parcelVersion: Long? = null
        private var startPlatform: String? = null
        private var endPlatform: String? = null
        private val uids = ArrayList<UidStats>()
        private var device: DeviceStats? = null
        private var statsSeen = false
        private var statsEnded = false

        /** True when the whole dump was read and nothing required was missing. */
        @Suppress("TooGenericExceptionCaught") // The parser boundary: bad input must never throw (SPEC 5.1).
        fun run(): Boolean {
            try {
                val reader = ProtoReader(meter.source)
                val root = reader.beginMessage()
                while (true) {
                    val tag = reader.nextTag()
                    if (tag == -1) break
                    if (tag == DUMP_BATTERYSTATS) {
                        readStats(reader)
                    } else {
                        ctx.unknown("BatteryStatsServiceDumpProto.$tag")
                        reader.skip()
                    }
                }
                reader.endMessageAndGetUnknownFields(root)
            } catch (e: IOException) {
                ctx.issue(meter.offset, SOURCE, "ended early: ${e.javaClass.simpleName}")
                return false
            } catch (e: RuntimeException) {
                ctx.issue(meter.offset, SOURCE, "malformed: ${e.javaClass.simpleName}")
                return false
            }
            if (!statsSeen) ctx.issue(meter.offset, SOURCE, "no batterystats message")
            if (statsSeen && device == null) ctx.issue(meter.offset, SOURCE, "no system message")
            return statsEnded && device != null
        }

        private fun readStats(reader: ProtoReader) {
            statsSeen = true
            val token = reader.beginMessage()
            while (true) {
                val offset = meter.offset
                val tag = reader.nextTag()
                if (tag == -1) break
                when (tag) {
                    REPORT_VERSION -> {
                        reportVersion = ProtoAdapter.INT32.decode(reader)
                    }

                    PARCEL_VERSION -> {
                        parcelVersion = ProtoAdapter.INT64.decode(reader)
                    }

                    START_PLATFORM_VERSION -> {
                        startPlatform = ProtoAdapter.STRING.decode(reader)
                    }

                    END_PLATFORM_VERSION -> {
                        endPlatform = ProtoAdapter.STRING.decode(reader)
                    }

                    UIDS -> {
                        decodeUid(reader.readBytes().toByteArray(), offset)?.let(uids::add)
                    }

                    SYSTEM -> {
                        device = decodeSystem(reader.readBytes().toByteArray(), offset)
                    }

                    else -> {
                        ctx.unknown("BatteryStatsProto.$tag")
                        reader.skip()
                    }
                }
            }
            reader.endMessageAndGetUnknownFields(token)
            statsEnded = true
        }

        @Suppress("TooGenericExceptionCaught") // One broken entry must not end the walk.
        private fun decodeUid(
            bytes: ByteArray,
            offset: Long,
        ): UidStats? =
            try {
                mapper.uid(UidProto.ADAPTER.decode(bytes))
            } catch (e: IOException) {
                ctx.issue(offset, "UidProto", "malformed: ${e.javaClass.simpleName}")
                null
            } catch (e: RuntimeException) {
                ctx.issue(offset, "UidProto", "malformed: ${e.javaClass.simpleName}")
                null
            }

        @Suppress("TooGenericExceptionCaught") // One broken entry must not end the walk.
        private fun decodeSystem(
            bytes: ByteArray,
            offset: Long,
        ): DeviceStats? =
            try {
                mapper.system(SystemProto.ADAPTER.decode(bytes))
            } catch (e: IOException) {
                ctx.issue(offset, "SystemProto", "malformed: ${e.javaClass.simpleName}")
                null
            } catch (e: RuntimeException) {
                ctx.issue(offset, "SystemProto", "malformed: ${e.javaClass.simpleName}")
                null
            }

        fun result(): BatteryStatsDump {
            val version = reportVersion
            val parcel = parcelVersion
            val header = if (version != null && parcel != null) StatsHeader(version, parcel, startPlatform, endPlatform) else null
            return BatteryStatsDump(header = header, device = device, uids = uids.toList(), uidsComplete = statsEnded)
        }
    }
}
