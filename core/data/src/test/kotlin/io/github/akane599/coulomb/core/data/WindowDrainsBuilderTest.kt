// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.data

import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.database.entity.UidMetaEntity
import io.github.akane599.coulomb.core.database.entity.WindowResultEntity
import io.github.akane599.coulomb.core.database.entity.WindowUidEntity
import io.github.akane599.coulomb.core.model.ChargeSource
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.model.WindowKind
import org.junit.Test

class WindowDrainsBuilderTest {
    private fun window(
        measured: Double?,
        source: ChargeSource?,
        modeled: Double? = null,
    ) = WindowResultEntity(
        kind = WindowKind.REFERENCE,
        sessionId = null,
        fromSnapshotId = 1,
        toSnapshotId = 2,
        measuredMah = measured,
        chargeSource = source,
        modeledMah = modeled,
        unattributedMah = null,
        screenOnMs = 0,
        screenOffMs = 0,
        awakeMsScreenOff = null,
        quality = 1.0,
        detailPruned = false,
    )

    private fun row(
        uid: Int,
        mah: Double,
        component: String = "all",
        state: String = "any",
    ) = WindowUidEntity(1, uid, component, state, WindowUidEntity.WHOLE_UID, mah, Provenance.MODELED)

    @Test
    fun appsRankByTheirWholeTotalWithNamesAndTheRemainderApart() {
        val rows =
            listOf(
                row(10_100, 120.0),
                row(10_200, 300.0),
                // A UID stored by part sums its parts.
                row(1_000, 40.0, component = "cpu"),
                row(1_000, 20.0, component = "wakelock"),
                row(WindowUidEntity.OTHER_UID, 50.0),
                // The device's own totals are never added to the apps.
                row(-1, 900.0),
            )
        val meta = listOf(UidMetaEntity(10_200, "[\"com.example.video\"]", "Video", false, "com.example.video", 0))

        // The window's modeled total is the device rows' 900 mAh.
        val drains = WindowDrainsBuilder.build(window(1_000.0, ChargeSource.CHARGE_COUNTER, modeled = 900.0), 10, 20, rows, meta)

        assertThat(drains.apps.map { it.uid }).containsExactly(10_200, 10_100, 1_000).inOrder()
        assertThat(drains.apps.first()).isEqualTo(AppDrain(10_200, "Video", false, "com.example.video", 300.0))
        assertThat(drains.apps.last().mah).isWithin(1e-9).of(60.0)
        assertThat(drains.otherMah).isWithin(1e-9).of(50.0)
        assertThat(drains.measuredProvenance).isEqualTo(Provenance.MEASURED)
        // Apps and OTHER make 530; the model charged 370 more to no app; 100 is unattributed.
        assertThat(drains.modeledMah).isWithin(1e-9).of(900.0)
        assertThat(drains.deviceMah).isWithin(1e-9).of(370.0)
        assertThat(drains.unattributedMah).isWithin(1e-9).of(100.0)
        assertThat(drains.share(300.0)).isWithin(1e-9).of(0.3)
    }

    @Test
    fun withoutTheDeviceTotalTheUidsAreTheModelAndSharesDivideByTheLarger() {
        val drains = WindowDrainsBuilder.build(window(400.0, ChargeSource.CHARGE_COUNTER), 10, 20, listOf(row(10_100, 530.0)), emptyList())

        assertThat(drains.modeledMah).isWithin(1e-9).of(530.0)
        assertThat(drains.deviceMah).isWithin(1e-9).of(0.0)
        assertThat(drains.unattributedMah).isWithin(1e-9).of(0.0)
        // Modeled exceeds measured here, so shares divide by the modeled total.
        assertThat(drains.share(530.0)).isWithin(1e-9).of(1.0)
    }

    @Test
    fun aLevelBasedWindowIsEstimatedAndSharesDivideByMeasured() {
        val drains = WindowDrainsBuilder.build(window(1_000.0, ChargeSource.LEVEL_DELTA), 10, 20, listOf(row(10_100, 250.0)), emptyList())

        assertThat(drains.measuredProvenance).isEqualTo(Provenance.ESTIMATED)
        assertThat(drains.share(250.0)).isWithin(1e-9).of(0.25)
        assertThat(drains.apps.single().label).isNull()
    }
}
