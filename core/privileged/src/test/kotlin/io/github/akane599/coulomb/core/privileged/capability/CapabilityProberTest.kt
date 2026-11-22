// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.privileged.capability

import android.content.ContextWrapper
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.model.AccessTier
import io.github.akane599.coulomb.core.model.Capability
import io.github.akane599.coulomb.core.model.CapabilityId
import io.github.akane599.coulomb.core.model.CapabilityState
import io.github.akane599.coulomb.core.model.FixAction
import io.github.akane599.coulomb.core.model.GrantStatus
import io.github.akane599.coulomb.core.model.kernel.KernelPaths
import io.github.akane599.coulomb.core.model.parse.ParseReport
import io.github.akane599.coulomb.core.model.parse.Parsed
import io.github.akane599.coulomb.core.privileged.PrivilegeConfig
import io.github.akane599.coulomb.core.privileged.client.FakeBinderGrants
import io.github.akane599.coulomb.core.privileged.client.FakeGrants
import io.github.akane599.coulomb.core.privileged.client.FakeInAppDumper
import io.github.akane599.coulomb.core.privileged.client.FakeLedger
import io.github.akane599.coulomb.core.privileged.client.FakePipes
import io.github.akane599.coulomb.core.privileged.client.FakeShizukuApi
import io.github.akane599.coulomb.core.privileged.client.FakeWorker
import io.github.akane599.coulomb.core.privileged.client.Foreground
import io.github.akane599.coulomb.core.privileged.client.PrivilegeController
import io.github.akane599.coulomb.core.privileged.client.PrivilegePlatform
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** The probe over the real controller and transfer, with a stand-in for Shizuku and the worker. */
@OptIn(ExperimentalCoroutinesApi::class)
class CapabilityProberTest {
    private val pipes = FakePipes()
    private val worker = FakeWorker(pipes)
    private val api = FakeShizukuApi(worker)
    private val grants = FakeGrants()
    private val foreground = MutableStateFlow(Foreground())
    private val config = PrivilegeConfig(PACKAGE, UID, FakeWorker.VERSION, true, FINGERPRINT)

    /** A prober on a connected root server. No probe used here touches the context. */
    private fun TestScope.rootProber(): CapabilityProber {
        api.binderAlive = true
        api.permissionGranted = true
        api.details = api.details.copy(uid = 0)
        worker.workerUid = 0
        grants.status =
            GrantStatus(batteryStats = true, dump = true, usageStats = true, usageStatsPermission = true, interactAcrossUsers = true)
        val platform = PrivilegePlatform(api, grants, FakeLedger(), FakeBinderGrants(), FakeInAppDumper(pipes), pipes) { foreground }
        val controller = PrivilegeController(config, platform, backgroundScope, StandardTestDispatcher(testScheduler), { currentTime })
        controller.start()
        runCurrent()
        return CapabilityProber(object : ContextWrapper(null) {}, controller, config) {
            Parsed(null, ParseReport("lab", 0, true, emptyList(), 0, emptyMap()))
        }
    }

    /** Rows found without root, which a root connection reads again. */
    private val foundWithoutRoot =
        KernelRows.IDS.map {
            Capability(
                it,
                CapabilityState.UNAVAILABLE,
                AccessTier.T3_ROOT,
                KernelRows.NEEDS_ROOT,
                FixAction.NEEDS_ROOT,
                0,
                FINGERPRINT,
                emptyMap(),
            )
        }

    private fun file(
        path: String,
        body: String,
    ) = "@$path ${body.length}\n$body\n"

    private val reasons = "${KernelPaths.WAKEUP_REASONS}/${KernelPaths.LAST_RESUME_REASON}"
    private val times = "${KernelPaths.WAKEUP_REASONS}/${KernelPaths.LAST_SUSPEND_TIME}"
    private val counters = file("${KernelPaths.SUSPEND_STATS}/success", "12\n") + file("${KernelPaths.SUSPEND_STATS}/fail", "1\n")

    /** The rows read from the kernel-file stream: S9, S25 and S26. */
    private fun List<Capability>.files() =
        filter { it.id in KernelRows.IDS && it.id != CapabilityId.KERNEL_LOG }.also {
            check(it.size == FILE_ROWS)
        }

    @Test
    fun aWholeKernelReadReportsItsRows() =
        runTest {
            val prober = rootProber()
            worker.kernelFiles = (counters + file(reasons, "170 glink\n") + file(times, "0.1 12.0\n") + "#end\n").toByteArray()

            val rows = checkNotNull(prober.kernelAgain(foundWithoutRoot)).associateBy { it.id }

            assertThat(rows.getValue(CapabilityId.SUSPEND_STATS).state).isEqualTo(CapabilityState.AVAILABLE)
            assertThat(rows.getValue(CapabilityId.WAKEUP_REASONS).state).isEqualTo(CapabilityState.AVAILABLE)
        }

