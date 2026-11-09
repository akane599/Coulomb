// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.data.di

import android.os.Build
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.akane599.coulomb.core.common.AppDispatchers
import io.github.akane599.coulomb.core.common.AppScope
import io.github.akane599.coulomb.core.common.ZoneSource
import io.github.akane599.coulomb.core.data.BuildFingerprint
import java.time.ZoneId
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
public object DataModule {
    @Provides
    @Singleton
    public fun dispatchers(): AppDispatchers = AppDispatchers.production()

    @Provides
    @Singleton
    public fun scope(dispatchers: AppDispatchers): AppScope = AppScope(dispatchers)

    @Provides
    public fun zone(): ZoneSource = ZoneSource { ZoneId.systemDefault() }

    @Provides
    public fun fingerprint(): BuildFingerprint = BuildFingerprint { Build.FINGERPRINT }
}
