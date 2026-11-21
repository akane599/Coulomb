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
import io.github.akane599.coulomb.core.collect.actions.ActionGate
import io.github.akane599.coulomb.core.collect.engine.CollectorSettings
import io.github.akane599.coulomb.core.datastore.SettingsStore
import io.github.akane599.coulomb.core.datastore.UserSettings
import io.github.akane599.coulomb.core.parsing.batterystats.UsageStatsProtoParser
import io.github.akane599.coulomb.core.privileged.PrivilegeConfig
import io.github.akane599.coulomb.core.privileged.capability.UsageParser
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

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

    /** The stored settings the collector follows: ADR-0035 §2 and SPEC 14.4's mode, sampling and retention. */
    @Provides
    fun collectorSettings(store: SettingsStore): CollectorSettings =
        object : CollectorSettings {
            override val liteMode = store.settings.map { it.liteMode }

            override val visibleSamplingMs = store.settings.map { it.screenOnSamplingS * MS_PER_S }

            override suspend fun componentBreakdown() = store.settings.first().componentBreakdown

            override suspend fun rawSampleDays() =
                if (store.settings.first().longRawRetention) UserSettings.LONG_RAW_DAYS else CollectorSettings.RAW_SAMPLE_DAYS
        }

    /** SPEC 13: the engine asks this before every action, so nothing can act while the Action Center is off. */
    @Provides
    fun actionGate(store: SettingsStore): ActionGate = ActionGate { store.settings.first().actionCenter }

    /** S4's parser for the capability probe (ADR-0035). */
    @Provides
    fun usageParser(): UsageParser = UsageParser { UsageStatsProtoParser.parse(it) }
}

private const val MS_PER_S = 1_000L
