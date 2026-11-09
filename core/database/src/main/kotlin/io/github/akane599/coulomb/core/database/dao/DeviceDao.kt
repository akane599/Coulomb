// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import io.github.akane599.coulomb.core.database.entity.BootEntity
import io.github.akane599.coulomb.core.database.entity.CapabilityEntity
import io.github.akane599.coulomb.core.database.entity.DeviceProfileEntity
import io.github.akane599.coulomb.core.database.entity.UidMetaEntity
import io.github.akane599.coulomb.core.database.entity.UidPackageEntity
import kotlinx.coroutines.flow.Flow

/** Device profile, boots, capabilities and UID names: the rows the other tables are read against. */
@Dao
public abstract class DeviceDao {
    @Upsert
    public abstract suspend fun upsertProfile(profile: DeviceProfileEntity)

    @Query("SELECT * FROM device_profile WHERE fingerprint = :fingerprint")
    public abstract suspend fun profile(fingerprint: String): DeviceProfileEntity?

    @Query("SELECT * FROM device_profile WHERE fingerprint = :fingerprint")
    public abstract fun observeProfile(fingerprint: String): Flow<DeviceProfileEntity?>

    /**
     * The design capacity read from the device, written only while the build has none, so it never
     * replaces a stored value, the owner's included (ADR-0042 §1). Only these columns are written.
     */
    @Query(
        "UPDATE device_profile SET designCapacityMah = :mah, designCapacitySource = :source " +
            "WHERE fingerprint = :fingerprint AND designCapacityMah IS NULL",
    )
    public abstract suspend fun fillDesignCapacity(
        fingerprint: String,
        mah: Int,
        source: String,
    )

    /** The owner's design capacity (ADR-0044 §5): replaces whatever is stored for the build. */
    @Query("UPDATE device_profile SET designCapacityMah = :mah, designCapacitySource = :source WHERE fingerprint = :fingerprint")
    public abstract suspend fun setDesignCapacity(
        fingerprint: String,
        mah: Int,
        source: String,
    )

    /** A design capacity the owner entered on any build, to carry to a new one (ADR-0044 §5). */
    @Query("SELECT designCapacityMah FROM device_profile WHERE designCapacitySource = :source AND designCapacityMah IS NOT NULL LIMIT 1")
    public abstract suspend fun designCapacityFrom(source: String): Int?

    /**
     * The calibration columns alone (SPEC 6.1), so a calibration result never writes back a design
     * capacity the owner changed meanwhile.
     */
    @Query(
        "UPDATE device_profile SET currentSign = :sign, currentScale = :scale, calibrationQuality = :quality, " +
            "calibratedAtWall = :calibratedAtWall, chargeCounterReliable = :reliable WHERE fingerprint = :fingerprint",
    )
    public abstract suspend fun setCalibration(
        fingerprint: String,
        sign: Int?,
        scale: Int?,
        quality: Double?,
        calibratedAtWall: Long?,
        reliable: Boolean?,
    )

    /**
     * The boot Coulomb is running in, numbered on first sight (ADR-0015). It is the latest boot of
     * this device when [rawBootCount] matches it and elapsed time has not gone back; otherwise a
     * new boot numbered [rawBootCount], or one past the highest stored boot when that is not higher.
     * An unreadable count always starts a new boot: splitting one boot in two keeps rows in order,
     * while merging two would not.
     */
    @Transaction
    public open suspend fun enterBoot(
        rawBootCount: Int?,
        fingerprint: String,
        wallMs: Long,
        elapsedNs: Long,
    ): BootEntity {
        val latest = latestLocalBoot()
        if (latest != null && latest.isStill(rawBootCount, elapsedNs)) return latest
        val highest = highestBootSeq()
        val bootSeq =
            when {
                highest == null -> rawBootCount ?: 1
                rawBootCount != null && rawBootCount > highest -> rawBootCount
                else -> highest + 1
            }
        return BootEntity(bootSeq, rawBootCount, fingerprint, wallMs, elapsedNs, null, imported = false)
            .also { insertBoot(it) }
    }

