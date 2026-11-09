// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.database.dao.WindowRows
import io.github.akane599.coulomb.core.database.dao.WindowStat
import io.github.akane599.coulomb.core.database.dao.WindowWake
import io.github.akane599.coulomb.core.database.entity.PowerColumns
import io.github.akane599.coulomb.core.database.entity.WindowComponentEntity
import io.github.akane599.coulomb.core.database.entity.WindowUidEntity
import io.github.akane599.coulomb.core.model.AutoReference
import io.github.akane599.coulomb.core.model.Breakdown
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.model.WakeKind
import io.github.akane599.coulomb.core.model.WindowKind
import io.github.akane599.coulomb.core.model.stats.PowerComponent
import io.github.akane599.coulomb.core.model.stats.UidKeys
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** ADR-0041: one app over the chosen window, its counters and its 14 days. */
@RunWith(AndroidJUnit4::class)
internal class AppDetailReaderTest : DrainDbTest() {
    /** §1: the header is the Drain row; wakes and counters are the app's own, and a missing counter is null. */
    @Test
    fun theStoredWindowGivesTheAppsRowWakesAndCounters() =
        runTest {
            val a = heavy(1_000)
            val c = heavy(3_000)
            val reference = reference(AutoReference.SINCE_UNPLUGGED, a)
            val stored =
                store(
                    window(a, c, WindowKind.REFERENCE).copy(measuredMah = 200.0, modeledMah = 180.0),
                    WindowRows(
                        uids = listOf(uid(APP, 100.0), uid(OTHER_APP, 50.0)),
                        wakes = listOf(wake(4_000, 4), WindowWake(OTHER_APP, "*job*", WakeKind.PARTIAL, 9_000, 9, null)),
                        stats =
                            listOf(
                                alarms(APP, 4),
                                alarms(OTHER_APP, 40),
                                stat(APP, UidKeys.JOB_COUNT, 2),
                                stat(APP, UidKeys.JOB_RUNTIME_MS, 30_000),
                                stat(APP, UidKeys.WIFI_RX_BYTES, 1_000_000),
                                stat(APP, UidKeys.FOREGROUND_ACTIVITY_MS, 0),
                            ),
                    ),
                )
            val session = sessions.insert(discharge(a))
            val inputs = DrainInputs(listOf(stored), listOf(reference), sessions.byId(session), meta)

            val detail = checkNotNull(reader.readApp(DrainSpan.SinceUnplugged, APP, inputs, wall(c)))

            assertThat(detail.app?.mah).isEqualTo(100.0)
            assertThat(detail.app?.label).isEqualTo("Halcyon")
            assertThat(detail.report.drains.measuredMah).isEqualTo(200.0)
            assertThat(detail.wakes.map { it.tag }).containsExactly("*sync*")
            assertThat(detail.counters)
                .isEqualTo(
                    AppCounters(
                        alarmWakeups = 4,
                        jobCount = 2,
                        jobRuntimeMs = 30_000,
                        wifiRxBytes = 1_000_000,
                        foregroundActivityMs = 0,
                    ),
                )
            assertThat(detail.components).isNull()
            assertThat(detail.processStates).isNull()
        }

    /** Without a stored window the counters are the chain's sums. */
    @Test
    fun aChainSumsTheAppsCounters() =
        runTest {
            val a = heavy(1_000)
            val b = heavy(2_000)
            val c = heavy(3_000)
            store(window(a, b), WindowRows(uids = listOf(uid(APP, 3.0)), stats = listOf(stat(APP, UidKeys.MOBILE_TX_BYTES, 500))))
            store(window(b, c), WindowRows(uids = listOf(uid(APP, 2.0)), stats = listOf(stat(APP, UidKeys.MOBILE_TX_BYTES, 700))))

            val detail = checkNotNull(reader.readApp(DrainSpan.Last24Hours, APP, empty(), wall(c)))

            assertThat(detail.app?.mah).isEqualTo(5.0)
            assertThat(detail.counters).isEqualTo(AppCounters(mobileTxBytes = 1_200))
        }

    /** §2-3: S4's rows give the app's components and process states, each with its remainder. */
    @Test
    fun s4SplitsTheAppByComponentAndProcessState() =
        runTest {
            val a = heavy(1_000)
            val b = heavy(2_000)
            val c = heavy(3_000)
            store(
                window(a, b).copy(modeledMah = 10.0, breakdown = Breakdown.BROKEN_DOWN),
                WindowRows(
                    uids = listOf(uid(APP, 7.0), uid(WindowUidEntity.OTHER_UID, 3.0)),
                    components = listOf(WindowComponentEntity(0, APP, PowerColumns(cpu = 216, foreground = 180, cached = 36))),
                ),
            )
            store(window(b, c).copy(modeledMah = 1.0, breakdown = Breakdown.S4_OFF), WindowRows(uids = listOf(uid(APP, 1.0))))

            val detail = checkNotNull(reader.readApp(DrainSpan.Last24Hours, APP, empty(), wall(c)))

            assertThat(detail.app?.mah).isEqualTo(8.0)
            assertThat(detail.components?.parts?.map { it.component to it.mah }).containsExactly(PowerComponent.CPU to 6.0)
            assertThat(detail.components?.notBrokenDownMah).isWithin(1e-9).of(2.0)
            val states = checkNotNull(detail.processStates)
            assertThat(states.foregroundMah).isEqualTo(5.0)
            assertThat(states.cachedMah).isEqualTo(1.0)
            assertThat(states.backgroundMah).isEqualTo(0.0)
            assertThat(states.notSplitMah).isWithin(1e-9).of(2.0)
        }

