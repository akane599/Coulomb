// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.privileged.worker

import io.github.akane599.coulomb.core.model.kernel.KernelPaths
import io.github.akane599.coulomb.core.privileged.system.Sysfs
import java.io.OutputStream

/**
 * Writes the bulk kernel-file stream that `KernelFilesParser` reads (ADR-0029 §2): `boot_id` first,
 * then S25, S26 and S9 as the [sources] bits ask, each file framed with its length, each failure
 * with its errno, then `#end`. Paths are fixed here; nothing comes from the caller but the bits.
 */
internal class KernelFileWriter(
    private val read: (String) -> Sysfs.Read,
    private val list: (String) -> Sysfs.Listing,
    private val sink: OutputStream,
) {
    fun write(sources: Int) {
        file(KernelPaths.BOOT_ID)
        if (sources and KernelPaths.READ_SUSPEND_STATS != 0) directory(KernelPaths.SUSPEND_STATS)
        if (sources and KernelPaths.READ_WAKEUP_REASONS != 0) {
            file("${KernelPaths.WAKEUP_REASONS}/${KernelPaths.LAST_RESUME_REASON}")
            file("${KernelPaths.WAKEUP_REASONS}/${KernelPaths.LAST_SUSPEND_TIME}")
        }
        if (sources and KernelPaths.READ_WAKEUP_SOURCES != 0) wakeupSources()
        sink.write(END)
        sink.flush()
    }

    /** Every entry of [dir], as files. */
    private fun directory(dir: String) {
        when (val listing = list(dir)) {
            is Sysfs.Listing.Ok -> listing.names.forEach { file("$dir/$it") }
            is Sysfs.Listing.Failed -> error(dir, listing.errno)
        }
    }

    /** Each `wakeupN` entry's fixed attribute files (drivers/base/power/wakeup_stats.c `wakeup_source_attrs`). */
    private fun wakeupSources() {
        val dir = KernelPaths.WAKEUP_CLASS
        when (val listing = list(dir)) {
            is Sysfs.Listing.Ok -> {
                listing.names.filter(::isWakeupEntry).forEach { entry ->
                    KernelPaths.WAKEUP_ATTRIBUTES.forEach { file("$dir/$entry/$it") }
                }
            }

            is Sysfs.Listing.Failed -> {
                error(dir, listing.errno)
            }
        }
    }

    private fun file(path: String) {
        when (val r = read(path)) {
            is Sysfs.Read.Ok -> {
                sink.write("@$path ${r.bytes.size}\n".toByteArray(Charsets.US_ASCII))
                sink.write(r.bytes)
                sink.write(NEWLINE)
            }

            is Sysfs.Read.Failed -> {
                error(path, r.errno)
            }
        }
    }

    private fun error(
        path: String,
        errno: Int,
    ) {
        sink.write("!$path $errno\n".toByteArray(Charsets.US_ASCII))
    }

    internal companion object {
        private val END = "#end\n".toByteArray(Charsets.US_ASCII)
        private const val NEWLINE = '\n'.code

        private val WAKEUP_ENTRY = Regex("wakeup[0-9]{1,9}")

        fun isWakeupEntry(name: String): Boolean = WAKEUP_ENTRY.matches(name)
    }
}
