// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.database.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.akane599.coulomb.core.database.CoulombDatabase
import io.github.akane599.coulomb.core.database.dao.DeviceDao
import io.github.akane599.coulomb.core.database.dao.HistoryDao
import io.github.akane599.coulomb.core.database.dao.InsightDao
import io.github.akane599.coulomb.core.database.dao.SampleDao
import io.github.akane599.coulomb.core.database.dao.SessionDao
import io.github.akane599.coulomb.core.database.dao.SnapshotDao
import io.github.akane599.coulomb.core.database.dao.WindowDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
public object DatabaseModule {
    @Provides
    @Singleton
    public fun database(
        @ApplicationContext context: Context,
    ): CoulombDatabase = CoulombDatabase.build(context)

    @Provides
    public fun deviceDao(db: CoulombDatabase): DeviceDao = db.deviceDao()

    @Provides
    public fun sampleDao(db: CoulombDatabase): SampleDao = db.sampleDao()

    @Provides
    public fun sessionDao(db: CoulombDatabase): SessionDao = db.sessionDao()

    @Provides
    public fun snapshotDao(db: CoulombDatabase): SnapshotDao = db.snapshotDao()

    @Provides
    public fun windowDao(db: CoulombDatabase): WindowDao = db.windowDao()

    @Provides
    public fun historyDao(db: CoulombDatabase): HistoryDao = db.historyDao()

    @Provides
    public fun insightDao(db: CoulombDatabase): InsightDao = db.insightDao()
}
