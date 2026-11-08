// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.data

import io.github.akane599.coulomb.core.common.AppDispatchers
import io.github.akane599.coulomb.core.common.AppScope
import io.github.akane599.coulomb.core.database.dao.DeviceDao
import io.github.akane599.coulomb.core.database.dao.SessionDao
import io.github.akane599.coulomb.core.database.dao.SnapshotDao
import io.github.akane599.coulomb.core.database.dao.WindowDao
import io.github.akane599.coulomb.core.database.entity.ReferenceEntity
import io.github.akane599.coulomb.core.database.entity.SessionEntity
import io.github.akane599.coulomb.core.database.entity.UidMetaEntity
import io.github.akane599.coulomb.core.database.entity.WindowResultEntity
import io.github.akane599.coulomb.core.database.entity.WindowUidEntity
import io.github.akane599.coulomb.core.model.AutoReference
import io.github.akane599.coulomb.core.model.ChargeSource
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.model.SessionKind
import io.github.akane599.coulomb.core.model.WindowKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Who used the battery in a window (SPEC 6.5, 10.2, 10.3), from the windows the collector stored
 * (ADR-0032 §3). Names come from `uid_meta` (§5).
 */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
public class DrainRepository
    @Inject
    internal constructor(
        private val windows: WindowDao,
        private val snapshots: SnapshotDao,
        sessions: SessionDao,
        devices: DeviceDao,
        dispatchers: AppDispatchers,
        scope: AppScope,
    ) {
        /**
         * Since unplugged: while unplugged, the REFERENCE window from the unplug; plugged in, the
         * last discharge session's window. Null until one is stored.
         */
        public val sinceUnplugged: StateFlow<WindowDrains?> =
            combine(
                windows.observeRecent(WindowKind.REFERENCE, RECENT_REFERENCES),
                snapshots.observeReferences(),
                sessions.observeRecent(SessionKind.DISCHARGE, 1),
                devices.observeUidMeta(),
            ) { stored, references, discharge, meta -> SinceUnplugged(stored, references, discharge.firstOrNull(), meta) }
                .mapLatest { it.window()?.let { window -> drains(window, it.meta) } }
                .flowOn(dispatchers.io)
                .stateIn(scope, SharingStarted.WhileSubscribed(BatteryRepository.STOP_TIMEOUT_MS), null)

        private suspend fun SinceUnplugged.window(): WindowResultEntity? {
            val session = discharge ?: return null
            if (session.closeReason != null) return windows.forSession(session.id)
            val start = references.firstOrNull { it.autoKind == AutoReference.SINCE_UNPLUGGED }?.snapshotId ?: return null
            return stored.firstOrNull { it.fromSnapshotId == start }
        }

        private suspend fun drains(
            window: WindowResultEntity,
            meta: List<UidMetaEntity>,
        ): WindowDrains? {
            val from = snapshots.snapshot(window.fromSnapshotId) ?: return null
            val to = snapshots.snapshot(window.toSnapshotId) ?: return null
            return WindowDrainsBuilder.build(window, from.wallMs, to.wallMs, windows.rows(window.id).uids, meta)
        }

        private class SinceUnplugged(
            val stored: List<WindowResultEntity>,
            val references: List<ReferenceEntity>,
            val discharge: SessionEntity?,
            val meta: List<UidMetaEntity>,
        )

        private companion object {
            /** One per reference is kept (ADR-0032 §3); a little slack for a write in progress. */
            const val RECENT_REFERENCES = 4
        }
    }

/** Turns a stored window and its UID rows into [WindowDrains]. Pure. */
internal object WindowDrainsBuilder {
    /** `uid_power.component` of a UID's whole modeled total (CounterMapper.ALL_COMPONENTS). */
    const val ALL_COMPONENTS: String = "all"

    fun build(
        window: WindowResultEntity,
        fromWallMs: Long,
        toWallMs: Long,
        rows: List<WindowUidEntity>,
        meta: List<UidMetaEntity>,
    ): WindowDrains {
        val names = meta.associateBy { it.uid }
        val perUid =
            rows
                .filter { it.uid >= 0 || it.uid == WindowUidEntity.OTHER_UID }
                .groupBy { it.uid }
                .mapValues { (_, uidRows) -> total(uidRows) }
        val apps =
            perUid
                .filterKeys { it >= 0 }
                .filterValues { it > 0 }
                .map { (uid, mah) ->
                    val name = names[uid]
                    AppDrain(uid, name?.label, name?.isSystem ?: false, name?.iconCacheKey?.takeIf { it.isNotEmpty() }, mah)
                }.sortedByDescending { it.mah }
        val uidSum = perUid.values.sum()
        // The window's modeled total is the model's device total (WindowBuilder.modeled), which also
        // holds what no app was charged for. Without the device section at both ends it is null,
        // and the UID rows are all there is.
        val modeled = window.modeledMah?.takeIf { it >= uidSum } ?: uidSum
        return WindowDrains(
            fromWallMs = fromWallMs,
            toWallMs = toWallMs,
            measuredMah = window.measuredMah,
            measuredProvenance = window.measuredMah?.let { provenance(window.chargeSource) },
            modeledMah = modeled,
            apps = apps,
            otherMah = perUid[WindowUidEntity.OTHER_UID] ?: 0.0,
            deviceMah = modeled - uidSum,
        )
    }

    /** A UID's whole total when stored, else the sum of its parts: never both (WindowUidEntity). */
    private fun total(rows: List<WindowUidEntity>): Double {
        val whole = rows.filter { it.component == ALL_COMPONENTS }
        return (whole.ifEmpty { rows }).sumOf { it.mAh }
    }

    private fun provenance(source: ChargeSource?): Provenance =
        if (source == ChargeSource.CHARGE_COUNTER) Provenance.MEASURED else Provenance.ESTIMATED
}
