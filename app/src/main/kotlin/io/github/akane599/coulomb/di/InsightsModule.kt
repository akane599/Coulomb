// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.di

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.akane599.coulomb.core.database.dao.DeviceDao
import io.github.akane599.coulomb.core.database.dao.InsightDao
import io.github.akane599.coulomb.core.database.dao.SampleDao
import io.github.akane599.coulomb.core.database.dao.SessionDao
import io.github.akane599.coulomb.core.database.dao.SnapshotDao
import io.github.akane599.coulomb.core.database.dao.WindowDao
import io.github.akane599.coulomb.core.insights.InsightEngine
import io.github.akane599.coulomb.core.insights.InsightPorts
import io.github.akane599.coulomb.core.insights.InsightRunner
import io.github.akane599.coulomb.core.insights.InsightStore
import io.github.akane599.coulomb.core.insights.RuleContextLoader
import io.github.akane599.coulomb.core.insights.Thresholds
import javax.inject.Singleton

/** The insight engine's wiring (SPEC 9). `SurfaceObserver` runs it after each heavy snapshot (SPEC 7.5, step 6). */
@Module
@InstallIn(SingletonComponent::class)
object InsightsModule {
    @Provides
    @Singleton
    fun store(
        dao: InsightDao,
        devices: DeviceDao,
    ): InsightStore = InsightStore(dao, devices)

    /** Reads the bundled thresholds on first use, off the main thread: the collector asks for it. */
    @Provides
    @Singleton
    fun engine(): InsightEngine = InsightEngine(Thresholds.bundled())

    @Provides
    @Singleton
    fun ports(
        @ApplicationContext context: Context,
        sessions: SessionDao,
        devices: DeviceDao,
    ): InsightPorts =
        object : InsightPorts {
            private val pm = context.packageManager

            override val ownUid = Process.myUid()

            /** The latest day's estimate, as Health shows it; before one settles, the design capacity. */
            override suspend fun capacityMah(): Double? =
                sessions.latestHealthEstimateMah() ?: devices.profile(Build.FINGERPRINT)?.designCapacityMah?.toDouble()

            override fun lastUpdateWallMs(uid: Int): Long? =
                packages(uid)
                    .mapNotNull { name ->
                        try {
                            pm.getPackageInfo(name, 0).takeIf { it.lastUpdateTime > it.firstInstallTime }?.lastUpdateTime
                        } catch (_: PackageManager.NameNotFoundException) {
                            null
                        }
                    }.maxOrNull()

            /** Another user's UID cannot be asked for its packages without cross-user access (see `AndroidAppLabels`). */
            private fun packages(uid: Int): List<String> =
                try {
                    pm.getPackagesForUid(uid)?.toList().orEmpty()
                } catch (_: SecurityException) {
                    emptyList()
                }
        }

    @Provides
    @Singleton
    fun runner(
        snapshots: SnapshotDao,
        windows: WindowDao,
        sessions: SessionDao,
        samples: SampleDao,
        ports: InsightPorts,
        engine: InsightEngine,
        store: InsightStore,
    ): InsightRunner = InsightRunner(RuleContextLoader(snapshots, windows, sessions, samples, ports), engine, store)
}
