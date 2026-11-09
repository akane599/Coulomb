// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.engine

import io.github.akane599.coulomb.core.collect.snapshot.HistoryRead
import io.github.akane599.coulomb.core.collect.snapshot.KernelFilesRead
import io.github.akane599.coulomb.core.collect.snapshot.KernelLogResult
import io.github.akane599.coulomb.core.collect.snapshot.StatsRead
import io.github.akane599.coulomb.core.collect.snapshot.UsageRead
import io.github.akane599.coulomb.core.model.PrivilegeMode
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

/**
 * The settings the collector follows, read at each heavy snapshot. `:core:collect` may not depend
 * on `:core:datastore` (SPEC 15.2), so the app binds this to the stored settings.
 */
public fun interface CollectorSettings {
    /** "Component breakdown" (ADR-0035 §2): whether S4 is read. */
    public suspend fun componentBreakdown(): Boolean
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
