// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.automation

import android.os.BatteryManager
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.analytics.TimeLeft
import io.github.akane599.coulomb.core.common.AutomationContract
import io.github.akane599.coulomb.core.data.AppDrain
import io.github.akane599.coulomb.core.data.BatteryNow
import io.github.akane599.coulomb.core.data.BatteryReading
import io.github.akane599.coulomb.core.data.DischargeSummary
import io.github.akane599.coulomb.core.data.NightSummary
import io.github.akane599.coulomb.core.data.StateRate
import io.github.akane599.coulomb.core.data.WindowDrains
import io.github.akane599.coulomb.core.model.Provenance
import org.junit.Test

/** What the provider gives out (SPEC 12.7, ADR-0065 §6): every figure with its provenance, a missing one with its reason. */
class AutomationFactsTest {
    @Test
    fun everyPathAnswersWithItsMetricsInTheContractsOrderWhateverIsMissing() {
        val empty =
            mapOf(
                AutomationContract.PATH_INFO to AutomationFacts.info("1.0.0", lite = false),
                AutomationContract.PATH_LEVEL to AutomationFacts.level(null),
                AutomationContract.PATH_RATES to AutomationFacts.rates(null),
                AutomationContract.PATH_NIGHT to AutomationFacts.night(null),
                AutomationContract.PATH_DRAINS to AutomationFacts.drains(null, ownUser = 0),
            )
        val full =
            mapOf(
                AutomationContract.PATH_INFO to AutomationFacts.info("1.0.0", lite = true),
                AutomationContract.PATH_LEVEL to AutomationFacts.level(battery()),
                AutomationContract.PATH_RATES to AutomationFacts.rates(reading()),
                AutomationContract.PATH_NIGHT to AutomationFacts.night(NIGHT),
                AutomationContract.PATH_DRAINS to AutomationFacts.drains(WINDOW, ownUser = 0),
            )

        for (rows in listOf(empty, full)) {
            assertThat(rows.keys).containsExactlyElementsIn(AutomationContract.PATHS)
            for ((path, list) in rows) {
                assertThat(list.map { it.metric }).isEqualTo(AutomationContract.METRICS.getValue(path))
                list.forEach { assertThat(it.cells()).hasLength(AutomationContract.COLUMNS.size) }
            }
        }
        // Nothing stored: no figure, and no row says "ok" about one.
        val missing = empty.filterKeys { it != AutomationContract.PATH_INFO }.values.flatten()
        assertThat(missing.map { it.state }.distinct()).containsExactly(AutomationContract.STATE_UNAVAILABLE)
        assertThat(missing.mapNotNull { it.value ?: it.low ?: it.high }).isEmpty()
    }

    @Test
    fun theLevelIsModeledAndThePowerStateIsAWord() {
        val (level, state) = AutomationFacts.level(battery())
        assertThat(level.value).isEqualTo(64.0)
        assertThat(level.cells()[AutomationContract.COLUMNS.indexOf(AutomationContract.PROVENANCE)]).isEqualTo("modeled")
        assertThat(level.asOfWallMs).isEqualTo(NOW)
        assertThat(state.text).isEqualTo("on_battery")

        fun word(status: Int) = AutomationFacts.level(battery(plugged = true, status = status))[1].text
        assertThat(word(BatteryManager.BATTERY_STATUS_CHARGING)).isEqualTo("charging")
        assertThat(word(BatteryManager.BATTERY_STATUS_FULL)).isEqualTo("full")
        assertThat(word(BatteryManager.BATTERY_STATUS_NOT_CHARGING)).isEqualTo("plugged")
    }

    @Test
    fun aRateShowsOnlyOnceThereIsEnoughOfItAndTheTimeLeftIsARange() {
        val (on, off, left) = AutomationFacts.rates(reading())
        assertThat(on.value).isEqualTo(11.3)
        assertThat(on.unit).isEqualTo(AutomationContract.UNIT_PCT_PER_H)
        assertThat(on.provenance).isEqualTo(Provenance.DERIVED)
        assertThat(on.quality).isEqualTo(0.8)
        // Ten minutes of screen-off: SPEC 6.2 says "collecting", and no number goes out.
        assertThat(off.state).isEqualTo(AutomationContract.STATE_COLLECTING)
        assertThat(off.value).isNull()
        // SPEC 6.8: never one number; the ends round outward.
        assertThat(left.value).isNull()
        assertThat(left.low).isEqualTo(250.0)
        assertThat(left.high).isEqualTo(380.0)
        assertThat(left.provenance).isEqualTo(Provenance.ESTIMATED)
    }

    @Test
    fun onTheChargerADischargeRateHasNoMeaning() {
        val rows = AutomationFacts.rates(reading(plugged = true))
        assertThat(rows.map { it.state }.distinct()).containsExactly(AutomationContract.STATE_NOT_APPLICABLE)
        assertThat(rows.mapNotNull { it.value ?: it.low }).isEmpty()
    }