    /** §6: an app with no use in the window still has a report, with nothing recorded. */
    @Test
    fun anAppWithNoUseStillOpens() =
        runTest {
            val a = heavy(1_000)
            val b = heavy(2_000)
            store(window(a, b), WindowRows(uids = listOf(uid(OTHER_APP, 3.0))))

            val detail = checkNotNull(reader.readApp(DrainSpan.Last24Hours, APP, empty(), wall(b)))

            assertThat(detail.app).isNull()
            assertThat(detail.wakes).isEmpty()
            assertThat(detail.counters).isEqualTo(AppCounters())
            assertThat(detail.trend.last().mah).isEqualTo(0.0)
        }

    @Test
    fun aSpanWithoutTwoEndsHasNoAppReport() =
        runTest {
            assertThat(reader.readApp(DrainSpan.Today, APP, empty(), BASE_WALL)).isNull()
        }

    /**
     * §4: a day sums the windows that end in it, the whole-UID row where a window has one; a day with
     * windows but none of the app's rows is 0, a day without windows a gap, and older days are left out.
     */
    @Test
    fun theTrendSumsEachDaysWindowsAndLeavesGaps() =
        runTest {
            val now = BASE_WALL
            val today = day(now)

            fun at(
                daysBack: Long,
                hour: Int,
            ) = today
                .minusDays(daysBack)
                .atStartOfDay(ZoneOffset.UTC)
                .plusHours(hour.toLong())
                .toInstant()
                .toEpochMilli()

            var elapsed = 0L

            suspend fun snapshotAt(wallMs: Long) = heavy(++elapsed * HOUR_S, wallMs = wallMs)

            suspend fun windowEnding(
                wallMs: Long,
                rows: WindowRows,
            ) = store(window(snapshotAt(wallMs - 1), snapshotAt(wallMs)), rows)

            windowEnding(at(20, 10), WindowRows(uids = listOf(uid(APP, 99.0))))
            windowEnding(at(13, 0), WindowRows(uids = listOf(uid(APP, 1.5))))
            windowEnding(at(13, 23), WindowRows(uids = listOf(uid(APP, 2.0), uid(APP, 1.2, "CPU"))))
            windowEnding(at(5, 12), WindowRows(uids = listOf(uid(OTHER_APP, 4.0))))
            windowEnding(at(1, 8), WindowRows(uids = listOf(uid(APP, 0.4, "CPU"), uid(APP, 0.6, "WIFI"))))
            store(
                window(snapshotAt(at(0, 1) - 1), snapshotAt(at(0, 1))).copy(detailPruned = true),
                WindowRows(uids = listOf(uid(APP, 7.0))),
            )

            val detail = checkNotNull(reader.readApp(DrainSpan.Last24Hours, APP, empty(), now))

            assertThat(detail.trend).hasSize(14)
            assertThat(detail.trend.first().day).isEqualTo(today.minusDays(13))
            assertThat(detail.trend.last().day).isEqualTo(today)
            val byDaysBack =
                detail.trend.associate {
                    java.time.temporal.ChronoUnit.DAYS
                        .between(it.day, today) to it.mah
                }
            assertThat(byDaysBack[13]).isWithin(1e-9).of(3.5)
            assertThat(byDaysBack[5]).isEqualTo(0.0)
            assertThat(byDaysBack[1]).isWithin(1e-9).of(1.0)
            assertThat(byDaysBack[0]).isNull()
            assertThat(byDaysBack.filterKeys { it !in setOf(13L, 5L, 1L) }.values.toSet()).containsExactly(null)
        }

    /** A remainder under 0.05 mAh is rounding, not a "Not split" part; no state at all is no split. */
    @Test
    fun processStatesHideARoundingRemainder() {
        val power = PowerColumns(foreground = 72, background = 35).toPower()
        val states = checkNotNull(ProcessStates.of(power, totalMah = 3.0))
        assertThat(states.splitMah).isWithin(1e-9).of(107 / 36.0)
        assertThat(states.notSplitMah).isNull()
        assertThat(ProcessStates.of(PowerColumns(cpu = 72).toPower(), 2.0)).isNull()
        assertThat(ProcessStates.of(null, 2.0)).isNull()
    }

    private fun stat(
        uid: Int,
        key: String,
        value: Long,
    ) = WindowStat(uid, key, value, Provenance.MEASURED)

    private fun day(wallMs: Long): LocalDate = Instant.ofEpochMilli(wallMs).atZone(ZoneOffset.UTC).toLocalDate()
}
