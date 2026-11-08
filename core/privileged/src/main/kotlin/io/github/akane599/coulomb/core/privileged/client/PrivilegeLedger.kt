// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.privileged.client

import android.util.AtomicFile
import java.io.File
import java.util.Properties

/**
 * What Coulomb has verified before, so a missing grant after an OS update reads as "revoked"
 * ("Re-enable deep stats", SPEC 4.5) rather than "never granted". Moves into the P2 Room
 * `DeviceProfile` / `Capability` rows; until then a small file in no-backup storage.
 */
internal data class LedgerRecord(
    val coreGrantsVerified: Boolean = false,
    /** `Build.FINGERPRINT` at the last successful verification. */
    val verifiedFingerprint: String? = null,
    /** The manager (package and version, or Sui) that last started the worker (ADR-0029 §6). */
    val boundManager: String? = null,
)

internal interface PrivilegeLedger {
    fun read(): LedgerRecord

    fun write(record: LedgerRecord)
}

internal class FilePrivilegeLedger(
    dir: File,
) : PrivilegeLedger {
    private val file = AtomicFile(File(dir, "privilege-ledger.properties"))

    // AtomicFile.openRead() deletes a pending "<name>.new" when the base file exists (core/java/android/
    // util/AtomicFile.java, openRead(): android-16.0.0_r4 L255-258, android-17.0.0_r1 L253-256), so a read that
    // overlaps a write makes finishWrite()'s rename fail, and AtomicFile only logs that. Seen on the
    // API 37 emulator (a refresh on resume racing the fingerprint drill). Reads and writes are serialised.
    private val lock = Any()

    override fun read(): LedgerRecord =
        synchronized(lock) {
            runCatching {
                val p = Properties().apply { file.openRead().use { load(it) } }
                LedgerRecord(p.getProperty(KEY_VERIFIED) == "true", p.getProperty(KEY_FINGERPRINT), p.getProperty(KEY_BOUND_MANAGER))
            }.getOrDefault(LedgerRecord())
        }

    override fun write(record: LedgerRecord) {
        val p = Properties()
        p.setProperty(KEY_VERIFIED, record.coreGrantsVerified.toString())
        record.verifiedFingerprint?.let { p.setProperty(KEY_FINGERPRINT, it) }
        record.boundManager?.let { p.setProperty(KEY_BOUND_MANAGER, it) }
        synchronized(lock) {
            val out = file.startWrite()
            try {
                p.store(out, null)
                file.finishWrite(out)
            } catch (e: java.io.IOException) {
                file.failWrite(out)
                throw e
            }
        }
    }

    private companion object {
        const val KEY_VERIFIED = "core_grants_verified"
        const val KEY_FINGERPRINT = "verified_fingerprint"
        const val KEY_BOUND_MANAGER = "bound_manager"
    }
}
