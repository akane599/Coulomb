// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.actions

import android.content.Context
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import io.github.akane599.coulomb.core.collect.SnapshotObserver
import io.github.akane599.coulomb.core.database.dao.InsightDao
import io.github.akane599.coulomb.core.privileged.PrivilegedAccess
import javax.inject.Singleton

/** The Action Center's wiring. The app supplies [ActionGate], because the setting lives outside this module. */
@Module
@InstallIn(SingletonComponent::class)
public object ActionModule {
    @Provides
    @Singleton
    public fun center(
        @ApplicationContext context: Context,
        access: PrivilegedAccess,
        dao: InsightDao,
        gate: ActionGate,
    ): ActionCenter = ActionCenter(WorkerActionIo(context, access), DaoActionLog(dao), gate, System::currentTimeMillis)

    /** SPEC 13.1: each heavy snapshot re-reads every active action's target. With none active it reads nothing. */
    @Provides
    @IntoSet
    public fun drift(center: Lazy<ActionCenter>): SnapshotObserver = SnapshotObserver { heavy -> if (heavy) center.get().checkDrift() }
}
