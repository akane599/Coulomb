// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.privileged.client

import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.model.GrantStatus
import io.github.akane599.coulomb.core.model.ManagerPermission
import io.github.akane599.coulomb.core.model.PrivilegeIssue
import io.github.akane599.coulomb.core.privileged.PrivilegeConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** The manager's authorization request and its hang check (SPEC 4.5). */
@OptIn(ExperimentalCoroutinesApi::class)
class PermissionRequestTest {
    private val pipes = FakePipes()
    private val api = FakeShizukuApi(FakeWorker(pipes))
    private val grants = FakeGrants()
    private val foreground = MutableStateFlow(Foreground())
    private var pausedAt: Long?
        get() = foreground.value.pausedAt
        set(value) = foreground.update { it.copy(pausedAt = value) }

    private val allGranted =
        GrantStatus(batteryStats = true, dump = true, usageStats = true, usageStatsPermission = true, interactAcrossUsers = true)

    private fun TestScope.controller(): PrivilegeController =
        PrivilegeController(
            config = PrivilegeConfig("io.github.akane599.coulomb", 10_123, FakeWorker.VERSION, true, "test/build/1"),
            platform = PrivilegePlatform(api, grants, FakeLedger(), FakeBinderGrants(), FakeInAppDumper(pipes), pipes) { foreground },
            scope = backgroundScope,
            io = StandardTestDispatcher(testScheduler),
            clock = { currentTime },
            processStartMs = null,
        )

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
    fun aSecondTapWithinFiveSecondsDoesNotAskAgainButALaterOneDoes() =
        runTest {
            api.binderAlive = true
            val c = controller()
            c.start()
            runCurrent()
            c.requestManagerPermission()
            c.requestManagerPermission()
            runCurrent()
            assertThat(api.permissionRequests).isEqualTo(1)
            // The dialog covered Coulomb, so no hang was flagged; a tap after 5 s asks again.
            pausedAt = currentTime
            advanceTimeBy(PrivilegeController.PERMISSION_HANG_MS + 1)
            runCurrent()
            c.requestManagerPermission()
            runCurrent()
            assertThat(api.permissionRequests).isEqualTo(2)
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

    /** Audit BUG-022: a request left without an answer is open to ask again once Coulomb is back. */
    @Test
    fun leavingDuringTheRequestLeavesItOpenToAskAgainOnceBack() =
        runTest {
            api.binderAlive = true
            val c = controller()
            c.start()
            runCurrent()
            c.requestManagerPermission()
            runCurrent()
            // The user goes Home; the check finds Coulomb away and waits for it, however long.
            pausedAt = currentTime
            advanceTimeBy(10 * PrivilegeController.PERMISSION_HANG_MS)
            runCurrent()
            assertThat(c.state.value.permission).isEqualTo(ManagerPermission.PENDING)
            // Back, and still no answer 5 s later.
            foreground.update { it.copy(resumedAt = currentTime) }
            runCurrent()
            advanceTimeBy(PrivilegeController.PERMISSION_HANG_MS - 1)
            runCurrent()
            assertThat(c.state.value.permission).isEqualTo(ManagerPermission.PENDING)
            advanceTimeBy(2)
            runCurrent()
            assertThat(c.state.value.permission).isEqualTo(ManagerPermission.NOT_REQUESTED)
            assertThat(c.state.value.issue).isNull()
            // Asking again asks; an answer to the first request would still count.
            c.requestManagerPermission()
            runCurrent()
            assertThat(api.permissionRequests).isEqualTo(2)
            api.events!!.onPermissionResult(PrivilegeController.PERMISSION_REQUEST_CODE, true)
            runCurrent()
            assertThat(c.state.value.permission).isEqualTo(ManagerPermission.GRANTED)
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
}
