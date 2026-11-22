// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.automation

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import io.github.akane599.coulomb.core.analytics.DozeProfile
import io.github.akane599.coulomb.core.collect.Collector
import io.github.akane599.coulomb.core.collect.SnapshotObserver
import io.github.akane599.coulomb.core.collect.SnapshotOutcome
import io.github.akane599.coulomb.core.collect.actions.ActionCenter
import io.github.akane599.coulomb.core.collect.actions.ActionPreview
import io.github.akane599.coulomb.core.collect.actions.ActionRecord
import io.github.akane599.coulomb.core.collect.actions.ActionRequest
import io.github.akane599.coulomb.core.collect.actions.ActionResult
import io.github.akane599.coulomb.core.collect.actions.ActionType
import io.github.akane599.coulomb.core.database.dao.InsightDao
import io.github.akane599.coulomb.core.database.entity.ActionLogEntity
import io.github.akane599.coulomb.core.datastore.SettingsStore
import io.github.akane599.coulomb.core.model.AccessTier
import io.github.akane599.coulomb.core.model.ActionOrigin
import io.github.akane599.coulomb.core.model.SnapshotTrigger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Singleton

/** The Automation API's wiring (SPEC 12.7): the engine over the real phone, settings and Action Center. */
@Module
@InstallIn(SingletonComponent::class)
internal object AutomationModule {
    @Provides
    @Singleton
    fun engine(
        @ApplicationContext context: Context,
        settings: SettingsStore,
        collector: Collector,
        center: ActionCenter,
        dao: InsightDao,
    ): AutomationEngine = AutomationEngine(PhonePorts(context, settings, collector), CenterActions(center, dao))

    private class PhonePorts(
        private val context: Context,
        private val settings: SettingsStore,
        private val collector: Collector,
    ) : AutomationPorts {
        override suspend fun enabled(): Boolean = settings.settings.first().automation

        override fun nowWallMs(): Long = System.currentTimeMillis()

        override fun elapsedMs(): Long = SystemClock.elapsedRealtime()

        override fun awakeOrPlugged(): Boolean {
            if (context.getSystemService(PowerManager::class.java).isInteractive) return true
            // The sticky battery broadcast: read, not registered for.
            val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            return (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        }

        // Giving up the wait does not give up the snapshot: the collector finishes what it took.
        override suspend fun snapshot(): SnapshotOutcome? =
            withTimeoutOrNull(SNAPSHOT_WAIT_MS) { collector.snapshot(SnapshotTrigger.AUTOMATION) }

        override suspend fun chargeTarget(): Int = settings.settings.first().chargeTargetPct

        override suspend fun setChargeTarget(pct: Int) = settings.chargeTarget(pct)
    }

    /** Inside the receiver's own eight seconds, with room for the answer. */
    private const val SNAPSHOT_WAIT_MS = 6_000L

    private class CenterActions(
        private val center: ActionCenter,
        private val dao: InsightDao,
    ) : AutomationActions {
        override suspend fun automationSince(sinceWallMs: Long): List<ActionRecord> = dao.automationSince(sinceWallMs).map(::record)

        override suspend fun appliedProfile(): ActionRecord? = dao.appliedDozeProfile()?.let(::record)

        override suspend fun userProfiles(profile: String): List<ActionRecord> = dao.userDozeProfiles(profile).map(::record)

        override suspend fun preview(profile: DozeProfile): ActionPreview = center.preview(ActionRequest.ApplyDozeProfile(profile))

        override suspend fun apply(profile: DozeProfile): ActionResult =
            center.run(ActionRequest.ApplyDozeProfile(profile), ActionOrigin.AUTOMATION)

        override suspend fun revert(id: Long): ActionResult = center.revert(id, ActionOrigin.AUTOMATION)

        override suspend fun log(
            target: String,
            before: String?,
            after: String,
        ): Long =
            dao.insertAction(
                ActionLogEntity(
                    actionType = ActionType.AUTOMATION_CALL.id,
                    target = target,
                    beforeValue = before,
                    afterValue = after,
                    // Nothing of a call's own runs through the worker; a Doze profile's row is the Action Center's.
                    tier = AccessTier.T0_UNPRIVILEGED,
                    executedWall = System.currentTimeMillis(),
                    revertedWall = null,
                    origin = ActionOrigin.AUTOMATION,
                    insightId = null,
                    externalDriftWall = null,
                    externalDriftValue = null,
                ),
            )

        override suspend fun forget(id: Long) {
            dao.action(id)?.takeIf { it.actionType == ActionType.AUTOMATION_CALL.id }?.let { dao.deleteAction(it) }
        }

        /** A log row as the engine reads it; the command that would revert it is the Action Center's to print. */
        private fun record(row: ActionLogEntity) =
            ActionRecord(
                id = row.id,
                type = ActionType.of(row.actionType),
                target = row.target,
                before = row.beforeValue,
                after = row.afterValue,
                executedWallMs = row.executedWall,
                revertedWallMs = row.revertedWall,
                origin = row.origin,
                driftWallMs = row.externalDriftWall,
                driftValue = row.externalDriftValue,
                revertCommand = null,
            )
    }
}

/** A stored snapshot makes the provider's kept answers stale; nothing is read until a caller asks. */
@Module
@InstallIn(SingletonComponent::class)
internal interface AutomationBindings {
    @Binds
    @IntoSet
    fun automation(store: AutomationStore): SnapshotObserver
}
