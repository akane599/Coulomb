// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.privileged.system

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Bounded sysfs reads for the worker (S9, S23). Paths are built from an allowlisted root plus
 * segments that `Validation.sysfsSegments` accepted. Errors keep their errno so the app can tell
 * "needs root" (EACCES, SELinux or DAC) from "not present" (ENOENT) (SPEC 4.5).
 */
internal object Sysfs {
    sealed interface Read {
        class Ok(
            val bytes: ByteArray,
        ) : Read

        class Failed(
            val errno: Int,
            val message: String,
        ) : Read
    }

    fun read(
        path: String,
        maxBytes: Int,
    ): Read =
        try {
            val fd = Os.open(path, OsConstants.O_RDONLY, 0)
            try {
                Read.Ok(readAtMost(FileInputStream(fd), maxBytes))
            } finally {
                Os.close(fd)
            }
        } catch (e: ErrnoException) {
            Read.Failed(e.errno, e.message.orEmpty())
        } catch (e: IOException) {
            // read(2) errors on attributes surface here, for example EACCES on some vendor nodes.
            val errno = (e.cause as? ErrnoException)?.errno ?: OsConstants.EIO
            Read.Failed(errno, e.message.orEmpty())
        }

    private fun readAtMost(
        input: InputStream,
        maxBytes: Int,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(minOf(maxBytes, BUFFER))
        var n = 0
        while (out.size() < maxBytes && n >= 0) {
            n = input.read(buf, 0, minOf(buf.size, maxBytes - out.size()))
            if (n > 0) out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    sealed interface Listing {
        class Ok(
            val names: List<String>,
        ) : Listing

        class Failed(
            val errno: Int,
            val message: String,
        ) : Listing
    }

    fun list(
        path: String,
        maxEntries: Int,
    ): Listing {
        try {
            Os.access(path, OsConstants.R_OK or OsConstants.X_OK)
        } catch (e: ErrnoException) {
            return Listing.Failed(e.errno, e.message.orEmpty())
        }
        val names = File(path).list() ?: return Listing.Failed(OsConstants.EACCES, "readdir failed: $path")
        return Listing.Ok(names.sorted().take(maxEntries))
    }

    private const val BUFFER = 8 * 1024
}
