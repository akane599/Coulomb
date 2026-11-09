// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.database.CoulombDatabase
import io.github.akane599.coulomb.core.database.dao.SessionDao
import io.github.akane599.coulomb.core.database.dao.WindowRows
import io.github.akane599.coulomb.core.database.dao.WindowStat
import io.github.akane599.coulomb.core.database.dao.WindowWake
import io.github.akane599.coulomb.core.database.entity.PowerColumns
import io.github.akane599.coulomb.core.database.entity.ReferenceEntity
import io.github.akane599.coulomb.core.database.entity.SessionEntity
import io.github.akane599.coulomb.core.database.entity.SnapshotEntity
import io.github.akane599.coulomb.core.database.entity.SourceStatusCodes
import io.github.akane599.coulomb.core.database.entity.UidMetaEntity
import io.github.akane599.coulomb.core.database.entity.UidPowerEntity
import io.github.akane599.coulomb.core.database.entity.WindowComponentEntity
import io.github.akane599.coulomb.core.database.entity.WindowResultEntity
import io.github.akane599.coulomb.core.database.entity.WindowUid
import io.github.akane599.coulomb.core.database.entity.WindowUidEntity
import io.github.akane599.coulomb.core.model.AutoReference
import io.github.akane599.coulomb.core.model.Breakdown
import io.github.akane599.coulomb.core.model.ChargeSource
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.model.SessionCloseReason
import io.github.akane599.coulomb.core.model.SessionKind
import io.github.akane599.coulomb.core.model.SnapshotKind
import io.github.akane599.coulomb.core.model.SnapshotTrigger
import io.github.akane599.coulomb.core.model.WakeKind
import io.github.akane599.coulomb.core.model.WindowKind
import io.github.akane599.coulomb.core.model.kernel.KernelCoverage
import io.github.akane599.coulomb.core.model.kernel.KernelCoverage.Coverage
import io.github.akane599.coulomb.core.model.stats.DeviceKeys
import io.github.akane599.coulomb.core.model.stats.PowerComponent
import io.github.akane599.coulomb.core.model.stats.UidKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneOffset

/** ADR-0036: a span's report from its stored window, or from the consecutive windows between its ends. */
@RunWith(AndroidJUnit4::class)
internal class DrainReaderTest : DrainDbTest() {
    /**
     * A light snapshot changes no input, so reports are not read again; a new consecutive window
     * (a heavy snapshot's) is the signal (ADR-0036 §6; optimize gate 2026-11-09).
     */
    @Test
    fun aLightSnapshotReadsNothingAgain() =
        runTest {
            val a = heavy(1_000)
            val emissions = mutableListOf<DrainInputs>()
            val job = launch(Dispatchers.Default) { reader.inputs().collect { synchronized(emissions) { emissions += it } } }

            fun count() = synchronized(emissions) { emissions.size }
            withContext(Dispatchers.Default) {
                withTimeout(WAIT_MS) { while (count() < 1) delay(POLL_MS) }
                heavy(1_500, kind = SnapshotKind.LIGHT)
                // Room re-runs the snapshot joins after a write; give it time to (not) emit.
                delay(SETTLE_MS)
                assertThat(count()).isEqualTo(1)
                val b = heavy(2_000)
                store(window(a, b), WindowRows())
                withTimeout(WAIT_MS) { while (count() < 2) delay(POLL_MS) }
            }
            assertThat(synchronized(emissions) { emissions.last().latestWindowId }).isNotNull()
            job.cancel()
        }

