// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.engine

import io.github.akane599.coulomb.core.collect.snapshot.HistoryRead
import io.github.akane599.coulomb.core.collect.snapshot.KernelFilesRead
import io.github.akane599.coulomb.core.collect.snapshot.KernelLogResult
import io.github.akane599.coulomb.core.collect.snapshot.StatsRead
import io.github.akane599.coulomb.core.collect.snapshot.UsageRead
import io.github.akane599.coulomb.core.model.DesignCapacity
import io.github.akane599.coulomb.core.model.PrivilegeMode
import io.github.akane599.coulomb.core.model.dumpsys.OemHealthValue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.time.ZoneId

/** The privileged part of a heavy snapshot; `HeavyReader` in the app, a fake in tests. */
internal interface HeavySource {
    /** Whether a dump can run now: the app's own grants or a usable worker (SPEC 4.1, tier T1 or up). */
    fun available(): Boolean

    suspend fun stats(timeoutMs: Long): StatsRead

    /** S4, `dumpsys batterystats --usage --proto` (ADR-0035 §1). */
    suspend fun usage(timeoutMs: Long): UsageRead

    suspend fun history(
        cursorMs: Long,
        timeoutMs: Long,
    ): HistoryRead

    /** The mode of the live Shizuku connection, or null without one (ADR-0029 §1). */
    fun liveMode(): PrivilegeMode?

    /** Whether the kernel reads can run now: a usable worker on a root server (tier T3). */
    fun kernelReadable(): Boolean

    /** S25, S26 and S9 in one worker read (ADR-0029 §2). */
    suspend fun kernelFiles(timeoutMs: Long): KernelFilesRead

    /** S27 after [afterSeq] (-1 for none), which the worker applies only under [bootKey]. */
    suspend fun kernelLog(
        bootKey: Long,
        afterSeq: Long,
        timeoutMs: Long,
    ): KernelLogResult
}

/** The health readings beside Coulomb's estimate (ADR-0042 §2), each null or empty where absent. */
internal data class HealthReadings(
    /** S3 `BATTERY_PROPERTY_STATE_OF_HEALTH`, 1 to 150. */
    val systemSohPct: Int?,
    /** S21's OEM values, as reported. */
    val oemHealth: List<OemHealthValue>,
) {
    companion object {
        val NONE = HealthReadings(null, emptyList())
    }
}

/** The design capacity and the health readings (ADR-0042); `AndroidHealthSource` in the app. */
internal interface HealthSource {
    /** S20, then S23: read once per build while the stored value is unset (SPEC 6.3). */
    suspend fun designCapacity(): DesignCapacity?

    /** S3 and S21, read at most once a day (SPEC 5: "Daily and Health screen"). */
    suspend fun readings(): HealthReadings

    /** No source: tests that are not about health. */
    object None : HealthSource {
        override suspend fun designCapacity(): DesignCapacity? = null

        override suspend fun readings(): HealthReadings = HealthReadings.NONE
    }
}

/** One power monitor's reading (S16): its name with [RailNames]' type prefix, energy since boot. */
internal data class RailReading(
    val rail: String,
    val energyUws: Long,
    val readElapsedNs: Long,
)

/** S16, read with each heavy snapshot when the device has power monitors (SPEC 5, ADR-0068). */
internal interface RailSource {
    suspend fun read(): List<RailReading>

    /** No power monitors: tests that are not about them, and devices without any. */
    object None : RailSource {
        override suspend fun read(): List<RailReading> = emptyList()
    }
}

/**
 * The settings the collector follows. `:core:collect` may not depend on `:core:datastore`
 * (SPEC 15.2), so the app binds this to the stored settings.
 */
public fun interface CollectorSettings {
    /** "Component breakdown" (ADR-0035 §2): whether S4 is read. Read at each heavy snapshot. */
    public suspend fun componentBreakdown(): Boolean

    /** "Monitoring mode" (SPEC 7.1, 14.4): true for Lite, which runs no foreground service. */
    public val liteMode: Flow<Boolean> get() = flowOf(false)

    /** "Screen-on sampling" (SPEC 14.4) in milliseconds, for [SamplerPolicy.intervalMs]. */
    public val visibleSamplingMs: Flow<Long> get() = flowOf(SamplerPolicy.VISIBLE_MS)

    /** "Data retention" (SPEC 14.4): the days raw samples stay before they become 1-minute aggregates. */
    public suspend fun rawSampleDays(): Int = RAW_SAMPLE_DAYS

    public companion object {
        /** SPEC 7.7. */
        public const val RAW_SAMPLE_DAYS: Int = 14
    }
}

/** The device the collector runs on. */
public interface DeviceIdentity {
    /** `Build.FINGERPRINT`: calibration and capabilities are kept per build. */
    public val fingerprint: String
    public val model: String
    public val oem: String
    public val sdkInt: Int
    public val sdkIntFull: Int

    /** Coulomb's own UID (`Process.myUid()`), whose cost SPEC 6.9 records. */
    public val appUid: Int

    /** `Settings.Global.BOOT_COUNT`, or null when it cannot be read (SPEC 7.2). */
    public fun bootCount(): Int?

    /** The zone a local day is counted in. */
    public fun zone(): ZoneId
}

/** Compressed raw dumps in the app's files directory (ADR-0018). */
internal interface BlobStore {
    /**
     * Writes each part gzip-compressed under one base name and returns that name, relative to the
     * files directory, or null when nothing could be written.
     */
    fun write(
        baseName: String,
        parts: Map<String, ByteArray>,
    ): String?

    /** Deletes every part of the blobs named. */
    fun delete(baseNames: Collection<String>)

    /** Every stored blob's base name with its size in bytes, oldest first. */
    fun list(): List<Pair<String, Long>>
}
