// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.android

import android.content.Context
import android.os.Build
import android.provider.Settings
import io.github.akane599.coulomb.core.collect.engine.BlobStore
import io.github.akane599.coulomb.core.collect.engine.DeviceIdentity
import java.io.File
import java.io.IOException
import java.time.ZoneId
import java.util.zip.GZIPOutputStream

/** The running device, from `Build` and `Settings.Global.BOOT_COUNT` (SPEC 7.2). */
internal class AndroidDeviceIdentity(
    private val context: Context,
) : DeviceIdentity {
    override val fingerprint: String = Build.FINGERPRINT
    override val model: String = Build.MODEL
    override val oem: String = Build.MANUFACTURER
    override val sdkInt: Int = Build.VERSION.SDK_INT
    override val sdkIntFull: Int = Build.VERSION.SDK_INT_FULL

    override fun bootCount(): Int? =
        runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }
            .getOrNull()

    override fun zone(): ZoneId = ZoneId.systemDefault()
}

/**
 * Blobs as gzip files under `files/snapshots/` (ADR-0015: the backup rules exclude the folder).
 * A blob's parts share its base name: `<base>.<part>.gz`.
 */
internal class FileBlobStore(
    private val filesDir: File,
) : BlobStore {
    override fun write(
        baseName: String,
        parts: Map<String, ByteArray>,
    ): String? {
        val base = File(filesDir, baseName)
        base.parentFile?.mkdirs()
        val written =
            parts.count { (part, bytes) ->
                try {
                    GZIPOutputStream(partFile(base, part).outputStream().buffered()).use { it.write(bytes) }
                    true
                } catch (_: IOException) {
                    partFile(base, part).delete()
                    false
                }
            }
        return baseName.takeIf { written > 0 }
    }

    override fun delete(baseNames: Collection<String>) {
        if (baseNames.isEmpty()) return
        val names = baseNames.toSet()
        folder()
            .listFiles()
            .orEmpty()
            .filter { baseOf(it) in names }
            .forEach { it.delete() }
    }

    override fun list(): List<Pair<String, Long>> =
        folder()
            .listFiles()
            .orEmpty()
            .groupBy(::baseOf)
            .map { (base, files) -> Triple(base, files.sumOf { it.length() }, files.minOf { it.lastModified() }) }
            .sortedBy { it.third }
            .map { it.first to it.second }

    private fun folder() = File(filesDir, FOLDER)

    private fun partFile(
        base: File,
        part: String,
    ) = File(base.parentFile, "${base.name}.$part.gz")

    /** `snapshots/12-345.s5.pb.gz` → `snapshots/12-345`. */
    private fun baseOf(file: File): String = "$FOLDER/${file.name.substringBefore('.')}"

    private companion object {
        const val FOLDER = "snapshots"
    }
}