    @Test
    fun sinceUnpluggedReadsTheStoredWindowAndItsOwnApps() =
        runTest {
            val a = heavy(1_000)
            val b = heavy(2_000)
            val c = heavy(3_000)
            val session = sessions.insert(discharge(a))
            val reference = reference(AutoReference.SINCE_UNPLUGGED, a)
            val stored =
                store(
                    window(
                        a,
                        c,
                        WindowKind.REFERENCE,
                    ).copy(measuredMah = 200.0, modeledMah = 180.0, screenOnMs = 600_000, screenOffMs = 1_400_000),
                    WindowRows(
                        uids = listOf(uid(APP, 100.0), uid(OTHER_APP, 50.0), uid(WindowUidEntity.OTHER_UID, 10.0)),
                        wakes = listOf(wake(4_000, 4)),
                        stats = device(onBatteryMs = 2_000_000, screenOffMs = 1_400_000),
                    ),
                )
            // Wholly screen-off, then mixed: only the first says when the wakelock was held.
            store(window(a, b).copy(screenOnMs = 0, screenOffMs = 1_000_000), WindowRows(wakes = listOf(wake(3_000, 3))))
            store(window(b, c).copy(screenOnMs = 600_000, screenOffMs = 400_000), WindowRows(wakes = listOf(wake(1_000, 1))))

            val report =
                checkNotNull(
                    reader.read(DrainSpan.SinceUnplugged, DrainInputs(listOf(stored), listOf(reference), sessions.byId(session), meta)),
                )

            assertThat(report.drains.measuredMah).isEqualTo(200.0)
            assertThat(report.drains.measuredProvenance).isEqualTo(Provenance.MEASURED)
            assertThat(report.drains.modeledMah).isEqualTo(180.0)
            assertThat(report.drains.apps.map { it.uid to it.mah }).containsExactly(APP to 100.0, OTHER_APP to 50.0).inOrder()
            assertThat(
                report.drains.apps
                    .first()
                    .label,
            ).isEqualTo("Halcyon")
            assertThat(report.drains.otherMah).isEqualTo(10.0)
            assertThat(report.lowerBound).isFalse()
            assertThat(report.chargedInSpan).isFalse()
            assertThat(report.durationMs).isEqualTo(2_000_000)
            assertThat(report.screenOffMs).isEqualTo(1_400_000)
            // The stored window's own device rows, as its apps and records are its own.
            assertThat(report.onBatteryMs).isEqualTo(2_000_000)
            assertThat(report.screenOffOnBatteryMs).isEqualTo(1_400_000)
            assertThat(report.measuredMs).isEqualTo(2_000_000)
            val wake = report.wakes.single()
            assertThat(wake.totalMs).isEqualTo(4_000)
            assertThat(wake.count).isEqualTo(4)
            assertThat(wake.label).isEqualTo("Halcyon")
            // Batterystats counts a partial wakelock only with the screen off: no share to show.
            assertThat(wake.screenOffShare).isNull()
            // The stored window read S5 at both ends and no root source.
            assertThat(report.wakeCoverage).isEqualTo(WakeCoverage(Coverage.WHOLE, Coverage.NONE, Coverage.NONE))
        }

    @Test
    fun aScreenOffShareIsKeptOnlyForRecordsCountedWheneverOnBattery() =
        runTest {
            val a = heavy(1_000)
            val b = heavy(2_000)
            val c = heavy(3_000)
            val reason = { ms: Long, count: Int -> WindowWake(null, "425 ipa", WakeKind.WAKEUP_REASON, ms, count, null) }
            val source = WindowWake(null, "IPA_WS", WakeKind.WAKEUP_SOURCE, 2_000, 2, 2)
            store(
                window(a, b).copy(screenOnMs = 0, screenOffMs = 1_000_000),
                WindowRows(wakes = listOf(wake(3_000, 3), reason(3_000, 3), source), stats = device(1_000_000, 1_000_000)),
            )
            store(
                window(b, c).copy(screenOnMs = 1_000_000, screenOffMs = 0),
                WindowRows(wakes = listOf(reason(1_000, 1)), stats = device(1_000_000, 0)),
            )

            val report = checkNotNull(reader.read(DrainSpan.Last24Hours, empty()))

            assertThat(report.wakes.single { it.kind == WakeKind.PARTIAL }.screenOffShare).isNull()
            assertThat(report.wakes.single { it.kind == WakeKind.WAKEUP_REASON }.screenOffShare).isEqualTo(0.75)
            // A root source counts on the charger too, where a window has no screen time to split by.
            assertThat(report.wakes.single { it.kind == WakeKind.WAKEUP_SOURCE }.screenOffShare).isNull()
            assertThat(report.onBatteryMs).isEqualTo(2_000_000)
            assertThat(report.screenOffOnBatteryMs).isEqualTo(1_000_000)
        }