    @Test
    fun theNightIsItsFiguresWithWhatIsMissingSaid() {
        val rows = AutomationFacts.night(NIGHT).associateBy { it.metric }
        assertThat(rows.getValue("duration").value).isEqualTo(450.0)
        assertThat(rows.getValue("level_start").value).isEqualTo(82.0)
        assertThat(rows.getValue("level_end").value).isEqualTo(76.0)
        assertThat(rows.getValue("rate").value).isEqualTo(0.8)
        assertThat(rows.getValue("rate").provenance).isEqualTo(Provenance.DERIVED)
        assertThat(rows.getValue("deep_sleep").value).isEqualTo(91.0)
        assertThat(rows.getValue("deep_doze_reached").text).isEqualTo("yes")
        assertThat(rows.getValue("deep_doze_after").value).isEqualTo(42.0)
        assertThat(rows.values.map { it.fromWallMs }.distinct()).containsExactly(NIGHT.startWallMs)

        val charged =
            AutomationFacts.night(
                NIGHT.copy(ratePctPerHour = null, charged = true, deepDozeReached = false, deepDozeAfterMs = null),
            )
        val byName = charged.associateBy { it.metric }
        assertThat(byName.getValue("rate").state).isEqualTo(AutomationContract.STATE_NOT_APPLICABLE)
        assertThat(byName.getValue("deep_doze_reached").text).isEqualTo("no")
        assertThat(byName.getValue("deep_doze_after").state).isEqualTo(AutomationContract.STATE_NOT_APPLICABLE)

        val unread =
            AutomationFacts.night(NIGHT.copy(deepSleepShare = null, deepDozeReached = null, deepDozeAfterMs = null)).associateBy {
                it.metric
            }
        assertThat(unread.getValue("deep_sleep").state).isEqualTo(AutomationContract.STATE_UNAVAILABLE)
        assertThat(unread.getValue("deep_sleep").provenance).isNull()
        assertThat(unread.getValue("deep_doze_reached").state).isEqualTo(AutomationContract.STATE_UNAVAILABLE)
        assertThat(unread.getValue("deep_doze_reached").text).isNull()
    }

    @Test
    fun theDrainsAreAlwaysThreeRowsLargestFirstWithTheirShareOfTheWindow() {
        val rows = AutomationFacts.drains(WINDOW, ownUser = 0)
        assertThat(rows.map { it.rank }).containsExactly(1, 2, 3).inOrder()
        assertThat(rows.map { it.subjectLabel }).containsExactly("Halcyon", "Tidewater Mail", null).inOrder()
        // 120 of the 400 mAh measured, and 80.
        assertThat(rows[0].value).isEqualTo(30.0)
        assertThat(rows[1].value).isEqualTo(20.0)
        assertThat(rows[0].unit).isEqualTo(AutomationContract.UNIT_PCT_OF_WINDOW)
        assertThat(rows[0].provenance).isEqualTo(Provenance.DERIVED)
        // By name only: no UID and no package goes out.
        assertThat(rows.mapNotNull { it.subject }).isEmpty()
        // Two apps only: the third row says so rather than being left out.
        assertThat(rows[2].state).isEqualTo(AutomationContract.STATE_UNAVAILABLE)
        assertThat(rows[2].value).isNull()
    }

    /** An app of a work profile or another user is counted and not named. */
    @Test
    fun anAppOfAnotherProfileKeepsItsShareAndLosesItsName() {
        val work = WINDOW.copy(apps = listOf(AppDrain(1_010_200, "Lumen Player", false, "org.example.lumen", 200.0)) + WINDOW.apps)

        val first = AutomationFacts.drains(work, ownUser = 0).first()
        assertThat(first.value).isEqualTo(50.0)
        assertThat(first.subject).isNull()
        assertThat(first.subjectLabel).isNull()
        assertThat(first.state).isEqualTo(AutomationContract.STATE_OK)
        // For Coulomb running in that profile it is its own.
        assertThat(AutomationFacts.drains(work, ownUser = 10).first().subjectLabel).isEqualTo("Lumen Player")
    }

    private fun battery(
        plugged: Boolean = false,
        status: Int = BatteryManager.BATTERY_STATUS_DISCHARGING,
    ) = BatteryNow(
        levelPct = 64,
        currentMa = -310.0,
        plugged = plugged,
        status = status,
        tempDeciC = 291,
        voltageMv = 3_910,
        screenOn = true,
        wallMs = NOW,
    )

    private fun reading(plugged: Boolean = false) =
        BatteryReading(
            battery(plugged, if (plugged) BatteryManager.BATTERY_STATUS_CHARGING else BatteryManager.BATTERY_STATUS_DISCHARGING),
            DischargeSummary(
                startWallMs = NOW - 4 * HOUR,
                startLevel = 91,
                levelPct = 64,
                screenOn = StateRate(pctPerHour = 11.26, hours = 2.0, quality = 0.8),
                screenOff = StateRate(pctPerHour = 1.2, hours = 10.0 / 60, quality = 0.3),
                deepSleep = null,
                timeLeft = TimeLeft(shortestHours = 4.2, longestHours = 6.3),
            ),
            levels = emptyList(),
            live = emptyList(),
        )

    private companion object {
        const val HOUR = 3_600_000L
        const val NOW = 1_795_167_000_000L
        val NIGHT =
            NightSummary(
                id = 9,
                startWallMs = NOW - 12 * HOUR,
                endWallMs = NOW - 12 * HOUR + 450 * 60_000L,
                durationMs = 450 * 60_000L,
                startLevel = 82,
                endLevel = 76,
                ratePctPerHour = 0.8,
                charged = false,
                deepSleepShare = 0.912,
                deepDozeReached = true,
                deepDozeAfterMs = 42 * 60_000L,
            )
        val WINDOW =
            WindowDrains(
                fromWallMs = NOW - 4 * HOUR,
                toWallMs = NOW,
                measuredMah = 400.0,
                measuredProvenance = Provenance.MEASURED,
                modeledMah = 300.0,
                apps =
                    listOf(
                        AppDrain(10_140, "Tidewater Mail", false, "org.example.mail", 80.0),
                        AppDrain(10_123, "Halcyon", false, "org.example.halcyon", 120.0),
                    ),
                otherMah = 60.0,
            )
    }
}
