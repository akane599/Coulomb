// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.privileged.client

import io.github.akane599.coulomb.core.privileged.DumpResult
import io.github.akane599.coulomb.core.privileged.FailureReason
import io.github.akane599.coulomb.core.privileged.PartialReason
import io.github.akane599.coulomb.core.privileged.PatternCheck
import io.github.akane599.coulomb.core.privileged.Route
import io.github.akane599.coulomb.core.privileged.TestPattern
import io.github.akane599.coulomb.core.privileged.WorkerOrigin
import io.github.akane599.coulomb.core.privileged.WorkerStatusCode
import java.io.InputStream

// Pure conversions from a finished transfer to the public result types, kept out of
// PrivilegeController so the controller holds only state and routing.

/** A refusal line or a SecurityException from the dump call means "not permitted", never data. */
internal fun <R> TransferResult<Sniffed<R>>.toSniffedResult(
    route: Route,
    isComplete: (R) -> Boolean,
    origin: WorkerOrigin? = null,
): DumpResult<R> {
    val thrown = (producer as? ProducerOutcome.Threw)?.error as? SecurityException
    val denied = (value as? Sniffed.Denied)?.line ?: thrown?.toString()
    if (denied != null) return DumpResult.Unavailable(FailureReason.NOT_PERMITTED, denied)
    val unwrapped = (value as? Sniffed.Value<R>)?.value
    return TransferResult(
        unwrapped,
        consumerFinished,
        bytes,
        producer,
        partial,
        detail,
        durationMs,
    ).toDumpResult(route, isComplete, origin)
}

internal fun <R> TransferResult<R>.toDumpResult(
    route: Route,
    isComplete: (R) -> Boolean,
    origin: WorkerOrigin? = null,
): DumpResult<R> {
    val status = (producer as? ProducerOutcome.Status)?.code
    if (bytes == 0L && status != null &&
        status != WorkerStatusCode.OK
    ) {
        return DumpResult.Unavailable(PrivilegeController.failureFor(status), "status $status")
    }
    partial?.let { return DumpResult.Partial(value, route, bytes, it, detail, origin) }
    // No partial reason means the consumer returned normally, so value holds its result.
    @Suppress("UNCHECKED_CAST")
    val v = value as R
    return if (isComplete(v)) {
        DumpResult.Complete(v, route, bytes, durationMs, origin)
    } else {
        DumpResult.Partial(v, route, bytes, PartialReason.TRUNCATED, "content ended early", origin)
    }
}

internal fun verifyPattern(
    input: InputStream,
    expected: Long,
): PatternCheck {
    val buffer = ByteArray(PATTERN_BUFFER)
    val runtime = Runtime.getRuntime()
    var offset = 0L
    var mismatch: Long? = null
    var peakHeap = 0L
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        if (mismatch == null) mismatch = firstMismatch(buffer, n, offset)
        offset += n
        peakHeap = maxOf(peakHeap, runtime.totalMemory() - runtime.freeMemory())
    }
    return PatternCheck(expected, offset, mismatch, peakHeap)
}

private fun firstMismatch(
    buffer: ByteArray,
    length: Int,
    offset: Long,
): Long? {
    // A plain loop: the drill measures heap, so no boxing per byte.
    for (i in 0 until length) {
        if (buffer[i] != TestPattern.byteAt(offset + i)) return offset + i
    }
    return null
}

private const val PATTERN_BUFFER = 64 * 1024
