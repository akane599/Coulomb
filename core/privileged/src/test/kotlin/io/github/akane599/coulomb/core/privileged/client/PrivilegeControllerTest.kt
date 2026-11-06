// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.privileged.client

import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.model.AccessTier
import io.github.akane599.coulomb.core.model.GrantStatus
import io.github.akane599.coulomb.core.model.ManagerPermission
import io.github.akane599.coulomb.core.model.ManagerStatus
import io.github.akane599.coulomb.core.model.PrivilegeIssue
import io.github.akane599.coulomb.core.model.WorkerFailure
import io.github.akane599.coulomb.core.privileged.BatteryStatsMode
import io.github.akane599.coulomb.core.privileged.DumpResult
import io.github.akane599.coulomb.core.privileged.FailureReason
import io.github.akane599.coulomb.core.privileged.GrantKind
import io.github.akane599.coulomb.core.privileged.GrantOutcome
import io.github.akane599.coulomb.core.privileged.PartialReason
import io.github.akane599.coulomb.core.privileged.PrivilegeConfig
import io.github.akane599.coulomb.core.privileged.PrivilegedResult
import io.github.akane599.coulomb.core.privileged.Route
import io.github.akane599.coulomb.core.privileged.RoutePreference
import io.github.akane599.coulomb.core.privileged.SysfsRoot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PrivilegeControllerTest {
    private val pipes = FakePipes()
    private val worker = FakeWorker(pipes)
    private val api = FakeShizukuApi(worker)
    private val grants = FakeGrants()
    private val ledger = FakeLedger()
    private val binderGrants = FakeBinderGrants()
    private val inApp = FakeInAppDumper(pipes)
    private var pausedAt: Long? = null

    private fun TestScope.controller(debuggable: Boolean = true): PrivilegeController {
        val io = StandardTestDispatcher(testScheduler)
        return PrivilegeController(
            config = PrivilegeConfig(PACKAGE, UID, FakeWorker.VERSION, debuggable, FINGERPRINT),
            platform = PrivilegePlatform(api, grants, ledger, binderGrants, inApp, pipes) { pausedAt },
            scope = backgroundScope,
            io = io,
            clock = { currentTime },
        )
    }

    private val allGranted =
        GrantStatus(batteryStats = true, dump = true, usageStats = true, usageStatsPermission = true, interactAcrossUsers = true)

    private fun workerGrantsForReal() {
        worker.onGrant = grants::grant
        worker.onAllowUsageStats = { grants.status = grants.status.copy(usageStats = true) }
    }

    private suspend fun PrivilegeController.dumpText(): DumpResult<String> =
        dumpBatteryStats(BatteryStatsMode.PROTO) { String(it.readBytes()) }

    @Test
    fun noBinderWithinTwoSecondsMeansStoppedOrNotInstalled() =
        runTest {
            val c = controller()
            c.start()
            advanceTimeBy(PrivilegeController.NO_BINDER_MS + 1)
            runCurrent()
            assertThat(c.state.value.manager).isEqualTo(ManagerStatus.NOT_RUNNING)
            assertThat(c.state.value.issue).isEqualTo(PrivilegeIssue.MANAGER_STOPPED)

            api.manager = null
            val other = controller()
            other.start()
            advanceTimeBy(PrivilegeController.NO_BINDER_MS + 1)
            runCurrent()
            assertThat(other.state.value.issue).isEqualTo(PrivilegeIssue.MANAGER_NOT_INSTALLED)
            assertThat(other.state.value.tier).isEqualTo(AccessTier.T0_UNPRIVILEGED)
        }

    @Test
    fun firstAuthorizationRunsTheBootstrapThroughTheWorkerAndRecordsIt() =
        runTest {
            api.binderAlive = true
            api.permissionGranted = true
            workerGrantsForReal()
            val c = controller()
            c.start()
            runCurrent()
            val report = c.lastBootstrap.value!!
            assertThat(report.allGranted).isTrue()
            assertThat(report.items.map { it.route }.toSet()).containsExactly(Route.WORKER)
            assertThat(report.adbCommands).isEmpty()
            assertThat(ledger.record).isEqualTo(LedgerRecord(coreGrantsVerified = true, verifiedFingerprint = FINGERPRINT))
            assertThat(c.state.value.tier).isEqualTo(AccessTier.T2_PRIVILEGED_WORKER)
            assertThat(c.state.value.issue).isNull()
            assertThat(binderGrants.calls).isEmpty()
        }

    @Test
    fun aPermissionRequestWithoutAnswerForFiveSecondsIsFlaggedAndALateGrantStillCounts() =
        runTest {
            api.binderAlive = true
            grants.status = allGranted
            val c = controller()
            c.start()
            runCurrent()
            assertThat(c.state.value.permission).isEqualTo(ManagerPermission.NOT_REQUESTED)
            c.requestManagerPermission()
            runCurrent()
            assertThat(api.permissionRequests).isEqualTo(1)
            assertThat(c.state.value.permission).isEqualTo(ManagerPermission.PENDING)
            advanceTimeBy(PrivilegeController.PERMISSION_HANG_MS + 1)
            runCurrent()
            assertThat(c.state.value.issue).isEqualTo(PrivilegeIssue.AUTHORIZATION_HANG)
            assertThat(c.diagnostics.value.permissionRequestHanging).isTrue()

            api.events!!.onPermissionResult(PrivilegeController.PERMISSION_REQUEST_CODE, true)
            runCurrent()
            assertThat(c.state.value.permission).isEqualTo(ManagerPermission.GRANTED)
            assertThat(c.state.value.issue).isNull()
        }

    @Test
    fun aDialogTheUserIsStillReadingIsNotAHang() =
        runTest {
            api.binderAlive = true
            val c = controller()
            c.start()
            runCurrent()
            c.requestManagerPermission()
            runCurrent()
            // The manager's dialog covers Coulomb, which pauses.
            pausedAt = currentTime
            advanceTimeBy(PrivilegeController.PERMISSION_HANG_MS + 1)
            runCurrent()
            assertThat(c.state.value.permission).isEqualTo(ManagerPermission.PENDING)
            assertThat(c.state.value.issue).isNull()
            assertThat(c.diagnostics.value.permissionRequestHanging).isFalse()
        }

    @Test
    fun aDeniedRequestShowsTheDeniedRow() =
        runTest {
            api.binderAlive = true
            val c = controller()
            c.start()
            c.requestManagerPermission()
            runCurrent()
            api.events!!.onPermissionResult(PrivilegeController.PERMISSION_REQUEST_CODE, false)
            runCurrent()
            assertThat(c.state.value.issue).isEqualTo(PrivilegeIssue.PERMISSION_DENIED)
        }

    @Test
    fun theManagerDyingDropsToT1AndANewBinderResumes() =
        runTest {
            api.binderAlive = true
            api.permissionGranted = true
            grants.status = allGranted
            val c = controller()
            c.start()
            runCurrent()
            api.stopServer()
            runCurrent()
            assertThat(c.state.value.manager).isEqualTo(ManagerStatus.NOT_RUNNING)
            assertThat(c.state.value.server).isNull()
            assertThat(c.state.value.issue).isEqualTo(PrivilegeIssue.MANAGER_STOPPED)
            assertThat(c.state.value.tier).isEqualTo(AccessTier.T1_DURABLE_GRANTS)
            api.startServer()
            runCurrent()
            assertThat(c.state.value.tier).isEqualTo(AccessTier.T2_PRIVILEGED_WORKER)
            assertThat(c.state.value.issue).isNull()
        }

    @Test
    fun aServerBelowApi13CannotRunTheWorker() =
        runTest {
            api.binderAlive = true
            api.permissionGranted = true
            api.details = api.details.copy(apiVersion = 12)
            grants.status = allGranted
            val c = controller()
            c.start()
            runCurrent()
            assertThat(c.state.value.workerFailure).isEqualTo(WorkerFailure.SERVER_TOO_OLD)
            assertThat(c.state.value.issue).isEqualTo(PrivilegeIssue.SERVER_TOO_OLD)
            assertThat(c.state.value.tier).isEqualTo(AccessTier.T1_DURABLE_GRANTS)
            assertThat(api.binds).isEqualTo(0)
        }

    @Test
    fun grantsMissingAfterTheyWereVerifiedMeansRevoked() =
        runTest {
            ledger.record = LedgerRecord(coreGrantsVerified = true, verifiedFingerprint = "previous/build")
            val c = controller()
            c.start()
            runCurrent()
            assertThat(c.state.value.issue).isEqualTo(PrivilegeIssue.GRANTS_REVOKED)
            assertThat(c.diagnostics.value.fingerprintChanged).isTrue()
            // No automatic re-grant: the repair is one tap (SPEC 4.5).
            api.binderAlive = true
            api.permissionGranted = true
            workerGrantsForReal()
            api.startServer()
            runCurrent()
            assertThat(c.lastBootstrap.value).isNull()
            assertThat(c.bootstrap().allGranted).isTrue()
            runCurrent()
            assertThat(c.state.value.issue).isNull()
            assertThat(ledger.record.verifiedFingerprint).isEqualTo(FINGERPRINT)
        }

    /** SPEC 16.3 "revoke each grant": one missing core grant is enough, and the repair re-grants only it. */
    private fun revokedAloneThenRepaired(
        kind: GrantKind,
        revoked: GrantStatus,
    ) = runTest {
        grants.status = revoked
        ledger.record = LedgerRecord(coreGrantsVerified = true, verifiedFingerprint = FINGERPRINT)
        val c = controller()
        c.start()
        runCurrent()
        assertThat(c.state.value.issue).isEqualTo(PrivilegeIssue.GRANTS_REVOKED)
        assertThat(c.diagnostics.value.fingerprintChanged).isFalse()
        api.binderAlive = true
        api.permissionGranted = true
        workerGrantsForReal()
        api.startServer()
        runCurrent()
        val report = c.bootstrap()
        assertThat(report.items.filter { it.outcome == GrantOutcome.GRANTED }.map { it.grant }).containsExactly(kind)
        assertThat(report.allGranted).isTrue()
        runCurrent()
        assertThat(c.state.value.issue).isNull()
    }

    @Test
    fun batteryStatsRevokedAlone() = revokedAloneThenRepaired(GrantKind.BATTERY_STATS, allGranted.copy(batteryStats = false))

    @Test
    fun dumpRevokedAlone() = revokedAloneThenRepaired(GrantKind.DUMP, allGranted.copy(dump = false))

    @Test
    fun usageStatsPermissionRevokedAlone() =
        revokedAloneThenRepaired(GrantKind.PACKAGE_USAGE_STATS, allGranted.copy(usageStatsPermission = false))

    @Test
    fun usageStatsOpResetAlone() = revokedAloneThenRepaired(GrantKind.USAGE_STATS, allGranted.copy(usageStats = false))

    @Test
    fun crossUserAccessRevokedAlone() =
        revokedAloneThenRepaired(GrantKind.INTERACT_ACROSS_USERS, allGranted.copy(interactAcrossUsers = false))

    @Test
    fun aSimulatedFingerprintChangeIsDetectedAndGrantsAreReverified() =
        runTest {
            grants.status = allGranted
            val c = controller()
            c.start()
            runCurrent()
            assertThat(c.diagnostics.value.fingerprintChanged).isFalse()
            c.simulateFingerprintChange()
            runCurrent()
            assertThat(c.diagnostics.value.fingerprintChanged).isTrue()
            assertThat(ledger.record.verifiedFingerprint).isEqualTo(FINGERPRINT)
            assertThat(c.state.value.issue).isNull()
        }

    @Test
    fun aBindTimeoutFallsBackToT1InApp() =
        runTest {
            api.binderAlive = true
            api.permissionGranted = true
            api.connects = false
            grants.status = allGranted
            val c = controller()
            c.start()
            runCurrent()
            val dump = async { c.dumpText() }
            advanceTimeBy(WorkerConnector.BIND_TIMEOUT_MS + 1)
            runCurrent()
            val result = dump.await()
            assertThat(result).isInstanceOf(DumpResult.Complete::class.java)
            result as DumpResult.Complete
            assertThat(result.route).isEqualTo(Route.IN_APP)
            assertThat(result.value).isEqualTo("in-app dump\n")
            assertThat(c.state.value.workerFailure).isEqualTo(WorkerFailure.BIND_TIMEOUT)
            assertThat(c.state.value.issue).isEqualTo(PrivilegeIssue.AUTHORIZATION_HANG)
            // The next dump does not wait again.
            val again = async { c.dumpText() }
            runCurrent()
            assertThat((again.await() as DumpResult.Complete).route).isEqualTo(Route.IN_APP)
        }

    @Test
    fun withoutWorkerOrGrantsADumpIsUnavailable() =
        runTest {
            val c = controller()
            c.start()
            runCurrent()
            val result = c.dumpText()
            assertThat((result as DumpResult.Unavailable).reason).isEqualTo(FailureReason.NO_ACCESS)
            assertThat(inApp.calls).isEqualTo(0)
        }

    @Test
    fun aWorkerDyingMidDumpGivesAPartialSnapshot() =
        runTest {
            api.binderAlive = true
            api.permissionGranted = true
            grants.status = allGranted
            worker.dieDuringDump = true
            val c = controller()
            c.start()
            runCurrent()
            val result = c.dumpText() as DumpResult.Partial
            assertThat(result.reason).isEqualTo(PartialReason.WORKER_DIED)
            assertThat(result.value).isEqualTo("battery stats\n")
        }

    @Test
    fun aStreamThatEndsEarlyIsReportedAsTruncated() =
        runTest {
            api.binderAlive = true
            api.permissionGranted = true
            grants.status = allGranted
            val c = controller()
            c.start()
            runCurrent()
            val full = c.streamTestPattern(300_000) as DumpResult.Complete
            assertThat(full.value.complete).isTrue()
            val cut = c.streamTestPattern(300_000, truncateAt = 100_000) as DumpResult.Partial
            assertThat(cut.reason).isEqualTo(PartialReason.TRUNCATED)
            assertThat(cut.value!!.receivedBytes).isEqualTo(100_000)
            assertThat(cut.value.firstMismatchAt).isNull()
        }

    @Test
    fun whenTheWorkerCannotBindTheBootstrapUsesTheShizukuBinder() =
        runTest {
            api.binderAlive = true
            api.permissionGranted = true
            api.connects = false
            binderGrants.result = BinderGrantResult.OK
            binderGrants.onGrant = { grants.grant(it.id) }
            val c = controller()
            c.start()
            advanceTimeBy(3 * WorkerConnector.BIND_TIMEOUT_MS)
            runCurrent()
            val report = c.lastBootstrap.value!!
            val byKind = report.items.associateBy { it.grant }
            assertThat(byKind.getValue(GrantKind.BATTERY_STATS).route).isEqualTo(Route.SHIZUKU_BINDER)
            assertThat(byKind.getValue(GrantKind.BATTERY_STATS).outcome).isEqualTo(GrantOutcome.GRANTED)
            // The fake app-op call "succeeds" without changing anything: verification catches it.
            assertThat(byKind.getValue(GrantKind.USAGE_STATS).outcome).isEqualTo(GrantOutcome.NOT_VERIFIED)
            assertThat(report.adbCommands).containsExactly("adb shell appops set $PACKAGE GET_USAGE_STATS allow")
            assertThat(binderGrants.calls).contains("grant BATTERY_STATS $PACKAGE 0")
            assertThat(c.diagnostics.value.binderGrantPathWorked).isTrue()
        }

    @Test
    fun withoutAnyPrivilegedPathTheBootstrapOffersAdbLines() =
        runTest {
            val c = controller()
            c.start()
            runCurrent()
            val report = c.bootstrap()
            assertThat(report.items.map { it.outcome }.toSet()).containsExactly(GrantOutcome.NO_PRIVILEGED_PATH)
            assertThat(report.adbCommands)
                .containsExactly(
                    "adb shell pm grant $PACKAGE android.permission.BATTERY_STATS",
                    "adb shell pm grant $PACKAGE android.permission.DUMP",
                    "adb shell pm grant $PACKAGE android.permission.PACKAGE_USAGE_STATS",
                    "adb shell appops set $PACKAGE GET_USAGE_STATS allow",
                    "adb shell pm grant $PACKAGE android.permission.INTERACT_ACROSS_USERS",
                ).inOrder()
        }

    @Test
    fun smallCallsCarryTheWorkerStatus() =
        runTest {
            api.binderAlive = true
            api.permissionGranted = true
            grants.status = allGranted
            worker.sysfs = mapOf("battery/capacity" to "80\n".toByteArray())
            val c = controller()
            c.start()
            runCurrent()
            assertThat(
                (c.readSysfs(SysfsRoot.POWER_SUPPLY, "battery/capacity") as PrivilegedResult.Success).value.decodeToString(),
            ).isEqualTo("80\n")
            assertThat(
                (c.readSysfs(SysfsRoot.POWER_SUPPLY, "battery/serial") as PrivilegedResult.Failure).reason,
            ).isEqualTo(FailureReason.ACCESS_DENIED)
        }

    @Test
    fun releaseBuildsRefuseTheDrills() =
        runTest {
            api.binderAlive = true
            api.permissionGranted = true
            grants.status = allGranted
            val c = controller(debuggable = false)
            c.start()
            runCurrent()
            assertThat((c.streamTestPattern(1000) as DumpResult.Unavailable).reason).isEqualTo(FailureReason.NOT_PERMITTED)
            c.setSimulatedBindHang(true)
            assertThat(c.dumpText()).isInstanceOf(DumpResult.Complete::class.java)
            ledger.record = LedgerRecord(true, FINGERPRINT)
            c.simulateFingerprintChange()
            assertThat(ledger.record.verifiedFingerprint).isEqualTo(FINGERPRINT)
        }

    private companion object {
        const val PACKAGE = "io.github.akane599.coulomb"
        const val UID = 10_123
        const val FINGERPRINT = "test/build/1"
    }

    @Test
    fun aRefusalLineIsNotPermittedNeverData() =
        runTest {
            grants.status = allGranted
            inApp.payload = "Permission Denial: can't dump BatteryStatsService from from pid=1, uid=10000 due to missing x\n".toByteArray()
            val c = controller()
            c.start()
            runCurrent()
            val result = c.dumpText() as DumpResult.Unavailable
            assertThat(result.reason).isEqualTo(FailureReason.NOT_PERMITTED)
            assertThat(result.detail).startsWith("Permission Denial: can't dump BatteryStatsService")
        }

    @Test
    fun aSecurityExceptionFromTheDumpIsNotPermitted() =
        runTest {
            grants.status = allGranted
            inApp.payload = "Security exception: MATCH_ANY_USER flag requires INTERACT_ACROSS_USERS permission\n".toByteArray()
            inApp.throwAfterWrite = SecurityException("MATCH_ANY_USER flag requires INTERACT_ACROSS_USERS permission")
            val c = controller()
            c.start()
            runCurrent()
            val result = c.dumpText() as DumpResult.Unavailable
            assertThat(result.reason).isEqualTo(FailureReason.NOT_PERMITTED)
        }

    @Test
    fun inAppProtoNeedsCrossUserAccessButHistoryDoesNot() =
        runTest {
            grants.status = allGranted.copy(interactAcrossUsers = false)
            val c = controller()
            c.start()
            runCurrent()
            assertThat((c.dumpText() as DumpResult.Unavailable).reason).isEqualTo(FailureReason.NO_ACCESS)
            assertThat(inApp.calls).isEqualTo(0)
            val history = c.dumpBatteryStats(BatteryStatsMode.HISTORY) { String(it.readBytes()) }
            assertThat((history as DumpResult.Complete).route).isEqualTo(Route.IN_APP)
            // DumpUtils also needs the PACKAGE_USAGE_STATS permission itself, not just the op.
            grants.status = allGranted.copy(usageStatsPermission = false)
            val noPermission = c.dumpBatteryStats(BatteryStatsMode.HISTORY) { String(it.readBytes()) }
            assertThat((noPermission as DumpResult.Unavailable).reason).isEqualTo(FailureReason.NO_ACCESS)
        }

    @Test
    fun aShortDumpThatIsNotARefusalReachesTheParserWhole() =
        runTest {
            grants.status = allGranted
            inApp.payload = "Perm".toByteArray()
            val c = controller()
            c.start()
            runCurrent()
            assertThat((c.dumpText() as DumpResult.Complete).value).isEqualTo("Perm")
        }

    @Test
    fun inAppFirstSkipsTheWorkerWhenTheGrantsAllowIt() =
        runTest {
            api.binderAlive = true
            api.permissionGranted = true
            grants.status = allGranted
            val c = controller()
            c.start()
            runCurrent()
            val inAppFirst = c.dumpBatteryStats(BatteryStatsMode.PROTO, route = RoutePreference.IN_APP_FIRST) { String(it.readBytes()) }
            assertThat((inAppFirst as DumpResult.Complete).route).isEqualTo(Route.IN_APP)
            assertThat((c.dumpText() as DumpResult.Complete).route).isEqualTo(Route.WORKER)
            // Without the grants the worker still serves it.
            grants.status = allGranted.copy(dump = false, batteryStats = false)
            val fallback = c.dumpBatteryStats(BatteryStatsMode.PROTO, route = RoutePreference.IN_APP_FIRST) { String(it.readBytes()) }
            assertThat((fallback as DumpResult.Complete).route).isEqualTo(Route.WORKER)
        }
}