    @Test
    fun theTimeOnBatteryIsBatterystatsOwnClock() =
        runTest {
            val a = heavy(1_000)
            val b = heavy(2_000)
            val c = heavy(3_000)
            // Unplugged at a, then on a charger whose snapshot was light: batterystats counted half an hour.
            store(window(a, b), WindowRows(stats = device(onBatteryMs = 1_800_000, screenOffMs = 1_200_000)))
            // No S5 device section at either end: no time on battery is known.
            store(window(b, c))

            val report = checkNotNull(reader.read(DrainSpan.Last24Hours, empty()))

            assertThat(report.durationMs).isEqualTo(2 * 3_600_000L)
            assertThat(report.onBatteryMs).isEqualTo(1_800_000)
            assertThat(report.screenOffOnBatteryMs).isEqualTo(1_200_000)
        }

    @Test
    fun theSmallAppsKeepTheirAlarmsTogether() =
        runTest {
            val a = heavy(1_000)
            val b = heavy(2_000)
            store(window(a, b), WindowRows(stats = listOf(alarms(APP, 3), alarms(WindowUidEntity.OTHER_UID, 7))))

            val report = checkNotNull(reader.read(DrainSpan.Last24Hours, empty()))

            assertThat(report.alarms.map { it.uid to it.wakeups })
                .containsExactly(AlarmEntry.SMALL_APPS_UID to 7L, APP to 3L)
                .inOrder()
            assertThat(report.alarms.first().smallApps).isTrue()
        }

    @Test
    fun aChainSaysWhichWakeSourcesItsWindowsCollected() =
        runTest {
            val a = heavy(1_000)
            val b = heavy(2_000)
            val c = heavy(3_000)
            val root = KernelCoverage(Coverage.WHOLE, Coverage.WHOLE, Coverage.WHOLE)
            store(window(a, b).copy(modeledMah = 10.0, kernelCoverage = root))
            store(window(b, c).copy(modeledMah = 10.0))

            val report = checkNotNull(reader.read(DrainSpan.Last24Hours, empty()))

            // Root for the first window only: its kernel rows are a lower bound for the span.
            assertThat(report.wakeCoverage).isEqualTo(WakeCoverage(Coverage.WHOLE, Coverage.PARTIAL, Coverage.PARTIAL))
        }

    @Test
    fun theDevicesRowsBecomeComponentsByTheirFriendlyKind() =
        runTest {
            val a = heavy(1_000)
            val b = heavy(2_000)
            val session = sessions.insert(discharge(a))
            val reference = reference(AutoReference.SINCE_UNPLUGGED, a)
            val device = UidPowerEntity.DEVICE_UID
            val stored =
                store(
                    window(a, b, WindowKind.REFERENCE).copy(measuredMah = 100.0, modeledMah = 90.0),
                    WindowRows(
                        uids =
                            listOf(
                                uid(APP, 30.0),
                                uid(device, 40.0, "SCREEN"),
                                uid(device, 20.0, "CELL"),
                                uid(device, 5.0, "unnamed"),
                                uid(device, 3.0, "something_else"),
                                uid(device, 0.0, "WIFI"),
                            ),
                    ),
                )

            val report =
                checkNotNull(
                    reader.read(DrainSpan.SinceUnplugged, DrainInputs(listOf(stored), listOf(reference), sessions.byId(session), meta)),
                )

            // Unknown kinds share Other; a zero row is left out; largest first.
            assertThat(report.components.map { it.component to it.mah })
                .containsExactly(PowerComponent.SCREEN to 40.0, PowerComponent.MOBILE_RADIO to 20.0, PowerComponent.OTHER to 8.0)
                .inOrder()
        }

