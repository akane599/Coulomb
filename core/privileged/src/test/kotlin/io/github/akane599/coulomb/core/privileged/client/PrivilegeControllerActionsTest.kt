// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.privileged.client

import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.model.GrantStatus
import io.github.akane599.coulomb.core.model.cmd.StandbyBucket
import io.github.akane599.coulomb.core.privileged.FailureReason
import io.github.akane599.coulomb.core.privileged.PrivilegeConfig
import io.github.akane599.coulomb.core.privileged.PrivilegedAction
import io.github.akane599.coulomb.core.privileged.PrivilegedResult
import io.github.akane599.coulomb.core.privileged.WorkerStatusCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** The Action Center's writes through the controller (SPEC 13.1 "typed worker methods"; ADR-0060 §5). */
@OptIn(ExperimentalCoroutinesApi::class)
class PrivilegeControllerActionsTest {
    private val pipes = FakePipes()
    private val worker = FakeWorker(pipes)
    private val api = FakeShizukuApi(worker)
    private val grants = FakeGrants()

    private fun TestScope.controller(): PrivilegeController =
        PrivilegeController(
            config = PrivilegeConfig(PACKAGE, UID, FakeWorker.VERSION, debuggable = true, fingerprint = "test/build/1"),
            platform = PrivilegePlatform(api, grants, FakeLedger(), FakeBinderGrants(), FakeInAppDumper(pipes), pipes) { null },
            scope = backgroundScope,
            io = StandardTestDispatcher(testScheduler),
            clock = { currentTime },
            processStartMs = null,
        )

    private val allGranted =
        GrantStatus(batteryStats = true, dump = true, usageStats = true, usageStatsPermission = true, interactAcrossUsers = true)

    @Test
    fun eachActionIsItsOwnWorkerCallAndARefusalComesBack() =
        runTest {
            api.binderAlive = true
            api.permissionGranted = true
            grants.status = allGranted
            val c = controller()
            c.start()
            runCurrent()
            val actions =
                listOf(
                    PrivilegedAction.ForceStop("com.example.app"),
                    PrivilegedAction.SetRunInBackground("com.example.app", allowed = false),
                    PrivilegedAction.SetStandbyBucket("com.example.app", StandbyBucket.RARE),
                    PrivilegedAction.SetDozeAllowlisted("com.example.app", listed = true),
                    PrivilegedAction.PutDozeConstants("inactive_to=900000"),
                    PrivilegedAction.PutDozeConstants(null),
                    PrivilegedAction.SetBatterySaver(true),
                    PrivilegedAction.ResetBatteryStats,
                )
            actions.forEach { assertThat(c.act(it)).isInstanceOf(PrivilegedResult.Success::class.java) }
            assertThat(worker.actions)
                .containsExactly(
                    "forceStop:com.example.app",
                    "setRunInBackground:com.example.app:false",
                    "setStandbyBucket:com.example.app:40",
                    "setDozeAllowlisted:com.example.app:true",
                    "putDozeConstants:inactive_to=900000",
                    "deleteDozeConstants",
                    "setBatterySaver:true",
                    "resetBatteryStats",
                ).inOrder()
            worker.actionStatus = WorkerStatusCode.INVALID_ARGUMENT
            assertThat(
                (c.act(PrivilegedAction.ForceStop("-a")) as PrivilegedResult.Failure).reason,
            ).isEqualTo(FailureReason.INVALID_ARGUMENT)
            // A put the worker refuses is a failure, never the key's deletion.
            worker.actions.clear()
            assertThat(c.act(PrivilegedAction.PutDozeConstants("inactive_to=900000"))).isInstanceOf(PrivilegedResult.Failure::class.java)
            assertThat(worker.actions).containsExactly("putDozeConstants:inactive_to=900000")
            // The preview's command is built for Coulomb's own user.
            assertThat(c.actionArgv(PrivilegedAction.ForceStop("com.example.app"))).contains((UID / 100_000).toString())
        }

    private companion object {
        const val PACKAGE = "io.github.akane599.coulomb"
        const val UID = 10_123
    }
}
