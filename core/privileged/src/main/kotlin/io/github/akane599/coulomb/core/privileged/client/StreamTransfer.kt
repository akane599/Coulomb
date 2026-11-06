// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.privileged.client

import android.os.DeadObjectException
import io.github.akane599.coulomb.core.privileged.PartialReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/** One pipe: the consumer reads [input], the producer gets [sink] (a ParcelFileDescriptor on device). */
internal interface PipePair<S> {
    val input: InputStream
    val sink: S

    /** Closes Coulomb's copy of the write end; the reader sees EOF once every copy is closed. */
    fun closeSink()

    /** Closes the read end; a blocked writer then fails with EPIPE, a blocked read with IOException. */
    fun closeInput()
}

internal sealed interface ProducerOutcome {
    data class Status(
        val code: Int,
    ) : ProducerOutcome

    data class Threw(
        val error: Throwable,
    ) : ProducerOutcome
}

internal data class TransferResult<R>(
    val value: R?,
    val consumerFinished: Boolean,
    val bytes: Long,
    val producer: ProducerOutcome?,
    val partial: PartialReason?,
    val detail: String?,
    val durationMs: Long,
)

/**
 * Streams a producer's output into a consumer through a pipe (SPEC 4.3: large outputs never cross
 * binder as return values). The producer is a blocking call (a binder call to the worker or
 * `Debug.dumpService`) that writes into the sink and returns a status; the consumer parses
 * incrementally, so memory stays flat whatever the dump size (SPEC 3.3).
 *
 * Ordering rules that avoid deadlocks: the consumer runs concurrently with the producer; when the
 * consumer returns early, the read end is closed so the writer stops instead of blocking on a full
 * pipe; Coulomb's copy of the write end is closed as soon as the producer returns.
 */
internal class StreamTransfer(
    private val io: CoroutineDispatcher,
    private val clock: () -> Long,
    private val graceMs: Long = GRACE_MS,
) {
    // A blocking binder call or a stuck parser cannot be interrupted, so producer and consumer run
    // outside the caller's scope: the caller stops waiting after the grace period and their threads
    // finish on their own once the call returns (closing the read end usually makes that quick).
    private val detached = CoroutineScope(SupervisorJob() + io)

    suspend fun <S, R> run(
        pipe: PipePair<S>,
        timeoutMs: Long,
        produce: (S) -> Int,
        okStatus: Int,
        consume: suspend (InputStream) -> R,
    ): TransferResult<R> {
        val start = clock()
        val counting = CountingInputStream(pipe.input)
        val producer = startProducer(pipe, produce)
        val consumer = startConsumer(counting, consume)
        try {
            val consumed = withTimeoutOrNull(timeoutMs) { consumer.await() }
            // Early return, error or timeout: release the writer.
            pipe.closeInput()
            val result = consumed ?: withTimeoutOrNull(graceMs) { consumer.await() }
            val outcome = withTimeoutOrNull(graceMs) { producer.await() }
            if (result == null) consumer.cancel()
            if (outcome == null) producer.cancel()
            val timedOut = consumed == null
            val (partial, detail) = classify(timedOut, timeoutMs, result?.exceptionOrNull(), outcome, okStatus)
            return TransferResult(
                value = result?.getOrNull(),
                consumerFinished = result?.isSuccess == true && !timedOut,
                bytes = counting.count,
                producer = outcome,
                partial = partial,
                detail = detail,
                durationMs = clock() - start,
            )
        } catch (e: CancellationException) {
            pipe.closeInput()
            consumer.cancel()
            producer.cancel()
            throw e
        }
    }

    // Every producer failure (binder, worker side, in-app dump) and every consumer failure (a parser
    // bug) must end as a classified result, never crash the collector.
    @Suppress("TooGenericExceptionCaught")
    private fun <S> startProducer(
        pipe: PipePair<S>,
        produce: (S) -> Int,
    ): Deferred<ProducerOutcome> =
        detached.async {
            try {
                ProducerOutcome.Status(produce(pipe.sink))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                ProducerOutcome.Threw(e)
            } finally {
                pipe.closeSink()
            }
        }

    @Suppress("TooGenericExceptionCaught")
    private fun <R> startConsumer(
        input: InputStream,
        consume: suspend (InputStream) -> R,
    ): Deferred<Result<R>> =
        detached.async {
            try {
                Result.success(consume(input))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Result.failure(e)
            }
        }

    /**
     * Root cause first: a dead worker explains a parser error on the cut stream, and a consumer
     * error explains the writer then meeting a closed pipe.
     */
    private fun classify(
        timedOut: Boolean,
        timeoutMs: Long,
        error: Throwable?,
        outcome: ProducerOutcome?,
        okStatus: Int,
    ): Pair<PartialReason?, String?> {
        val thrown = (outcome as? ProducerOutcome.Threw)?.error
        val status = (outcome as? ProducerOutcome.Status)?.code
        return when {
            timedOut -> PartialReason.TIMEOUT to "no end of stream after $timeoutMs ms"
            thrown is DeadObjectException -> PartialReason.WORKER_DIED to thrown.toString()
            error is IOException -> PartialReason.READ_ERROR to error.toString()
            error != null -> PartialReason.CONSUMER_ERROR to error.toString()
            thrown != null -> PartialReason.PRODUCER_FAILED to thrown.toString()
            status == null -> PartialReason.TIMEOUT to "producer still running after the stream ended"
            status != okStatus -> PartialReason.PRODUCER_FAILED to "status $status"
            else -> null to null
        }
    }

    private companion object {
        const val GRACE_MS = 5_000L
    }
}

internal class CountingInputStream(
    input: InputStream,
) : FilterInputStream(input) {
    @Volatile var count: Long = 0L
        private set

    override fun read(): Int = super.read().also { if (it >= 0) count++ }

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int = super.read(b, off, len).also { if (it > 0) count += it }

    override fun skip(n: Long): Long = super.skip(n).also { count += it }
}