    @Test
    fun aSpanWithAChargeCountsOnlyItsDischarge() =
        runTest {
            val a = heavy(1_000)
            val b = heavy(2_000)
            val c = heavy(3_000, plugType = 2)
            val d = heavy(4_000)
            val hour = 3_600_000L
            store(
                window(a, b).copy(measuredMah = 120.0),
                WindowRows(uids = listOf(uid(APP, 30.0)), stats = listOf(alarms(APP, 3)) + device(hour, hour)),
            )
            store(
                window(b, c).copy(measuredMah = 50.0, chargeSource = ChargeSource.LEVEL_DELTA),
                WindowRows(uids = listOf(uid(APP, 10.0)), stats = device(hour, hour)),
            )
            // On the charger, batterystats counts no time on battery.
            store(window(c, d).copy(measuredMah = -300.0), WindowRows(stats = listOf(alarms(APP, 2))))

            val report = checkNotNull(reader.read(DrainSpan.Last24Hours, empty()))

            assertThat(report.drains.fromWallMs).isEqualTo(wall(a))
            assertThat(report.drains.toWallMs).isEqualTo(wall(d))
            assertThat(report.drains.measuredMah).isEqualTo(170.0)
            // One of the two discharge windows came from the level.
            assertThat(report.drains.measuredProvenance).isEqualTo(Provenance.ESTIMATED)
            assertThat(report.chargedInSpan).isTrue()
            // Three hours, one of them on the charger.
            assertThat(report.durationMs).isEqualTo(3 * 3_600_000L)
            assertThat(report.onBatteryMs).isEqualTo(2 * 3_600_000L)
            assertThat(report.screenOffOnBatteryMs).isEqualTo(2 * 3_600_000L)
            assertThat(report.measuredMs).isEqualTo(2 * 3_600_000L)
            assertThat(report.lowerBound).isTrue()
            assertThat(report.drains.apps.map { it.uid to it.mah }).containsExactly(APP to 40.0)
            assertThat(report.alarms.map { it.uid to it.wakeups }).containsExactly(APP to 5L)
        }

    @Test
    fun aChainWithLevelWindowsIsARangeOfOneStepForEachStretch() =
        runTest {
            val a = heavy(1_000, level = 80)
            val b = heavy(2_000, level = 78)
            val c = heavy(3_000, level = 75)
            val d = heavy(4_000, level = 74)
            val e = heavy(5_000, level = 73)
            // Two level windows in a row (50 mAh a point), the counter, then one more level window.
            store(window(a, b).copy(measuredMah = 100.0, chargeSource = ChargeSource.LEVEL_DELTA))
            store(window(b, c).copy(measuredMah = 150.0, chargeSource = ChargeSource.LEVEL_DELTA))
            store(window(c, d).copy(measuredMah = 40.0))
            store(window(d, e).copy(measuredMah = 50.0, chargeSource = ChargeSource.LEVEL_DELTA))

            val report = checkNotNull(reader.read(DrainSpan.Last24Hours, empty()))

            assertThat(report.drains.measuredMah).isEqualTo(340.0)
            assertThat(report.drains.measuredProvenance).isEqualTo(Provenance.ESTIMATED)
            // 300 mAh over 6 points: 50 a point, for each of the two stretches.
            assertThat(report.drains.levelEstimate).isEqualTo(LevelEstimate(100.0, stretches = 2, withCounter = true))
            assertThat(report.drains.measuredLowMah).isEqualTo(240.0)
            assertThat(report.drains.measuredHighMah).isEqualTo(440.0)
        }

    @Test
    fun aStoredLevelWindowGivesWayToTheChainThatCoversIt() =
        runTest {
            val a = heavy(1_000, level = 80)
            val b = heavy(2_000, level = 79)
            val c = heavy(3_000, level = 77)
            val session = sessions.insert(discharge(a))
            val reference = reference(AutoReference.SINCE_UNPLUGGED, a)
            // A reboot made the stored window level-based; the counter read both windows inside it.
            val stored =
                store(
                    window(a, c, WindowKind.REFERENCE).copy(
                        measuredMah = 150.0,
                        chargeSource = ChargeSource.LEVEL_DELTA,
                        screenOffMs = 2_000_000,
                    ),
                )
            store(window(a, b).copy(measuredMah = 45.0, screenOffMs = 1_000_000))
            store(window(b, c).copy(measuredMah = 95.0, screenOffMs = 1_000_000))

            val report =
                checkNotNull(
                    reader.read(DrainSpan.SinceUnplugged, DrainInputs(listOf(stored), listOf(reference), sessions.byId(session), meta)),
                )

            assertThat(report.drains.measuredMah).isEqualTo(140.0)
            assertThat(report.drains.measuredProvenance).isEqualTo(Provenance.MEASURED)
            assertThat(report.drains.levelEstimate).isNull()
        }

