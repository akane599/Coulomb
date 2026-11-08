// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.data

import io.github.akane599.coulomb.core.common.AppDispatchers
import io.github.akane599.coulomb.core.common.AppScope
import io.github.akane599.coulomb.core.database.entity.UidMetaEntity
import io.github.akane599.coulomb.core.database.entity.WindowResultEntity
import io.github.akane599.coulomb.core.database.entity.WindowUidEntity
import io.github.akane599.coulomb.core.model.ChargeSource
import io.github.akane599.coulomb.core.model.Provenance
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Who used the battery in a window (SPEC 6.5, 10.2, 10.3), from the windows the collector stored
 * (ADR-0032 §3) and the chains of consecutive windows between them (ADR-0036). Names come from
 * `uid_meta` (§5).
 */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
public class DrainRepository
    @Inject
    internal constructor(
        private val reader: DrainReader,
        private val dispatchers: AppDispatchers,
        scope: AppScope,
    ) {
        private val inputs: SharedFlow<DrainInputs> =
            reader.inputs().shareIn(scope, SharingStarted.WhileSubscribed(BatteryRepository.STOP_TIMEOUT_MS), replay = 1)

        /**
         * Since unplugged: while unplugged, the REFERENCE window from the unplug; plugged in, the
         * last discharge session's window. Null until one is stored.
         */
        public val sinceUnplugged: StateFlow<WindowDrains?> =
            report(DrainSpan.SinceUnplugged)
                .map { it?.drains }
                .stateIn(scope, SharingStarted.WhileSubscribed(BatteryRepository.STOP_TIMEOUT_MS), null)

        /** The report for [span], read again after every heavy snapshot; null until it has two ends. */
        public fun report(span: DrainSpan): Flow<DrainReport?> = inputs.mapLatest { reader.read(span, it) }.flowOn(dispatchers.io)
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
    ): WindowDrains =
        build(
            fromWallMs = fromWallMs,
            toWallMs = toWallMs,
            measuredMah = window.measuredMah,
            measuredProvenance = window.measuredMah?.let { provenance(window.chargeSource) },
            modeledMah = window.modeledMah,
            rows = rows,
            meta = meta,
        )

    /**
     * A span's drains from its measured total, the model's device total and its UID rows, whether
     * they come from one stored window or are summed over a chain of them (ADR-0036).
     */
    fun build(
        fromWallMs: Long,
        toWallMs: Long,
        measuredMah: Double?,
        measuredProvenance: Provenance?,
        modeledMah: Double?,
        rows: List<WindowUidEntity>,
        meta: List<UidMetaEntity>,
        levelEstimate: LevelEstimate? = null,
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
        val modeled = modeledMah?.takeIf { it >= uidSum } ?: uidSum
        return WindowDrains(
            fromWallMs = fromWallMs,
            toWallMs = toWallMs,
            measuredMah = measuredMah,
            measuredProvenance = measuredMah?.let { measuredProvenance },
            modeledMah = modeled,
            apps = apps,
            otherMah = perUid[WindowUidEntity.OTHER_UID] ?: 0.0,
            deviceMah = modeled - uidSum,
            levelEstimate = levelEstimate?.takeIf { measuredMah != null && measuredProvenance == Provenance.ESTIMATED },
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
