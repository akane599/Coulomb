// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.privileged

import android.os.Build
import com.google.common.truth.Truth.assertWithMessage
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.github.akane599.coulomb.core.model.AccessTier
import io.github.akane599.coulomb.core.model.parse.ParseReport
import io.github.akane599.coulomb.core.parsing.batterystats.BatteryHistoryParser
import io.github.akane599.coulomb.core.parsing.batterystats.BatteryStatsCheckinParser
import io.github.akane599.coulomb.core.parsing.batterystats.BatteryStatsProtoParser
import io.github.akane599.coulomb.core.parsing.dumpsys.AlarmParser
import io.github.akane599.coulomb.core.parsing.dumpsys.BatteryServiceParser
import io.github.akane599.coulomb.core.parsing.dumpsys.DeviceIdleParser
import io.github.akane599.coulomb.core.parsing.dumpsys.JobSchedulerParser
import io.github.akane599.coulomb.core.parsing.dumpsys.PowerParser
import io.github.akane599.coulomb.core.parsing.dumpsys.ThermalServiceParser
import io.github.akane599.coulomb.core.privileged.BatteryStatsMode
import io.github.akane599.coulomb.core.privileged.DumpResult
import io.github.akane599.coulomb.core.privileged.PrivilegedAccess
import io.github.akane599.coulomb.core.privileged.RoutePreference
import io.github.akane599.coulomb.core.privileged.SystemService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.InputStream
import javax.inject.Inject

/**
 * On-device parser tests (SPEC 16.7): every source the parsers read, dumped live through the
 * collector's route (in-app with the T1 grants first, ADR-0018) and parsed whole, with no issue.
 * They catch output that the committed fixtures do not have. Skipped without the T1 grants.
 */
@HiltAndroidTest
class OnDeviceParserTest {
    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var access: PrivilegedAccess

    @Before
    fun grantsOrSkip() {
        hilt.inject()
        access.start()
        val reachable =
            runBlocking {
                access.refreshGrants()
                // The tier settles once the grants are read and Shizuku's binder (if any) arrives.
                withTimeoutOrNull(WAIT_MS) { access.state.first { it.tier != AccessTier.T0_UNPRIVILEGED } } != null
            }
        assumeTrue("Coulomb has no dump access: bootstrap the T1 grants first", reachable)
    }

    @Test
    fun batteryStatsParsesWhole() =
        runBlocking {
            assertWhole(
                "S5 proto",
                access.dumpBatteryStats(BatteryStatsMode.PROTO, route = IN_APP) { BatteryStatsProtoParser.parse(it).report },
            )
            assertWhole(
                "S6 checkin",
                access.dumpBatteryStats(
                    BatteryStatsMode.CHECKIN,
                    route = IN_APP,
                ) { BatteryStatsCheckinParser.parse(it, Build.VERSION.SDK_INT).report },
            )
            assertWhole(
                "S7 history",
                access.dumpBatteryStats(BatteryStatsMode.CHECKIN_HISTORY, historyStart = 0, route = IN_APP) {
                    BatteryHistoryParser.parse(it, 0) {}.report
                },
            )
        }

    @Test
    fun serviceDumpsParseWhole() =
        runBlocking {
            val parsers: Map<SystemService, (InputStream) -> ParseReport> =
                mapOf(
                    SystemService.ALARM to { AlarmParser.parse(it).report },
                    SystemService.DEVICE_IDLE to { DeviceIdleParser.parse(it).report },
                    SystemService.POWER to { PowerParser.parse(it).report },
                    SystemService.JOB_SCHEDULER to { JobSchedulerParser.parse(it).report },
                    SystemService.THERMAL to { ThermalServiceParser.parse(it).report },
                )
            for ((service, parse) in parsers) {
                assertWhole("$service", access.dumpService(service, route = IN_APP) { parse(it) })
            }
        }

    /** Apps cannot look up the battery service, so S21 needs the worker (`SystemService.inApp`). */
    @Test
    fun batteryServiceParsesWholeThroughTheWorker() =
        runBlocking {
            assumeTrue("S21 needs the worker, and Shizuku is not usable here", access.state.value.workerUsable)
            assertWhole("S21", access.dumpService(SystemService.BATTERY) { BatteryServiceParser.parse(it).report })
        }

    private fun assertWhole(
        what: String,
        result: DumpResult<ParseReport>,
    ) {
        assertWithMessage("$what: $result").that(result).isInstanceOf(DumpResult.Complete::class.java)
        val complete = result as DumpResult.Complete<ParseReport>
        val report = complete.value
        assertWithMessage("$what: ${report.issues}").that(report.issueCount).isEqualTo(0)
        assertWithMessage("$what: ${report.issues}").that(report.complete).isTrue()
        assertWithMessage(what).that(report.bytesRead).isEqualTo(complete.bytes)
    }

    private companion object {
        const val WAIT_MS = 10_000L
        val IN_APP = RoutePreference.IN_APP_FIRST
    }
}