    @Test
    fun aStoredLevelWindowItsChainDoesNotCoverIsOneStep() =
        runTest {
            val a = heavy(1_000, level = 80)
            val c = heavy(3_000, level = 76)
            val session = sessions.insert(discharge(a))
            val reference = reference(AutoReference.SINCE_UNPLUGGED, a)
            val stored =
                store(
                    window(a, c, WindowKind.REFERENCE).copy(
                        measuredMah = 160.0,
                        chargeSource = ChargeSource.LEVEL_DELTA,
                        screenOffMs = 2_000_000,
                    ),
                )

            val report =
                checkNotNull(
                    reader.read(DrainSpan.SinceUnplugged, DrainInputs(listOf(stored), listOf(reference), sessions.byId(session), meta)),
                )

            assertThat(report.drains.measuredMah).isEqualTo(160.0)
            assertThat(report.drains.measuredProvenance).isEqualTo(Provenance.ESTIMATED)
            assertThat(report.drains.levelEstimate).isEqualTo(LevelEstimate(40.0, stretches = 1, withCounter = false))
        }

    @Test
    fun lastNightIsTheLongestScreenOffSessionOfTheDayWithItsAppsFromTheChain() =
        runTest {
            val a = heavy(1_000)
            val b = heavy(1_000 + 2 * HOUR_S)
            val c = heavy(10_000)
            val d = heavy(10_000 + HOUR_S + 600)
            val e = heavy(20_000)
            val f = heavy(20_000 + 600)
            val night = sessions.insert(screenOff(a, b))
            sessions.insert(screenOff(c, d))
            // Too short to be a night.
            sessions.insert(screenOff(e, f))
            // Screen sessions keep no detail of their own.
            store(window(a, b, WindowKind.SESSION, night).copy(measuredMah = 80.0, detailPruned = true))
            store(window(a, b).copy(measuredMah = 80.0), WindowRows(uids = listOf(uid(APP, 20.0))))
            store(window(b, c))
            store(window(c, d))

            val report = checkNotNull(reader.read(DrainSpan.LastNight, empty()))

            assertThat(report.drains.fromWallMs).isEqualTo(wall(a))
            assertThat(report.drains.toWallMs).isEqualTo(wall(b))
            assertThat(report.drains.measuredMah).isEqualTo(80.0)
            assertThat(report.drains.apps.map { it.uid to it.mah }).containsExactly(APP to 20.0)
            assertThat(report.lowerBound).isTrue()
        }

    @Test
    fun aRangeOfDaysRunsFromItsFirstMidnightToTheNext() =
        runTest {
            val day = LocalDate.of(2026, 11, 1)
            val midnight = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            val before = heavy(1_000, wallMs = midnight - 60_000)
            val a = heavy(2_000, wallMs = midnight + 3_600_000)
            val b = heavy(3_000, wallMs = midnight + 12 * 3_600_000)
            val c = heavy(4_000, wallMs = midnight + 25 * 3_600_000)
            heavy(5_000, wallMs = midnight + 30 * 3_600_000)
            store(window(before, a).copy(measuredMah = 999.0))
            store(window(a, b).copy(measuredMah = 40.0))
            store(window(b, c).copy(measuredMah = 60.0))

            val report = checkNotNull(reader.read(DrainSpan.Days(day, day), empty()))

            assertThat(report.drains.fromWallMs).isEqualTo(midnight + 3_600_000)
            assertThat(report.drains.toWallMs).isEqualTo(midnight + 25 * 3_600_000)
            assertThat(report.drains.measuredMah).isEqualTo(100.0)
            assertThat(report.drains.measuredProvenance).isEqualTo(Provenance.MEASURED)
        }

    @Test
    fun todayBeforeItsWindowIsStoredSumsTheChainToTheLatestSnapshot() =
        runTest {
            val a = heavy(1_000)
            val b = heavy(2_000)
            store(window(a, b).copy(measuredMah = 25.0, modeledMah = 20.0), WindowRows(uids = listOf(uid(APP, 15.0))))

            val report = checkNotNull(reader.read(DrainSpan.Today, empty(listOf(reference(AutoReference.TODAY, a)))))

            assertThat(report.drains.measuredMah).isEqualTo(25.0)
            assertThat(report.drains.modeledMah).isEqualTo(20.0)
            assertThat(
                report.drains.apps
                    .single()
                    .mah,
            ).isEqualTo(15.0)
            assertThat(report.lowerBound).isTrue()
        }

