// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.engine

import io.github.akane599.coulomb.core.collect.snapshot.HistoryRead
import io.github.akane599.coulomb.core.collect.snapshot.StatsRead
import java.time.ZoneId

/** The privileged part of a heavy snapshot; `HeavyReader` in the app, a fake in tests. */
internal interface HeavySource {
    /** Whether a dump can run now: the app's own grants or a usable worker (SPEC 4.1, tier T1 or up). */
    fun available(): Boolean

    suspend fun stats(timeoutMs: Long): StatsRead

    suspend fun history(
        cursorMs: Long,
        timeoutMs: Long,
    ): HistoryRead
}

/** The device the collector runs on. */
public interface DeviceIdentity {
    /** `Build.FINGERPRINT`: calibration and capabilities are kept per build. */
    public val fingerprint: String
    public val model: String
    public val oem: String
    public val sdkInt: Int
    public val sdkIntFull: Int

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
