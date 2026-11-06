// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.privileged

import io.github.akane599.coulomb.core.model.GrantStatus
import io.github.akane599.coulomb.core.model.PrivilegeState
import kotlinx.coroutines.flow.StateFlow
import java.io.InputStream

/**
 * The only way the rest of Coulomb reaches privileged data (SPEC 4). Every call picks the best
 * available route (worker, then T1 grants in Coulomb's own process) and says which one it used.
 * Nothing here throws for an expected failure; the result types carry the reason.
 *
 * One facade on purpose (hence the suppression): callers must not pick routes themselves, so status,
 * dumps and small calls stay behind the object that knows which route is live.
 */
@Suppress("TooManyFunctions")
public interface PrivilegedAccess {
    public val state: StateFlow<PrivilegeState>

    /** Diagnostics for the Privileges screen and the capability report (SPEC 14.3, 4.6). */
    public val diagnostics: StateFlow<PrivilegeDiagnostics>

    /** The last bootstrap run, automatic (first authorization) or one-tap repair. */
    public val lastBootstrap: StateFlow<BootstrapReport?>

    /** Idempotent. Re-verifies T1 grants, checks the OS fingerprint and starts listening to Shizuku. */
    public fun start()

    /** Opens the manager's authorization dialog. No-op without a binder or when already granted. */
    public fun requestManagerPermission()

    /** Re-reads the T1 grants (cheap; call on resume). */
    public suspend fun refreshGrants(): GrantStatus

    /**
     * SPEC 4.4: grants the durable permissions through the worker (or the Shizuku binder when the
     * worker cannot bind), then verifies each one. `WRITE_SECURE_SETTINGS` only on request.
     */
    public suspend fun bootstrap(includeWriteSecureSettings: Boolean = false): BootstrapReport

    /** The same commands as copyable `adb shell` lines (SPEC 4.4). */
    public fun adbBootstrapCommands(includeWriteSecureSettings: Boolean = false): List<String>

    /**
     * Streams `dumpsys batterystats <mode>` into [consume]. [isComplete] lets a parser that found
     * the content cut short report a partial result even though the stream ended cleanly. [route]
     * picks which route is tried first; the other is the fallback.
     */
    public suspend fun <R> dumpBatteryStats(
        mode: BatteryStatsMode,
        historyStart: Long = 0L,
        timeoutMs: Long = DEFAULT_DUMP_TIMEOUT_MS,
        isComplete: (R) -> Boolean = { true },
        route: RoutePreference = RoutePreference.WORKER_FIRST,
        consume: suspend (InputStream) -> R,
    ): DumpResult<R>

    public suspend fun <R> dumpService(
        service: SystemService,
        timeoutMs: Long = DEFAULT_DUMP_TIMEOUT_MS,
        isComplete: (R) -> Boolean = { true },
        route: RoutePreference = RoutePreference.WORKER_FIRST,
        consume: suspend (InputStream) -> R,
    ): DumpResult<R>

    /** Worker only: `cmd` has no in-app equivalent. */
    public suspend fun <R> runQuery(
        query: PrivilegedQuery,
        packageName: String? = null,
        timeoutMs: Long = DEFAULT_DUMP_TIMEOUT_MS,
        consume: suspend (InputStream) -> R,
    ): DumpResult<R>

    /** Value of an allowlisted battery-related key, or null when unset (ADR-0009). Worker only. */
    public suspend fun getSetting(
        namespace: SettingsNamespace,
        key: String,
    ): PrivilegedResult<String?>

    /** Names of the battery-related keys present in [namespace]; values never cross (ADR-0009). */
    public suspend fun listSettingKeys(namespace: SettingsNamespace): PrivilegedResult<List<String>>

    public suspend fun readSysfs(
        root: SysfsRoot,
        relativePath: String,
    ): PrivilegedResult<ByteArray>

    public suspend fun listSysfs(
        root: SysfsRoot,
        relativeDir: String = "",
    ): PrivilegedResult<List<String>>

    /** Unbinds the worker now (e.g. when the app leaves the foreground); it rebinds on demand. */
    public fun releaseWorker()

    public companion object {
        public const val DEFAULT_DUMP_TIMEOUT_MS: Long = 60_000L
    }
}

public data class PrivilegeDiagnostics(
    /** Time from bind to a verified handshake (SPEC 4.3 "measure bind cost"). */
    val lastBindMs: Long? = null,
    val workerUid: Int? = null,
    val workerSeLinuxContext: String? = null,
    /** The OS build changed since grants were last verified (SPEC 4.1: re-verify, re-probe). */
    val fingerprintChanged: Boolean = false,
    /** Core grants were verified at some point, so their absence now means "revoked". */
    val grantsVerifiedBefore: Boolean = false,
    /** A permission request has had no answer for 5 s (SPEC 4.5); a late answer still counts. */
    val permissionRequestHanging: Boolean = false,
    /** Whether the Shizuku-binder grant path worked the last time it was tried; null if never tried. */
    val binderGrantPathWorked: Boolean? = null,
)

/**
 * Debug-build drills for protocol F (SPEC 16.3, 16.6). Every method refuses to run in a release
 * build: the worker refuses the test stream, and the client-side switches check debuggability.
 */
public interface PrivilegeDrills {
    /**
     * Streams [bytes] of the worker's test pattern and verifies every byte as it arrives. With
     * [truncateAt] < [bytes] the worker stops early and reports success, which must come back as
     * [PartialReason.TRUNCATED]; [chunkDelayMs] stretches the stream so Shizuku can be stopped mid-dump.
     */
    public suspend fun streamTestPattern(
        bytes: Long,
        truncateAt: Long = bytes,
        chunkDelayMs: Int = 0,
        timeoutMs: Long = PrivilegedAccess.DEFAULT_DUMP_TIMEOUT_MS,
    ): DumpResult<PatternCheck>

    /** Rewrites the stored fingerprint and re-runs the start-up verification, as after an OS update. */
    public suspend fun simulateFingerprintChange()

    /** Makes the next binds never call `bindUserService`, so the 5 s timeout and fallback run for real. */
    public fun setSimulatedBindHang(enabled: Boolean)

    /** Clears an UNAVAILABLE worker for this session so a drill can be repeated. */
    public fun resetWorker()
}

public data class PatternCheck(
    val expectedBytes: Long,
    val receivedBytes: Long,
    val firstMismatchAt: Long?,
    val peakHeapBytes: Long,
) {
    val complete: Boolean get() = receivedBytes == expectedBytes && firstMismatchAt == null
}