    @Test
    fun aSpanWithoutTwoEndsHasNoReport() =
        runTest {
            assertThat(reader.read(DrainSpan.Today, empty())).isNull()
            assertThat(reader.read(DrainSpan.LastNight, empty())).isNull()
            val a = heavy(1_000)
            assertThat(reader.read(DrainSpan.Last24Hours, empty())).isNull()
            assertThat(reader.read(DrainSpan.Today, empty(listOf(reference(AutoReference.TODAY, a))))).isNull()
            assertThat(reader.read(DrainSpan.SinceUnplugged, empty())).isNull()
        }

    /**
     * ADR-0035 §7: S4's covered windows give the device's components, with what they leave out as
     * "Not broken down", and each app's chips; custom energy shows in app chips only.
     */
    @Test
    fun coveredWindowsBreakDownTheComponentsAndTheApps() =
        runTest {
            val a = heavy(1_000)
            val b = heavy(2_000)
            val c = heavy(3_000)
            store(
                window(a, b).copy(modeledMah = 10.0, breakdown = Breakdown.BROKEN_DOWN),
                WindowRows(
                    uids = listOf(uid(APP, 6.0), uid(WindowUidEntity.OTHER_UID, 4.0)),
                    components =
                        listOf(
                            WindowComponentEntity(0, APP, PowerColumns(cpu = 180, custom = 36, foreground = 216)),
                            WindowComponentEntity(0, WindowUidEntity.OTHER_UID, PowerColumns(wifi = 144)),
                            WindowComponentEntity(0, UidPowerEntity.DEVICE_UID, PowerColumns(cpu = 300, screen = 60, custom = 50)),
                        ),
                ),
            )
            store(window(b, c).copy(modeledMah = 6.0, breakdown = Breakdown.S4_OFF), WindowRows(uids = listOf(uid(APP, 2.0))))

            val report = checkNotNull(reader.read(DrainSpan.Last24Hours, empty()))

            assertThat(report.components.map { it.component to it.mah })
                .containsExactly(PowerComponent.CPU to 300 / 36.0, PowerComponent.SCREEN to 60 / 36.0)
                .inOrder()
            assertThat(report.components.first().topApps).containsExactly(ComponentApp(APP, "Halcyon", 5.0))
            assertThat(report.componentsNotBrokenDownMah).isEqualTo(6.0)
            assertThat(report.breakdownOff).isTrue()
            val chips = checkNotNull(checkNotNull(report.breakdown).of(APP, totalMah = 8.0))
            assertThat(chips.parts.map { it.component to it.mah })
                .containsExactly(PowerComponent.CPU to 5.0, PowerComponent.CUSTOM to 1.0)
                .inOrder()
            assertThat(chips.notBrokenDownMah).isWithin(1e-9).of(2.0)
            assertThat(report.breakdown.of(OTHER_APP, 1.0)).isNull()
        }

    /** Under half the span covered, the lens keeps S5's coarse device rows; the app chips stay. */
    @Test
    fun aMostlyUncoveredSpanKeepsS5sDeviceRows() =
        runTest {
            val a = heavy(1_000)
            val b = heavy(2_000)
            val c = heavy(3_000)
            store(
                window(a, b).copy(modeledMah = 4.0, breakdown = Breakdown.BROKEN_DOWN),
                WindowRows(
                    uids = listOf(uid(APP, 4.0), uid(UidPowerEntity.DEVICE_UID, 4.0, "SCREEN")),
                    components =
                        listOf(
                            WindowComponentEntity(0, APP, PowerColumns(cpu = 144)),
                            WindowComponentEntity(0, UidPowerEntity.DEVICE_UID, PowerColumns(cpu = 144)),
                        ),
                ),
            )
            store(
                window(b, c).copy(modeledMah = 10.0, breakdown = Breakdown.NOT_ON_BATTERY),
                WindowRows(uids = listOf(uid(UidPowerEntity.DEVICE_UID, 10.0, "SCREEN"))),
            )

            val report = checkNotNull(reader.read(DrainSpan.Last24Hours, empty()))

            assertThat(report.components.map { it.component to it.mah }).containsExactly(PowerComponent.SCREEN to 14.0)
            assertThat(report.componentsNotBrokenDownMah).isNull()
            assertThat(report.breakdownOff).isFalse()
            assertThat(
                report.breakdown
                    ?.of(APP, 4.0)
                    ?.parts
                    ?.map { it.component },
            ).containsExactly(PowerComponent.CPU)
        }
}
