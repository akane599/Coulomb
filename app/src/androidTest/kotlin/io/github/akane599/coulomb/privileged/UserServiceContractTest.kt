// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.privileged

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.github.akane599.coulomb.core.privileged.BatteryStatsMode
import io.github.akane599.coulomb.core.privileged.DumpResult
import io.github.akane599.coulomb.core.privileged.FailureReason
import io.github.akane599.coulomb.core.privileged.PartialReason
import io.github.akane599.coulomb.core.privileged.PrivilegeDrills
import io.github.akane599.coulomb.core.privileged.PrivilegedAccess
import io.github.akane599.coulomb.core.privileged.PrivilegedQuery
import io.github.akane599.coulomb.core.privileged.PrivilegedResult
import io.github.akane599.coulomb.core.privileged.Route
import io.github.akane599.coulomb.core.privileged.SettingsNamespace
import io.github.akane599.coulomb.core.privileged.SysfsRoot
import io.github.akane599.coulomb.core.privileged.SystemService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * SPEC 16.3 UserService contract tests: the worker's whole AIDL surface, called through
 * [PrivilegedAccess] in Coulomb's own process. They need Shizuku running with Coulomb authorized
 * and are skipped with that reason otherwise (CI has no device). Official Shizuku cannot authorize
 * on the API 37 AVD, so they run on the API 36 AVD (P1 progress, Pending).
 */
@HiltAndroidTest
class UserServiceContractTest {
    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var access: PrivilegedAccess

    @Inject lateinit var drills: PrivilegeDrills

    @Before
    fun bindOrSkip() {
        hilt.inject()
        access.start()
        val usable = runBlocking { withTimeoutOrNull(WAIT_MS) { access.state.first { it.workerUsable } } != null }
        assumeTrue("Shizuku is not running, or Coulomb is not authorized in it", usable)
    }

    @Test
    fun theWorkerRunsAsShellOrRootAndSaysWhere() =
        runBlocking {
            val help = access.dumpBatteryStats(BatteryStatsMode.HELP) { it.readBytes().size }
            assertThat(help.worker().value).isGreaterThan(0)
            val diagnostics = access.diagnostics.value
            assertThat(diagnostics.workerUid).isAnyOf(ROOT_UID, SHELL_UID)
            assertThat(diagnostics.workerSeLinuxContext).isNotEmpty()
            assertThat(diagnostics.lastBindMs).isNotNull()
        }

    @Test
    fun everyBatteryStatsModeStreamsThroughTheWorker() =
        runBlocking {
            for (mode in BatteryStatsMode.entries) {
                val result = access.dumpBatteryStats(mode, historyStart = 0) { it.readBytes().size }
                assertWithMessage("$mode").that(result.worker().value).isGreaterThan(0)
            }
        }

    @Test
    fun everyServiceDumpsThroughTheWorkerOrIsReportedMissing() =
        runBlocking {
            for (service in SystemService.entries) {
                when (val result = access.dumpService(service) { it.readBytes().size }) {
                    is DumpResult.Unavailable -> {
                        // suspend_control_internal is not registered on the AVDs or the phone (P0).
                        assertWithMessage("$service").that(service).isEqualTo(SystemService.SUSPEND_CONTROL_INTERNAL)
                        assertWithMessage("$service").that(result.reason).isEqualTo(FailureReason.SERVICE_NOT_FOUND)
                    }

                    else -> {
                        assertWithMessage("$service").that(result.worker().value).isGreaterThan(0)
                    }
                }
            }
        }

    @Test
    fun queriesRunOnlyForAnInstalledPackage() =
        runBlocking {
            for (query in PrivilegedQuery.entries) {
                val result = access.runQuery(query, packageName = OWN_PACKAGE) { it.readBytes().size }
                assertWithMessage("$query").that(result.worker().value).isAtLeast(0)
            }
            for (bad in listOf("-a", "not a package", "com.example.not.installed")) {
                val result = access.runQuery(PrivilegedQuery.APP_OPS, packageName = bad) { it.readBytes().size }
                assertWithMessage(bad).that((result as? DumpResult.Unavailable)?.reason).isEqualTo(FailureReason.INVALID_ARGUMENT)
            }
        }

