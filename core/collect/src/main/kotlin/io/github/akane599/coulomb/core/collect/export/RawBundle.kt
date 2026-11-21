// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.export

import io.github.akane599.coulomb.core.privileged.BatteryStatsMode
import io.github.akane599.coulomb.core.privileged.DumpResult
import io.github.akane599.coulomb.core.privileged.PrivilegedAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** How much of one dump the raw bundle holds. */
public enum class RawPart {
    COMPLETE,

    /** Cut short: the dump timed out, its producer died, or it was larger than the bundle takes. */
    PARTIAL,

    /** Not read at all: Coulomb has no access to it now. */
    MISSING,
}

/** What a raw bundle ended up holding. */
public data class RawBundleResult(
    val stats: RawPart,
    val history: RawPart,
) {
    /** Neither dump could be read: the file holds only its README and is not worth keeping. */
    val empty: Boolean get() = stats == RawPart.MISSING && history == RawPart.MISSING
}

/** What the README says of the phone and the build that wrote the bundle. */
public data class RawOrigin(
    val writtenAtWallMs: Long,
    val appVersionName: String,
    val fingerprint: String,
    val sdkInt: Int,
)

/**
 * SPEC 14.5's raw bundle (ADR-0062 §8): the two batterystats dumps Coulomb reads, unparsed, in a
 * zip. `-c` is the current statistics in checkin format and never touches the saved checkin file
 * (ADR-0005); `--history` is the history as text. Both are on SPEC 19.1's list. Nothing is parsed,
 * redacted or kept: the dumps go through files in [write]'s scratch folder, which are deleted when
 * it returns.
 */
@Singleton
public class RawBundle internal constructor(
    private val dump: suspend (mode: BatteryStatsMode, into: File) -> RawPart,
) {
    @Inject
    public constructor(access: PrivilegedAccess) : this({ mode, into -> access.dumpTo(mode, into) })

    /**
     * Writes the bundle to [out] and closes it. [scratch] is a folder of the cache directory that
     * this call may create, fill and empty. Throws [java.io.IOException] when [out] cannot be written.
     */
    public suspend fun write(
        out: OutputStream,
        scratch: File,
        origin: RawOrigin,
    ): RawBundleResult =
        withContext(Dispatchers.IO) {
            scratch.deleteRecursively()
            scratch.mkdirs()
            try {
                val stats = scratch.resolve(STATS)
                val history = scratch.resolve(HISTORY)
                val result = RawBundleResult(dump(BatteryStatsMode.CHECKIN, stats), dump(BatteryStatsMode.HISTORY, history))
                ZipOutputStream(out.buffered()).use { zip ->
                    zip.putNextEntry(ZipEntry(README))
                    zip.write(readme(origin, result).toByteArray())
                    zip.closeEntry()
                    for ((name, file, part) in listOf(Triple(STATS, stats, result.stats), Triple(HISTORY, history, result.history))) {
                        if (part == RawPart.MISSING || !file.isFile) continue
                        zip.putNextEntry(ZipEntry(name))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
                result
            } finally {
                scratch.deleteRecursively()
            }
        }

    private fun readme(
        origin: RawOrigin,
        result: RawBundleResult,
    ): String =
        buildString {
            appendLine("Coulomb raw bundle")
            appendLine()
            appendLine("Written (UTC): ${java.time.Instant.ofEpochMilli(origin.writtenAtWallMs)}")
            appendLine("Coulomb: ${origin.appVersionName}")
            appendLine("Build: ${origin.fingerprint} (SDK ${origin.sdkInt})")
            appendLine()
            appendLine("$STATS: `dumpsys batterystats -c`, the current statistics in checkin format. ${words(result.stats)}")
            appendLine("$HISTORY: `dumpsys batterystats --history`, the battery history as text. ${words(result.history)}")
            appendLine()
            appendLine("Both are exactly what Android printed: nothing was parsed, removed or renamed, so they hold")
            appendLine("the names of installed apps and of what those apps did. Share them with that in mind.")
            appendLine()
            appendLine("Battery Historian reads a full bug report. Whether it accepts these two files by themselves")
            appendLine("has not been verified.")
        }

    private fun words(part: RawPart): String =
        when (part) {
            RawPart.COMPLETE -> "Complete."
            RawPart.PARTIAL -> "Cut short: the dump did not finish, so the end of the file is missing."
            RawPart.MISSING -> "Not in this bundle: Coulomb could not read it."
        }

    internal companion object {
        const val README = "README.txt"
        const val STATS = "batterystats-checkin.txt"
        const val HISTORY = "batterystats-history.txt"

        /** A history of weeks is tens of megabytes; past this the rest is dropped and the part reads as cut short. */
        const val MAX_BYTES = 256L * 1024 * 1024
        const val TIMEOUT_MS = 60_000L
        private const val BUFFER = 64 * 1024

        /**
         * Copies [input] to [file] up to [max] bytes, reading on to the end so the producer is not
         * left blocked on a full pipe; true when all of it fit.
         */
        fun copyCapped(
            input: InputStream,
            file: File,
            max: Long = MAX_BYTES,
        ): Boolean {
            var written = 0L
            var whole = true
            val buffer = ByteArray(BUFFER)
            // A second route's attempt starts the file again.
            file.outputStream().use { out ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    val room = (max - written).coerceAtMost(read.toLong()).toInt()
                    if (room > 0) out.write(buffer, 0, room)
                    written += room
                    if (room < read) whole = false
                }
            }
            return whole
        }
    }
}

private suspend fun PrivilegedAccess.dumpTo(
    mode: BatteryStatsMode,
    into: File,
): RawPart {
    val result = dumpBatteryStats<Boolean>(mode, timeoutMs = RawBundle.TIMEOUT_MS) { RawBundle.copyCapped(it, into) }
    return when (result) {
        is DumpResult.Complete -> if (result.value) RawPart.COMPLETE else RawPart.PARTIAL
        is DumpResult.Partial -> if (into.length() > 0) RawPart.PARTIAL else RawPart.MISSING
        is DumpResult.Unavailable -> RawPart.MISSING
    }
}
