// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import io.github.akane599.coulomb.core.database.dao.DeviceDao
import io.github.akane599.coulomb.core.database.dao.HistoryDao
import io.github.akane599.coulomb.core.database.dao.InsightDao
import io.github.akane599.coulomb.core.database.dao.SampleDao
import io.github.akane599.coulomb.core.database.dao.SessionDao
import io.github.akane599.coulomb.core.database.dao.SnapshotDao
import io.github.akane599.coulomb.core.database.dao.WindowDao
import io.github.akane599.coulomb.core.database.entity.ActionLogEntity
import io.github.akane599.coulomb.core.database.entity.AlarmStatEntity
import io.github.akane599.coulomb.core.database.entity.BatterySampleEntity
import io.github.akane599.coulomb.core.database.entity.BootEntity
import io.github.akane599.coulomb.core.database.entity.CapabilityEntity
import io.github.akane599.coulomb.core.database.entity.CapacityEstimateEntity
import io.github.akane599.coulomb.core.database.entity.ChargeDetailEntity
import io.github.akane599.coulomb.core.database.entity.ChargerProfileEntity
import io.github.akane599.coulomb.core.database.entity.DetailCodes
import io.github.akane599.coulomb.core.database.entity.DeviceProfileEntity
import io.github.akane599.coulomb.core.database.entity.DeviceStatEntity
import io.github.akane599.coulomb.core.database.entity.HealthSummaryEntity
import io.github.akane599.coulomb.core.database.entity.HistoryCodes
import io.github.akane599.coulomb.core.database.entity.HistoryEventEntity
import io.github.akane599.coulomb.core.database.entity.HistoryGapEntity
import io.github.akane599.coulomb.core.database.entity.HistoryTagEntity
import io.github.akane599.coulomb.core.database.entity.InsightEntity
import io.github.akane599.coulomb.core.database.entity.JobStatEntity
import io.github.akane599.coulomb.core.database.entity.KernelLogEventEntity
import io.github.akane599.coulomb.core.database.entity.KernelReadEntity
import io.github.akane599.coulomb.core.database.entity.NetStatEntity
import io.github.akane599.coulomb.core.database.entity.RailEnergyEntity
import io.github.akane599.coulomb.core.database.entity.ReferenceEntity
import io.github.akane599.coulomb.core.database.entity.SampleAggregateEntity
import io.github.akane599.coulomb.core.database.entity.SessionEntity
import io.github.akane599.coulomb.core.database.entity.SnapshotEntity
import io.github.akane599.coulomb.core.database.entity.StatKeyEntity
import io.github.akane599.coulomb.core.database.entity.UidComponentPowerEntity
import io.github.akane599.coulomb.core.database.entity.UidMetaEntity
import io.github.akane599.coulomb.core.database.entity.UidPackageEntity
import io.github.akane599.coulomb.core.database.entity.UidPowerEntity
import io.github.akane599.coulomb.core.database.entity.UidStatEntity
import io.github.akane599.coulomb.core.database.entity.WakeStatEntity
import io.github.akane599.coulomb.core.database.entity.WakeTagEntity
import io.github.akane599.coulomb.core.database.entity.WakeupSourceStatEntity
import io.github.akane599.coulomb.core.database.entity.WindowComponentEntity
import io.github.akane599.coulomb.core.database.entity.WindowResultEntity
import io.github.akane599.coulomb.core.database.entity.WindowStatEntity
import io.github.akane599.coulomb.core.database.entity.WindowUidEntity
import io.github.akane599.coulomb.core.database.entity.WindowWakeEntity

/**
 * The SPEC 8 schema (ADR-0015, ADR-0021, ADR-0029, ADR-0035). Schemas are exported to `core/database/schemas` and
 * committed; a version bump needs a migration and a migration test (SPEC 8). Enums are stored by
 * name, except the position codes of the history and window detail tables ([HistoryCodes],
 * [DetailCodes]).
 */
@Database(
    version = 4,
    exportSchema = true,
    entities = [
        DeviceProfileEntity::class,
        BootEntity::class,
        CapabilityEntity::class,
        BatterySampleEntity::class,
        SampleAggregateEntity::class,
        SessionEntity::class,
        ChargerProfileEntity::class,
        ChargeDetailEntity::class,
        CapacityEstimateEntity::class,
        HealthSummaryEntity::class,
        SnapshotEntity::class,
        UidPowerEntity::class,
        UidComponentPowerEntity::class,
        UidMetaEntity::class,
        WakeStatEntity::class,
        AlarmStatEntity::class,
        JobStatEntity::class,
        NetStatEntity::class,
        RailEnergyEntity::class,
        UidStatEntity::class,
        DeviceStatEntity::class,
        KernelReadEntity::class,
        WakeupSourceStatEntity::class,
        KernelLogEventEntity::class,
        UidPackageEntity::class,
        ReferenceEntity::class,
        WindowResultEntity::class,
        WindowUidEntity::class,
        WindowWakeEntity::class,
        WindowStatEntity::class,
        WindowComponentEntity::class,
        StatKeyEntity::class,
        WakeTagEntity::class,
        HistoryEventEntity::class,
        HistoryTagEntity::class,
        HistoryGapEntity::class,
        InsightEntity::class,
        ActionLogEntity::class,
    ],
)
public abstract class CoulombDatabase : RoomDatabase() {
    public abstract fun deviceDao(): DeviceDao

    public abstract fun sampleDao(): SampleDao

    public abstract fun sessionDao(): SessionDao

    public abstract fun snapshotDao(): SnapshotDao

    public abstract fun windowDao(): WindowDao

    public abstract fun historyDao(): HistoryDao

    public abstract fun insightDao(): InsightDao

    public companion object {
        public const val FILE_NAME: String = "coulomb.db"

        /** The app's database in its no-backup directory: battery data stays on the device (SPEC 14). */
        @Suppress("SpreadOperator") // One small array, copied once when the database is built.
        public fun build(context: Context): CoulombDatabase =
            Room
                .databaseBuilder(context, CoulombDatabase::class.java, context.noBackupFilesDir.resolve(FILE_NAME).path)
                .addMigrations(*Migrations.ALL)
                .build()
    }
}
