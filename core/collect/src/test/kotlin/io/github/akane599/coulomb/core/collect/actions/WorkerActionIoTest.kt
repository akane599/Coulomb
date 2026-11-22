// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.actions

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.privileged.FailureReason
import io.github.akane599.coulomb.core.privileged.PrivilegedAccess
import io.github.akane599.coulomb.core.privileged.PrivilegedAction
import io.github.akane599.coulomb.core.privileged.PrivilegedResult
import io.github.akane599.coulomb.core.privileged.Route
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.lang.reflect.Proxy

/**
 * The app's side of an action, on the real class with Robolectric's package manager: which
 * packages it will not name to the worker, and how it reads a lost reply (audit TEST-001).
 */
@RunWith(RobolectricTestRunner::class)
class WorkerActionIoTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private var reply: PrivilegedResult<String> = PrivilegedResult.Success("", Route.WORKER)

    /** Answers the two members [WorkerActionIo] uses here; anything else is a test error. */
    private val access =
        Proxy.newProxyInstance(PrivilegedAccess::class.java.classLoader, arrayOf(PrivilegedAccess::class.java)) { _, method, _ ->
            when (method.name) {
                "managerLaunchIntent" -> Intent().setPackage(MANAGER)

                // A suspend function that answers at once returns its value.
                "act" -> reply

                else -> error("unused: ${method.name}")
            }
        } as PrivilegedAccess
    private val io = WorkerActionIo(context, access)

    private fun install(
        name: String,
        uid: Int,
    ) {
        val app =
            ApplicationInfo().also {
                it.packageName = name
                it.uid = uid
            }
        shadowOf(context.packageManager).installPackage(
            PackageInfo().also {
                it.packageName = name
                it.applicationInfo = app
            },
        )
    }

    /** ADR-0060 §5: stopping the manager would stop the worker under the action; a system UID's package is not an app to tune. */
    @Test
    fun appBlockProtectsCoulombTheManagerAndSystemUids() {
        install(MANAGER, MANAGER_UID)
        install(SYSTEM, SYSTEM_UID)
        install(SYSTEM_IN_PROFILE, PROFILE_SYSTEM_UID)
        install(APP, APP_UID)

        assertThat(io.appBlock(context.packageName)).isEqualTo(ActionBlock.PROTECTED)
        assertThat(io.appBlock(MANAGER)).isEqualTo(ActionBlock.PROTECTED)
        assertThat(io.appBlock(SYSTEM)).isEqualTo(ActionBlock.PROTECTED)
        // The app id decides, in any Android user.
        assertThat(io.appBlock(SYSTEM_IN_PROFILE)).isEqualTo(ActionBlock.PROTECTED)
        assertThat(io.appBlock("not.installed.app")).isEqualTo(ActionBlock.NOT_INSTALLED)
        assertThat(io.appBlock(APP)).isNull()
    }

    /** A timeout or a dead worker is a reply lost, not a command that did not run (audit DATA-001). */
    @Test
    fun actMarksALostReplyUnclear() =
        runTest {
            val action = PrivilegedAction.ForceStop(APP)

            reply = PrivilegedResult.Failure(FailureReason.TIMEOUT, null)
            assertThat(io.act(action)).isEqualTo(Ran(ok = false, noWorker = false, output = null, unclear = true))
            reply = PrivilegedResult.Failure(FailureReason.WORKER_DIED, "gone")
            assertThat(io.act(action)).isEqualTo(Ran(ok = false, noWorker = false, output = "gone", unclear = true))

            reply = PrivilegedResult.Failure(FailureReason.NO_ACCESS, null)
            assertThat(io.act(action)).isEqualTo(Ran(ok = false, noWorker = true, output = null))
            reply = PrivilegedResult.Failure(FailureReason.COMMAND_FAILED, "exit 1")
            assertThat(io.act(action)).isEqualTo(Ran(ok = false, noWorker = false, output = "exit 1"))
            reply = PrivilegedResult.Success("done", Route.WORKER)
            assertThat(io.act(action)).isEqualTo(Ran(ok = true, noWorker = false, output = "done"))
        }

    private companion object {
        const val APP = "com.example.app"
        const val APP_UID = 10_456
        const val MANAGER = "moe.shizuku.privileged.api"
        const val MANAGER_UID = 10_457
        const val SYSTEM = "com.android.systemui"
        const val SYSTEM_UID = 1000
        const val SYSTEM_IN_PROFILE = "com.android.settings"
        const val PROFILE_SYSTEM_UID = 1_001_000
    }
}