    /** The same boot: its count was read, matches, and elapsed time has not gone back. */
    private fun BootEntity.isStill(
        rawBootCount: Int?,
        elapsedNs: Long,
    ): Boolean = rawBootCount != null && this.rawBootCount == rawBootCount && elapsedNs >= firstElapsedNs

    @Query("SELECT * FROM boot WHERE bootSeq = :bootSeq")
    public abstract suspend fun boot(bootSeq: Int): BootEntity?

    /** Every boot, oldest first: the collector turns "N days ago" into a retention cut with them (ADR-0015). */
    @Query("SELECT * FROM boot ORDER BY bootSeq")
    public abstract suspend fun boots(): List<BootEntity>

    /**
     * Each boot's last sighting: the latest elapsed time of a snapshot or sample stored in it. The
     * boot ran at least that long; retention counts back with it. Only the tables with a
     * `(bootSeq, elapsedNs)` index are read: aggregates and history events would cost a full scan
     * and could only move the bound later. Snapshot rows are kept for good, so old boots keep one.
     */
    @Query(
        "SELECT bootSeq, MAX(lastNs) AS lastElapsedNs FROM (" +
            "SELECT bootSeq, MAX(elapsedNs) AS lastNs FROM snapshot GROUP BY bootSeq " +
            "UNION ALL SELECT bootSeq, MAX(elapsedNs) FROM battery_sample GROUP BY bootSeq" +
            ") GROUP BY bootSeq",
    )
    public abstract suspend fun bootsLastSeen(): List<BootLastSeen>

    /** Records how this boot's history clock maps to elapsed time (ADR-0014). */
    @Query("UPDATE boot SET historyOffsetMs = :offsetMs WHERE bootSeq = :bootSeq")
    public abstract suspend fun setHistoryOffset(
        bootSeq: Int,
        offsetMs: Long,
    )

    @Upsert
    public abstract suspend fun upsertCapabilities(capabilities: List<CapabilityEntity>)

    @Query("SELECT * FROM capability ORDER BY id")
    public abstract fun observeCapabilities(): Flow<List<CapabilityEntity>>

    @Upsert
    public abstract suspend fun upsertUidMeta(meta: List<UidMetaEntity>)

    @Query("SELECT * FROM uid_meta WHERE uid = :uid")
    public abstract suspend fun uidMeta(uid: Int): UidMetaEntity?

    @Query("SELECT * FROM uid_meta ORDER BY uid")
    public abstract fun observeUidMeta(): Flow<List<UidMetaEntity>>

    /** Notes that [uid] belonged to [packages] at [wall], widening each pair's seen range. */
    @Transaction
    public open suspend fun recordUidPackages(
        uid: Int,
        packages: Collection<String>,
        wall: Long,
    ) {
        insertUidPackages(packages.map { UidPackageEntity(uid, it, wall, wall) })
        packages.forEach { widenUidPackage(uid, it, wall) }
    }

    /** Every package [uid] has belonged to, the most recently seen first. */
    @Query("SELECT * FROM uid_package WHERE uid = :uid ORDER BY lastSeenWall DESC")
    public abstract suspend fun uidPackages(uid: Int): List<UidPackageEntity>

    @Query("SELECT * FROM boot WHERE imported = 0 ORDER BY bootSeq DESC LIMIT 1")
    protected abstract suspend fun latestLocalBoot(): BootEntity?

    @Query("SELECT MAX(bootSeq) FROM boot")
    protected abstract suspend fun highestBootSeq(): Int?

    @Insert
    protected abstract suspend fun insertBoot(boot: BootEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertUidPackages(rows: List<UidPackageEntity>)

    @Query(
        "UPDATE uid_package SET firstSeenWall = MIN(firstSeenWall, :wall), lastSeenWall = MAX(lastSeenWall, :wall) " +
            "WHERE uid = :uid AND packageName = :packageName",
    )
    protected abstract suspend fun widenUidPackage(
        uid: Int,
        packageName: String,
        wall: Long,
    )
}

/** The latest elapsed time stored for [bootSeq] ([DeviceDao.bootsLastSeen]). */
public data class BootLastSeen(
    val bootSeq: Int,
    val lastElapsedNs: Long,
)
