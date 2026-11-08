// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.privileged.di

import android.app.Application
import android.content.Context
import android.os.Process
import android.os.SystemClock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.akane599.coulomb.core.privileged.PrivilegeConfig
import io.github.akane599.coulomb.core.privileged.PrivilegeDrills
import io.github.akane599.coulomb.core.privileged.PrivilegedAccess
import io.github.akane599.coulomb.core.privileged.client.ActivityPauseTracker
import io.github.akane599.coulomb.core.privileged.client.AndroidGrantSource
import io.github.akane599.coulomb.core.privileged.client.AndroidPipes
import io.github.akane599.coulomb.core.privileged.client.DebugInAppDumper
import io.github.akane599.coulomb.core.privileged.client.FilePrivilegeLedger
import io.github.akane599.coulomb.core.privileged.client.PrivilegeController
import io.github.akane599.coulomb.core.privileged.client.PrivilegePlatform
import io.github.akane599.coulomb.core.privileged.client.ShizukuApiImpl
import io.github.akane599.coulomb.core.privileged.client.ShizukuBinderGrants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/** The app module provides [PrivilegeConfig]; everything else is built here. */
@Module
@InstallIn(SingletonComponent::class)
public object PrivilegedModule {
    @Provides
    @Singleton
    public fun privilegedAccess(
        @ApplicationContext context: Context,
        config: PrivilegeConfig,
    ): PrivilegedAccess =
        PrivilegeController(
            config = config,
            platform =
                PrivilegePlatform(
                    api = ShizukuApiImpl(context, config.workerVersion.toInt(), config.debuggable),
                    grantSource = AndroidGrantSource(context),
                    ledger = FilePrivilegeLedger(context.noBackupFilesDir),
                    binderGrants = ShizukuBinderGrants,
                    inAppDumper = DebugInAppDumper,
                    pipes = AndroidPipes,
                    foreground = ActivityPauseTracker(context.applicationContext as Application, SystemClock::elapsedRealtime),
                ),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            io = Dispatchers.IO,
            clock = SystemClock::elapsedRealtime,
            processStartMs = Process.getStartElapsedRealtime(),
        )

    /** The same instance; the drills refuse to run in release builds. */
    @Provides
    @Singleton
    public fun privilegeDrills(access: PrivilegedAccess): PrivilegeDrills = access as PrivilegeController
}