    @Test
    fun settingsAreReadOnlyForBatteryKeys() =
        runBlocking {
            val lowPower = access.getSetting(SettingsNamespace.GLOBAL, "low_power")
            assertThat(lowPower).isInstanceOf(PrivilegedResult.Success::class.java)
            assertThat((lowPower as PrivilegedResult.Success).route).isEqualTo(Route.WORKER)
            for (key in listOf("android_id", "-low_power", "low_power;id", "")) {
                val refused = access.getSetting(SettingsNamespace.SECURE, key)
                assertWithMessage(key).that((refused as? PrivilegedResult.Failure)?.reason).isEqualTo(FailureReason.INVALID_ARGUMENT)
            }
            for (namespace in SettingsNamespace.entries) {
                val keys = access.listSettingKeys(namespace)
                assertWithMessage("$namespace").that(keys).isInstanceOf(PrivilegedResult.Success::class.java)
                for (key in (keys as PrivilegedResult.Success).value) {
                    assertWithMessage("$namespace $key").that(BATTERY_KEY.containsMatchIn(key)).isTrue()
                }
            }
        }

    @Test
    fun sysfsReadsStayInsideTheirEntry() =
        runBlocking {
            for (path in listOf("", "..", "../../proc/version", "a/./b", "a/b/c/d")) {
                val refused = access.readSysfs(SysfsRoot.POWER_SUPPLY, path)
                assertWithMessage(path).that((refused as? PrivilegedResult.Failure)?.reason).isEqualTo(FailureReason.INVALID_ARGUMENT)
            }
            // Sysfs reads open the path directly, with no command line, so a leading '-' is only a name.
            val missing = access.readSysfs(SysfsRoot.POWER_SUPPLY, "-x")
            assertThat((missing as? PrivilegedResult.Failure)?.reason).isEqualTo(FailureReason.NOT_FOUND)
            val entries = access.listSysfs(SysfsRoot.POWER_SUPPLY)
            val names = (entries as? PrivilegedResult.Success)?.value
            assumeTrue("power_supply is not listable here: $entries", names != null)
            for (name in checkNotNull(names)) {
                // `subsystem` links back to the class directory, outside the entry: refused.
                val escaped = access.readSysfs(SysfsRoot.POWER_SUPPLY, "$name/subsystem/$name/type")
                assertWithMessage(name).that((escaped as? PrivilegedResult.Failure)?.reason).isEqualTo(FailureReason.INVALID_ARGUMENT)
                val type = access.readSysfs(SysfsRoot.POWER_SUPPLY, "$name/type")
                if (type is PrivilegedResult.Failure) {
                    assertWithMessage(name).that(type.reason).isAnyOf(FailureReason.ACCESS_DENIED, FailureReason.NOT_FOUND)
                }
            }
        }

    @Test
    fun aTwentyMegabyteStreamArrivesByteExact() =
        runBlocking {
            val result = drills.streamTestPattern(TWENTY_MB)
            val check = result.worker().value
            assertThat(check.complete).isTrue()
            assertThat(check.peakHeapBytes).isLessThan(PEAK_HEAP_LIMIT)
        }

    @Test
    fun aStreamThatEndsEarlyIsReportedTruncated() =
        runBlocking {
            val result = drills.streamTestPattern(bytes = ONE_MB, truncateAt = ONE_MB / 2)
            assertThat(result).isInstanceOf(DumpResult.Partial::class.java)
            val partial = result as DumpResult.Partial
            assertThat(partial.reason).isEqualTo(PartialReason.TRUNCATED)
            assertThat(partial.bytes).isEqualTo(ONE_MB / 2)
            assertThat(partial.value?.firstMismatchAt).isNull()
        }

    private fun <R> DumpResult<R>.worker(): DumpResult.Complete<R> {
        assertWithMessage("$this").that(this).isInstanceOf(DumpResult.Complete::class.java)
        val complete = this as DumpResult.Complete<R>
        assertWithMessage("$this").that(complete.route).isEqualTo(Route.WORKER)
        return complete
    }

    private companion object {
        const val WAIT_MS = 10_000L
        const val ROOT_UID = 0
        const val SHELL_UID = 2000
        const val OWN_PACKAGE = "io.github.akane599.coulomb"
        const val ONE_MB = 1L shl 20
        const val TWENTY_MB = 20L shl 20

        /** SPEC 16.5: a 20 MB dump keeps the peak heap under 64 MB. */
        const val PEAK_HEAP_LIMIT = 64L shl 20

        /** The worker's allowlist (`Validation.BATTERY_SETTINGS_KEY`, ADR-0009), restated so a change to it fails here. */
        val BATTERY_KEY =
            Regex("(batt|protect|charg|adaptive_?power|power_?sav|low_power|doze|device_idle|psm|thermal)", RegexOption.IGNORE_CASE)
    }
}
