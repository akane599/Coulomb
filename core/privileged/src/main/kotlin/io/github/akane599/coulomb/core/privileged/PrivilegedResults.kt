// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.privileged

/** Which path produced a result. */
public enum class Route {
    /** Coulomb's UserService worker (T2/T3). */
    WORKER,

    /** A direct binder call relayed by the Shizuku server (SPEC 4.3 fallback; bootstrap only). */
    SHIZUKU_BINDER,

    /** Coulomb's own process with durable grants (T1). */
    IN_APP,
}

/**
 * Which route a dump tries first. Both give the same output; they differ in cost. The worker is a
 * separate process that Shizuku starts and that exits after a minute idle, so a background
 * collector that dumps every few minutes pays for a bind each time. The in-app route runs in
 * Coulomb's own process with the T1 grants (ADR-0010, ADR-0018).
 */
public enum class RoutePreference {
    /** The worker, then T1 in Coulomb's process (the default, for interactive use). */
    WORKER_FIRST,

    /** T1 in Coulomb's process when the grants allow it, then the worker. */
    IN_APP_FIRST,
}

/**
 * The connection a worker result ran under (ADR-0029 §1): the worker's uid from its handshake and
 * the number of the manager binder it was bound through, one higher for each binder received.
 */
public data class WorkerOrigin(
    val uid: Int,
    val connection: Long,
) {
    val isRoot: Boolean get() = uid == 0
}

/** Outcome of a streamed dump. Partial results still carry whatever the consumer parsed (SPEC 4.5). */
public sealed interface DumpResult<out R> {
    public data class Complete<R>(
        val value: R,
        val route: Route,
        val bytes: Long,
        val durationMs: Long,
        /** Set when [route] is [Route.WORKER]. */
        val origin: WorkerOrigin? = null,
    ) : DumpResult<R>

    public data class Partial<R>(
        val value: R?,
        val route: Route,
        val bytes: Long,
        val reason: PartialReason,
        val detail: String?,
        /** Set when [route] is [Route.WORKER]. */
        val origin: WorkerOrigin? = null,
    ) : DumpResult<R>

    public data class Unavailable(
        val reason: FailureReason,
        val detail: String? = null,
    ) : DumpResult<Nothing>
}

public enum class PartialReason {
    /** The worker or its binder died while the dump was running (`DeadObjectException`). */
    WORKER_DIED,

    /** The dump did not finish within its time limit; the read end was closed. */
    TIMEOUT,

    /** Reading the pipe failed. */
    READ_ERROR,

    /** The producer reported a failure after writing some bytes. */
    PRODUCER_FAILED,

    /** The consumer threw; the stream was abandoned. */
    CONSUMER_ERROR,

    /** The stream ended cleanly but the consumer found its content incomplete. */
    TRUNCATED,
}

public enum class FailureReason {
    /** No tier that can serve this request is available right now. */
    NO_ACCESS,
    SERVICE_NOT_FOUND,
    DUMP_FAILED,
    COMMAND_FAILED,

    /** EACCES in the worker: the feature needs root (SPEC 4.5). */
    ACCESS_DENIED,
    NOT_FOUND,
    INVALID_ARGUMENT,
    NOT_PERMITTED,
    TIMEOUT,

    /** The worker binder died before the call completed. */
    WORKER_DIED,
    WORKER_ERROR,
}

/** Outcome of a small, non-streamed privileged call. */
public sealed interface PrivilegedResult<out T> {
    public data class Success<T>(
        val value: T,
        val route: Route,
        /** Set when [route] is [Route.WORKER]. */
        val origin: WorkerOrigin? = null,
    ) : PrivilegedResult<T>

    public data class Failure(
        val reason: FailureReason,
        val detail: String? = null,
    ) : PrivilegedResult<Nothing>
}

public fun <T> PrivilegedResult<T>.valueOrNull(): T? = (this as? PrivilegedResult.Success<T>)?.value

/** The T1 grants of SPEC 4.4 (ADR-0010) as bootstrap rows. [USAGE_STATS] is the app op; [PACKAGE_USAGE_STATS] the permission. */
public enum class GrantKind { BATTERY_STATS, DUMP, PACKAGE_USAGE_STATS, USAGE_STATS, INTERACT_ACROSS_USERS, WRITE_SECURE_SETTINGS }

public enum class GrantOutcome {
    ALREADY_GRANTED,

    /** Granted now and verified with checkSelfPermission / AppOpsManager. */
    GRANTED,

    /** The command ran but verification still fails. */
    NOT_VERIFIED,

    /** No worker or binder path could run the command; use the ADB lines. */
    NO_PRIVILEGED_PATH,
    COMMAND_FAILED,
}

public data class BootstrapItem(
    val grant: GrantKind,
    val outcome: GrantOutcome,
    val route: Route?,
)

public data class BootstrapReport(
    val items: List<BootstrapItem>,
    val adbCommands: List<String>,
) {
    val allGranted: Boolean get() = items.all { it.outcome == GrantOutcome.GRANTED || it.outcome == GrantOutcome.ALREADY_GRANTED }
}
