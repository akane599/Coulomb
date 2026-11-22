// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.privileged.worker

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.ParcelFileDescriptor
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.akane599.coulomb.core.model.cmd.StandbyBucket
import io.github.akane599.coulomb.core.privileged.ICoulombPrivileged
import io.github.akane599.coulomb.core.privileged.WorkerStatusCode
import io.github.akane599.coulomb.core.privileged.system.ShellRunner
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowBinder
import java.io.OutputStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * The worker's own guards (SPEC 4.3, ADR-0060 §5), on the real class with Robolectric's binder and
 * package manager: who may call it, and which packages an action may name. No command runs; the
 * shell is a recorder (audit TEST-001).
 */
@RunWith(RobolectricTestRunner::class)
class CoulombPrivilegedServiceTest {
    @get:Rule
    val files = TemporaryFolder()

    private val context: Context = RuntimeEnvironment.getApplication()
    private val owner = context.applicationInfo.uid
    private val ran = mutableListOf<List<String>>()
    private val shell =
        object : ShellRunner(TIMEOUT_MS) {
            override fun run(
                argv: List<String>,
                sink: OutputStream?,
                maxBytes: Long,
            ): Result {
                ran += argv
                return Result(0, ByteArray(0), "", timedOut = false, truncated = false)
            }
        }
    private val service = CoulombPrivilegedService(context, shell)

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

    private fun pipe(): ParcelFileDescriptor = ParcelFileDescriptor.open(files.newFile(), ParcelFileDescriptor.MODE_READ_WRITE)

    private fun argumentsFor(method: Method): Array<Any?> =
        method.parameterTypes
            .map { type ->
                when (type) {
                    Int::class.javaPrimitiveType -> 0
                    Long::class.javaPrimitiveType -> 0L
                    Boolean::class.javaPrimitiveType -> false
                    String::class.java -> APP
                    IntArray::class.java -> IntArray(1)
                    ParcelFileDescriptor::class.java -> pipe()
                    else -> error("no argument for ${type.name} in ${method.name}")
                }
            }.toTypedArray()

    /** By reflection, so a method added to the AIDL without the check fails here. */
    @Test
    fun everyMethodRefusesAnotherUid() {
        install(APP, APP_UID)
        ShadowBinder.setCallingUid(owner + 1)
        // destroy() has a test of its own: were its check gone, it would end the test's process.
        val methods = ICoulombPrivileged::class.java.declaredMethods.filter { it.name != "destroy" && !it.isSynthetic }
        assertThat(methods.size).isAtLeast(KNOWN_METHODS)

        for (method in methods) {
            val arguments = argumentsFor(method)
            val thrown = assertThrows(method.name, InvocationTargetException::class.java) { method.invoke(service, *arguments) }
            assertWithMessage(method.name).that(thrown.cause).isInstanceOf(SecurityException::class.java)
            // A refused caller's pipe is closed too, or its descriptor would stay open in the worker.
            arguments.filterIsInstance<ParcelFileDescriptor>().forEach {
                assertWithMessage("${method.name}: pipe closed").that(it.fileDescriptor.valid()).isFalse()
            }
        }
        assertThat(ran).isEmpty()
    }

    @Test
    fun destroyRefusesAnotherUid() {
        ShadowBinder.setCallingUid(owner + 1)
        assertThrows(SecurityException::class.java) { service.destroy() }
    }

    /** ADR-0060 §5: an action names an installed app, never Coulomb and never a system UID's package. */
    @Test
    fun actionsRefuseCoulombSystemAndUnknownPackages() {
        install(APP, APP_UID)
        install(SYSTEM, SYSTEM_UID)
        ShadowBinder.setCallingUid(owner)
        val actions: List<(String, IntArray) -> String?> =
            listOf(
                { name, status -> service.forceStop(name, status) },
                { name, status -> service.setRunInBackground(name, false, status) },
                { name, status -> service.setStandbyBucket(name, StandbyBucket.RARE.value, status) },
                { name, status -> service.setDozeAllowlisted(name, true, status) },
            )

        for (action in actions) {
            for (refused in listOf(context.packageName, SYSTEM, "not.installed.app", "not a package")) {
                val status = IntArray(1)
                action(refused, status)
                assertWithMessage(refused).that(status[0]).isEqualTo(WorkerStatusCode.INVALID_ARGUMENT)
            }
        }
        assertThat(ran).isEmpty()

        // The same calls on an app go through, so the refusals above are the guard's and not the test's.
        for (action in actions) {
            val status = IntArray(1)
            action(APP, status)
            assertThat(status[0]).isEqualTo(WorkerStatusCode.OK)
        }
        assertThat(ran).hasSize(actions.size)
        assertThat(ran.flatten()).contains(APP)
    }

    @Test
    fun aBucketOutsideTheAllowlistIsRefused() {
        install(APP, APP_UID)
        ShadowBinder.setCallingUid(owner)
        for (bucket in listOf(StandbyBucket.EXEMPTED.value, StandbyBucket.NEVER.value, 0)) {
            val status = IntArray(1)
            service.setStandbyBucket(APP, bucket, status)
            assertWithMessage("bucket $bucket").that(status[0]).isEqualTo(WorkerStatusCode.INVALID_ARGUMENT)
        }
        assertThat(ran).isEmpty()
    }

    @Test
    fun malformedDozeConstantsAreRefused() {
        ShadowBinder.setCallingUid(owner)
        for (value in listOf("inactive_to=1000;reboot", "inactive_to", "a=1,,b=2", "a=\$(id)")) {
            val status = IntArray(1)
            service.putDozeConstants(value, status)
            assertWithMessage(value).that(status[0]).isEqualTo(WorkerStatusCode.INVALID_ARGUMENT)
        }
        assertThat(ran).isEmpty()

        val status = IntArray(1)
        service.putDozeConstants("inactive_to=1000,sensing_to=0", status)
        assertThat(status[0]).isEqualTo(WorkerStatusCode.OK)
        assertThat(ran.single()).contains("inactive_to=1000,sensing_to=0")
    }

    private companion object {
        const val TIMEOUT_MS = 1_000L
        const val APP = "com.example.app"
        const val APP_UID = 10_456
        const val SYSTEM = "com.android.systemui"
        const val SYSTEM_UID = 1000

        /** The AIDL's methods without destroy(), when this test was written. */
        const val KNOWN_METHODS = 23
    }
}