    /** Audit BUG-006: what a read cut short did not reach says nothing about those paths. */
    @Test
    fun aCutKernelReadReportsNoKernelRowAvailable() =
        runTest {
            val prober = rootProber()
            // The worker dies inside last_suspend_time's payload.
            worker.kernelFiles = (counters + file(reasons, "170 glink\n") + "@$times 40\n0.1").toByteArray()
            worker.dieDuringDump = true

            val died = checkNotNull(prober.kernelAgain(foundWithoutRoot)).files()

            assertThat(died.map { it.state }.distinct()).containsExactly(CapabilityState.DEGRADED)
            assertThat(died.map { it.reason }.distinct()).containsExactly("read cut short")
        }

    @Test
    fun aKernelReadWithoutItsEndClosesNoPath() =
        runTest {
            val prober = rootProber()
            // The transfer is whole, the stream is not: the paths after it were never tried.
            worker.kernelFiles = file("${KernelPaths.SUSPEND_STATS}/success", "12\n").toByteArray()

            val unended = checkNotNull(prober.kernelAgain(foundWithoutRoot)).files()

            assertThat(unended.map { it.state }.distinct()).containsExactly(CapabilityState.DEGRADED)
            assertThat(unended.mapNotNull { it.reason }.filter { it.startsWith("closed") }).isEmpty()
        }

    /**
     * Audit BUG-008: a row is filed at the lowest tier it works at (ADR-0047 §7). What the worker
     * does as shell is T2 under a root server too; only kernel data is root's.
     */
    @Test
    fun workerRowsUnderARootServerAreFiledAtTheShellTier() =
        runTest {
            val prober = rootProber()
            // Without INTERACT_ACROSS_USERS the batterystats dumps are the worker's, not Coulomb's own.
            grants.status = grants.status.copy(interactAcrossUsers = false)

            val rows = prober.probeAll().associateBy { it.id }

            val shellLevel =
                listOf(
                    CapabilityId.BATTERY_DUMP,
                    CapabilityId.BATTERYSTATS_PROTO,
                    CapabilityId.STANDBY_BUCKETS,
                    CapabilityId.PROTECTION_SETTINGS,
                    CapabilityId.WORKER_BIND,
                )
            assertThat(shellLevel.associateWith { rows.getValue(it).tier }.filterValues { it != AccessTier.T2_PRIVILEGED_WORKER }).isEmpty()
            assertThat(KernelRows.IDS.map { rows.getValue(it).tier }.distinct()).containsExactly(AccessTier.T3_ROOT)
            // As root a read does not show that shell could make it (SPEC 5 S23: "T2 if readable, else T3").
            assertThat(rows.getValue(CapabilityId.POWER_SUPPLY_SYSFS).tier).isEqualTo(AccessTier.T3_ROOT)
        }

    /** The one worker row whose tier follows the server's uid is probed again when that changes. */
    @Test
    fun aSwitchToShellProbesPowerSupplyAgain() =
        runTest {
            val prober = rootProber()
            worker.sysfs = mapOf("battery/capacity" to "50\n".toByteArray())
            val underRoot = prober.probeAll().filter { it.id in KernelRows.IDS || it.id == CapabilityId.POWER_SUPPLY_SYSFS }
            assertThat(underRoot.single { it.id == CapabilityId.POWER_SUPPLY_SYSFS }.tier).isEqualTo(AccessTier.T3_ROOT)

            api.stopServer()
            runCurrent()
            api.details = api.details.copy(uid = SHELL_UID)
            worker.workerUid = SHELL_UID
            api.startServer()
            runCurrent()
            val again = checkNotNull(prober.kernelAgain(underRoot)).associateBy { it.id }

            assertThat(again.keys).containsExactlyElementsIn(KernelRows.IDS + CapabilityId.POWER_SUPPLY_SYSFS)
            assertThat(again.getValue(CapabilityId.POWER_SUPPLY_SYSFS).tier).isEqualTo(AccessTier.T2_PRIVILEGED_WORKER)
            assertThat(again.getValue(CapabilityId.WAKEUP_SOURCES).reason).isEqualTo(KernelRows.NEEDS_ROOT)
        }

    private companion object {
        const val PACKAGE = "io.github.akane599.coulomb"
        const val UID = 10_123
        const val SHELL_UID = 2000
        const val FILE_ROWS = 3
        const val FINGERPRINT = "test/build/1"
    }
}
