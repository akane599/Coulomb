// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.privileged.client

import android.os.DeadObjectException
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.privileged.PartialReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real threads: the producer blocks on a full pipe exactly as a binder dump would. */
class StreamTransferTest {
    private fun now() = System.nanoTime() / 1_000_000

    private val transfer = StreamTransfer(Dispatchers.IO, ::now)

    private fun pipe(buffer: Int = 64 * 1024): PipePair<OutputStream> {
        val out = PipedOutputStream()
        val input = PipedInputStream(out, buffer)
        return object : PipePair<OutputStream> {
            override val input: InputStream = input
            override val sink: OutputStream = out

            override fun closeSink() {
                runCatching { out.close() }
            }

            override fun closeInput() {
                runCatching { input.close() }
            }
        }
    }

    private fun write(
        out: OutputStream,
        bytes: Long,
    ) {
        val chunk = ByteArray(64 * 1024) { it.toByte() }
        var left = bytes
        while (left > 0) {
            val n = minOf(left, chunk.size.toLong()).toInt()
            out.write(chunk, 0, n)
            left -= n
        }
    }

    private fun count(input: InputStream): Long {
        val buffer = ByteArray(8 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return total
            total += n
        }
    }

    @Test
    fun twentyMegabytesStreamThroughASixtyFourKilobytePipe() =
        runBlocking {
            val bytes = 20L * 1024 * 1024
            val r =
                transfer.run(pipe(), 30_000, {
                    write(it, bytes)
                    0
                }, okStatus = 0) { count(it) }
            assertThat(r.partial).isNull()
            assertThat(r.value).isEqualTo(bytes)
            assertThat(r.bytes).isEqualTo(bytes)
            assertThat(r.consumerFinished).isTrue()
        }

    @Test
    fun aConsumerThatStopsEarlyReleasesTheWriter() =
        runBlocking {
            val r =
                transfer.run(pipe(), 30_000, {
                    write(it, 8L * 1024 * 1024)
                    0
                }, okStatus = 0) { it.read(ByteArray(16)) }
            assertThat(r.value).isEqualTo(16)
            // The writer met the closed read end; the dump is honestly not complete.
            assertThat(r.partial).isEqualTo(PartialReason.PRODUCER_FAILED)
            assertThat(r.producer).isInstanceOf(ProducerOutcome.Threw::class.java)
        }

    @Test
    fun aDeadWorkerYieldsAPartialResultWithWhatArrived() =
        runBlocking {
            val r =
                transfer.run(pipe(), 30_000, { out ->
                    write(out, 1000)
                    throw DeadObjectException()
                }, okStatus = 0) { count(it) }
            assertThat(r.partial).isEqualTo(PartialReason.WORKER_DIED)
            assertThat(r.value).isEqualTo(1000L)
        }

    @Test
    fun aStreamWithoutEndTimesOutAndUnblocksTheProducer() =
        runBlocking {
            val r =
                transfer.run(pipe(), 300, { out ->
                    write(out, 100)
                    Thread.sleep(1_000)
                    // The read end is closed by now.
                    write(out, 100)
                    0
                }, okStatus = 0) { count(it) }
            assertThat(r.partial).isEqualTo(PartialReason.TIMEOUT)
            assertThat(r.consumerFinished).isFalse()
        }

    @Test
    fun producerStatusAndConsumerErrorsAreReported() =
        runBlocking {
            val failed =
                transfer.run(pipe(), 30_000, {
                    write(it, 10)
                    2
                }, okStatus = 0) { count(it) }
            assertThat(failed.partial).isEqualTo(PartialReason.PRODUCER_FAILED)
            val consumer =
                transfer.run(pipe(), 30_000, {
                    write(it, 10)
                    0
                }, okStatus = 0) { error("parser bug") }
            assertThat(consumer.partial).isEqualTo(PartialReason.CONSUMER_ERROR)
            val read =
                transfer.run(pipe(), 30_000, {
                    write(it, 10)
                    0
                }, okStatus = 0) { throw IOException("read failed") }
            assertThat(read.partial).isEqualTo(PartialReason.READ_ERROR)
        }

