// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.privileged.system

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs one allowlisted command as an argv array: no shell, so nothing in an argument is ever
 * interpreted. Used only inside the worker process (SPEC 4.3). Output beyond [maxBytes] is dropped
 * and reported as truncated; a command still running after [timeoutMs] is killed.
 */
internal class ShellRunner(
    private val timeoutMs: Long,
) {
    class Result(
        val exitCode: Int,
        val stdout: ByteArray,
        val stderr: String,
        val timedOut: Boolean,
        val truncated: Boolean,
    ) {
        val ok: Boolean get() = exitCode == 0 && !timedOut
        val stdoutText: String get() = stdout.toString(Charsets.UTF_8)
    }

    /** Writes stdout to [sink] when given, else collects it into [Result.stdout]. */
    fun run(
        argv: List<String>,
        sink: OutputStream?,
        maxBytes: Long,
    ): Result {
        val process = ProcessBuilder(argv).redirectInput(ProcessBuilder.Redirect.from(java.io.File("/dev/null"))).start()
        val stderr = ByteArrayOutputStream()
        val errThread =
            Thread { copyCapped(process.errorStream, stderr, MAX_STDERR_BYTES) }.apply {
                isDaemon = true
                start()
            }
        val killed = AtomicBoolean(false)
        val watchdog =
            Thread {
                try {
                    if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                        killed.set(true)
                        process.destroyForcibly()
                    }
                } catch (_: InterruptedException) {
                    // Command finished first.
                }
            }.apply {
                isDaemon = true
                start()
            }
        val collected = if (sink == null) ByteArrayOutputStream() else null
        val truncated = copyCapped(process.inputStream, sink ?: collected!!, maxBytes)
        val exit = process.waitFor()
        watchdog.interrupt()
        val timedOut = killed.get()
        errThread.join(JOIN_MS)
        return Result(
            exit,
            collected?.toByteArray() ?: ByteArray(0),
            stderr.toString(Charsets.UTF_8).take(MAX_STDERR_CHARS),
            timedOut,
            truncated,
        )
    }

    private companion object {
        const val MAX_STDERR_BYTES = 16L * 1024
        const val MAX_STDERR_CHARS = 2048
        const val JOIN_MS = 1000L
        const val BUFFER = 64 * 1024

        /** Copies until EOF; returns true when bytes beyond [max] were discarded. */
        fun copyCapped(
            input: InputStream,
            output: OutputStream,
            max: Long,
        ): Boolean {
            val buf = ByteArray(BUFFER)
            var total = 0L
            var dropped = false
            input.use {
                while (true) {
                    val n = it.read(buf)
                    if (n < 0) break
                    val keep = minOf(n.toLong(), max - total).toInt()
                    if (keep > 0) output.write(buf, 0, keep)
                    if (keep < n) dropped = true
                    total += keep
                }
            }
            output.flush()
            return dropped
        }
    }
}
