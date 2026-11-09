// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.di

import android.content.Context
import android.os.Build
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.akane599.coulomb.BuildConfig
import io.github.akane599.coulomb.core.collect.engine.CollectorSettings
import io.github.akane599.coulomb.core.datastore.SettingsStore
import io.github.akane599.coulomb.core.privileged.PrivilegeConfig
import kotlinx.coroutines.flow.first

@Module
@InstallIn(SingletonComponent::class)
object AppConfigModule {
    // SPEC 4.3: .version(BuildConfig.VERSION_CODE) and .debuggable(BuildConfig.DEBUG).
    @Provides
    fun privilegeConfig(
        @ApplicationContext context: Context,
    ): PrivilegeConfig =
        PrivilegeConfig(
            packageName = context.packageName,
            uid = context.applicationInfo.uid,
            workerVersion = BuildConfig.VERSION_CODE.toLong(),
            debuggable = BuildConfig.DEBUG,
            fingerprint = Build.FINGERPRINT,
        )

    // ADR-0035 §2: the collector reads "Component breakdown" at each heavy snapshot.
    @Provides
    fun collectorSettings(store: SettingsStore): CollectorSettings = CollectorSettings { store.settings.first().componentBreakdown }
}