    @Test
    fun aProducerThatNeverReturnsDoesNotHoldTheCallerPastTheGrace() =
        runBlocking {
            val release = CountDownLatch(1)
            val shortGrace = StreamTransfer(Dispatchers.IO, ::now, graceMs = 200)
            val started = now()
            val r =
                shortGrace.run(pipe(), 300, {
                    // A service stuck on a lock: no writes, no return, deaf to the closed read end.
                    release.await(30, TimeUnit.SECONDS)
                    0
                }, okStatus = 0) { count(it) }
            val waited = now() - started
            release.countDown()
            assertThat(r.partial).isEqualTo(PartialReason.TIMEOUT)
            assertThat(r.producer).isNull()
            assertThat(waited).isLessThan(5_000L)
        }

    @Test
    fun aConsumerStuckInALoopDoesNotHoldTheCallerPastTheGrace() =
        runBlocking {
            val release = CountDownLatch(1)
            val shortGrace = StreamTransfer(Dispatchers.IO, ::now, graceMs = 200)
            val started = now()
            val r =
                shortGrace.run(pipe(), 300, {
                    write(it, 10)
                    0
                }, okStatus = 0) {
                    // A parser bug that spins without reading: closing the input cannot wake it.
                    release.await(30, TimeUnit.SECONDS)
                    0L
                }
            val waited = now() - started
            release.countDown()
            assertThat(r.partial).isEqualTo(PartialReason.TIMEOUT)
            assertThat(r.value).isNull()
            assertThat(waited).isLessThan(5_000L)
        }

    /** Audit PERF-004: after a timeout, a producer stuck in its call gets a short grace, not the full one. */
    @Test
    fun aTimedOutProducerGetsOnlyAShortGrace() =
        runBlocking {
            val release = CountDownLatch(1)
            val started = now()
            val r =
                transfer.run(pipe(), 300, {
                    release.await(30, TimeUnit.SECONDS)
                    0
                }, okStatus = 0) { count(it) }
            val waited = now() - started
            release.countDown()
            assertThat(r.partial).isEqualTo(PartialReason.TIMEOUT)
            // 300 ms, then at most the 250 ms grace, with room for a slow machine; the full grace is 5 s.
            assertThat(waited).isLessThan(2_000L)
        }

    private class CountingPipe : PipePair<Unit> {
        var sinkClosed = 0
        override val input: InputStream = ByteArrayInputStream(ByteArray(0))
        override val sink: Unit = Unit

        override fun closeSink() {
            sinkClosed += 1
        }

        override fun closeInput() = Unit
    }

    /** Audit CONC-005: a producer cancelled before its thread took it never ran the code that closed the sink. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aTransferCancelledBeforeItsProducerStartsClosesTheSink() =
        runTest {
            // The transfer's own dispatcher, held: what is launched on it waits.
            val held = TestCoroutineScheduler()
            val pipe = CountingPipe()
            var produced = 0
            val caller =
                launch {
                    StreamTransfer(StandardTestDispatcher(held), { currentTime }).run(pipe, 1_000, {
                        produced += 1
                        0
                    }, okStatus = 0) { it.readBytes() }
                }
            runCurrent()
            caller.cancel()
            advanceUntilIdle()
            held.advanceUntilIdle()

            assertThat(produced).isEqualTo(0)
            assertThat(pipe.sinkClosed).isEqualTo(1)
        }

    @Test
    fun theSinkIsClosedOnceWhenTheProducerRan() =
        runBlocking {
            val pipe = CountingPipe()
            val r = transfer.run(pipe, 5_000, { 0 }, okStatus = 0) { it.readBytes() }
            assertThat(r.partial).isNull()
            assertThat(pipe.sinkClosed).isEqualTo(1)
        }
}
