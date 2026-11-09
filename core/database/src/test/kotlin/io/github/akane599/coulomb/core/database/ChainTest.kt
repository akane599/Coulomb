// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.database.dao.ChainStat
import io.github.akane599.coulomb.core.database.dao.ChainUid
import io.github.akane599.coulomb.core.database.dao.ChainWake
import io.github.akane599.coulomb.core.database.dao.WindowRows
import io.github.akane599.coulomb.core.database.dao.WindowStat
import io.github.akane599.coulomb.core.database.dao.WindowWake
import io.github.akane599.coulomb.core.database.entity.UidPowerEntity
import io.github.akane599.coulomb.core.database.entity.WindowUid
import io.github.akane599.coulomb.core.database.entity.WindowUidEntity
import io.github.akane599.coulomb.core.model.ChargeSource
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.model.SnapshotKind
import io.github.akane599.coulomb.core.model.WakeKind
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

/** ADR-0036: a span's sums over its chain of consecutive windows. */
@RunWith(AndroidJUnit4::class)
class ChainTest : DatabaseTest() {
    private val windows get() = db.windowDao()
    private val snapshots get() = db.snapshotDao()

    private fun uid(
        uid: Int,
        mah: Double,
        component: String = "all",
    ) = WindowUid(0, uid, component, UidPowerEntity.ANY_STATE, WindowUidEntity.WHOLE_UID, mah, Provenance.MODELED)

    private fun rows(
        vararg wakes: WindowWake,
        uids: List<WindowUid> = emptyList(),
        alarms: Long = 0,
    ) = WindowRows(
        uids = uids,
        wakes = wakes.toList(),
        stats = if (alarms > 0) listOf(WindowStat(10_001, "alarm_wakeups", alarms, Provenance.MEASURED)) else emptyList(),
    )

    @Test
    fun aChainSumsItsDischargeAndSplitsWakesByScreenState() =
        runTest {
            val before = snapshots.insert(snapshot(elapsedS = 100))
            val a = snapshots.insert(snapshot(elapsedS = 1_000))
            val b = snapshots.insert(snapshot(elapsedS = 2_000))
            // Plugged in at c: the window from c is charge, not drain.
            val c = snapshots.insert(snapshot(elapsedS = 3_000).copy(plugType = 2))
            val d = snapshots.insert(snapshot(elapsedS = 4_000))
            val e = snapshots.insert(snapshot(elapsedS = 5_000).copy(plugType = null))
            val f = snapshots.insert(snapshot(elapsedS = 6_000))
            val tag = "*alarm*"
            // Outside the chain: ends at its start.
            windows.insert(window(before, a).copy(measuredMah = 999.0), rows(WindowWake(10_001, tag, WakeKind.PARTIAL, 9_000, 9, null)))
            // Wholly screen-off, by the counter.
            windows.insert(
                window(a, b).copy(measuredMah = 120.0, modeledMah = 100.0, screenOnMs = 0, screenOffMs = 1_000_000),
                rows(
                    WindowWake(10_001, tag, WakeKind.PARTIAL, 1_000, 2, null),
                    uids = listOf(uid(10_001, 30.0), uid(-1, 60.0, "SCREEN")),
                    alarms = 3,
                ),
            )
            // Wholly screen-on, from the level.
            windows.insert(
                window(
                    b,
                    c,
                ).copy(
                    measuredMah = 50.0,
                    chargeSource = ChargeSource.LEVEL_DELTA,
                    modeledMah = 40.0,
                    screenOnMs = 1_000_000,
                    screenOffMs = 0,
                ),
                rows(WindowWake(10_001, tag, WakeKind.PARTIAL, 500, 1, null), uids = listOf(uid(10_001, 10.0)), alarms = 2),
            )
            // Charging: its negative change is not drain.
            windows.insert(window(c, d).copy(measuredMah = -300.0, modeledMah = 5.0, screenOnMs = 400_000, screenOffMs = 600_000), rows())
            // A discharge with no measured value, then one whose start has no plug state.
            windows.insert(window(d, e).copy(measuredMah = null, chargeSource = null, modeledMah = null), rows())
            windows.insert(window(e, f).copy(measuredMah = 7.0), rows())

            val from = checkNotNull(snapshots.snapshot(a))
            val to = checkNotNull(snapshots.snapshot(f))
            val totals = windows.chainTotals(from.bootSeq, from.elapsedNs, to.bootSeq, to.elapsedNs)
            assertThat(totals.windows).isEqualTo(5)
            assertThat(totals.dischargeMah).isEqualTo(170.0)
            assertThat(totals.dischargeWindows).isEqualTo(3)
            assertThat(totals.counterWindows).isEqualTo(1)
            assertThat(totals.unmeasuredWindows).isEqualTo(1)
            assertThat(totals.pluggedWindows).isEqualTo(1)
            assertThat(totals.unknownWindows).isEqualTo(1)
            assertThat(totals.modeledMah).isEqualTo(245.0)
            assertThat(totals.unmodeledWindows).isEqualTo(1)
            assertThat(totals.screenOnMs).isEqualTo(1_000_000 + 400_000)
            assertThat(totals.screenOffMs).isEqualTo(1_000_000 + 600_000 + 2 * 3_600_000)
            // Started on battery with a measured value: a to b and b to c.
            assertThat(totals.measuredMs).isEqualTo(1_000_000 + 1_000_000)

            val end = checkNotNull(snapshots.snapshot(c))
            assertThat(windows.chainWakes(from.bootSeq, from.elapsedNs, end.bootSeq, end.elapsedNs))
                .containsExactly(ChainWake(10_001, tag, WakeKind.PARTIAL, 1_500, 3, screenOffMs = 1_000, screenOnMs = 500, wakeups = null))
            assertThat(windows.chainUids(from.bootSeq, from.elapsedNs, end.bootSeq, end.elapsedNs))
                .containsExactly(ChainUid(10_001, "all", 40.0), ChainUid(-1, "SCREEN", 60.0))
            assertThat(windows.chainStat("alarm_wakeups", from.bootSeq, from.elapsedNs, end.bootSeq, end.elapsedNs))
                .containsExactly(ChainStat(10_001, 5))
        }

    @Test
    fun anEmptyChainHasZeroCountsAndNoSums() =
        runTest {
            val a = checkNotNull(snapshots.snapshot(snapshots.insert(snapshot(elapsedS = 1_000))))
            val totals = windows.chainTotals(a.bootSeq, a.elapsedNs, a.bootSeq, a.elapsedNs)
            assertThat(totals.windows).isEqualTo(0)
            assertThat(totals.dischargeMah).isNull()
            assertThat(totals.modeledMah).isNull()
            assertThat(totals.screenOnMs).isEqualTo(0)
            assertThat(totals.measuredMs).isEqualTo(0)
        }

    @Test
    fun aSpanStartsAtTheFirstHeavySnapshotFromAWallTime() =
        runTest {
            snapshots.insert(snapshot(elapsedS = 100, wallMs = 5_000))
            snapshots.insert(snapshot(elapsedS = 200, wallMs = 9_000, kind = SnapshotKind.LIGHT))
            val heavy = snapshots.insert(snapshot(elapsedS = 300, wallMs = 12_000))
            // A later boot whose clock was set back still comes after it.
            snapshots.insert(snapshot(elapsedS = 10, bootSeq = 2, wallMs = 11_000))
            assertThat(snapshots.firstHeavyFrom(9_000)?.id).isEqualTo(heavy)
            assertThat(snapshots.firstHeavyFrom(20_000)).isNull()
        }
}
