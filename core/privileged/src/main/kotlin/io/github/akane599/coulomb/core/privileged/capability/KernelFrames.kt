// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.privileged.capability

import io.github.akane599.coulomb.core.model.kernel.KernelPaths

/**
 * What a probe needs from the worker's bulk kernel-file stream: which paths read and which failed,
 * by errno. The collector parses the stream fully (`KernelFilesParser` in `:core:parsing`); this
 * module cannot depend on it, so the probe walks the frames itself. Never throws.
 */
internal class KernelFrames private constructor(
    val read: Set<String>,
    val failed: Map<String, Int>,
    val ended: Boolean,
) {
    fun readIn(dir: String): List<String> = read.filter { it.startsWith("$dir/") }

    fun failedIn(dir: String): Map<String, Int> = failed.filterKeys { it == dir || it.startsWith("$dir/") }

    internal companion object {
        fun walk(bytes: ByteArray): KernelFrames {
            val walker = Walker(bytes)
            var more = true
            while (more) more = walker.step()
            return KernelFrames(walker.read, walker.failed, walker.ended)
        }

        private class Walker(
            private val bytes: ByteArray,
        ) {
            val read = LinkedHashSet<String>()
            val failed = LinkedHashMap<String, Int>()
            var ended = false
            private var at = 0

            /**
             * Steps over [path]'s payload of [length] bytes and its line break. The path is read only
             * when both are there: the worker writes the header first, so a cut can fall after it
             * (audit BUG-006). In `Long`, so that no length overflows.
             */
            private fun payload(
                path: String,
                length: Int,
            ): Boolean {
                val end = at.toLong() + length
                if (end >= bytes.size || bytes[end.toInt()] != NEWLINE) return false
                read += path
                at = end.toInt() + 1
                return true
            }

            /** Reads one frame; false at the end or at anything that is not a frame. */
            fun step(): Boolean {
                val newline = bytes.indexOf(at, NEWLINE)
                if (ended || newline < 0) return false
                val header = String(bytes, at, newline - at, Charsets.US_ASCII)
                at = newline + 1
                val space = header.lastIndexOf(' ')
                val path = if (space > 1) header.substring(1, space) else ""
                val number = if (space > 1) header.substring(space + 1).toIntOrNull() else null
                return when {
                    header == END -> {
                        ended = true
                        false
                    }

                    header.startsWith("@") && number != null && number >= 0 -> {
                        payload(path, number)
                    }

                    header.startsWith("!") && path.isNotEmpty() -> {
                        failed[path] = number ?: -1
                        true
                    }

                    else -> {
                        false
                    }
                }
            }
        }

        private fun ByteArray.indexOf(
            from: Int,
            b: Byte,
        ): Int {
            for (i in from until size) if (this[i] == b) return i
            return -1
        }

        private const val END = "#end"
        private const val NEWLINE = '\n'.code.toByte()

        /** Every kernel-file source, for one probe read. */
        const val ALL_SOURCES: Int = KernelPaths.READ_SUSPEND_STATS or KernelPaths.READ_WAKEUP_REASONS or KernelPaths.READ_WAKEUP_SOURCES
    }
}
