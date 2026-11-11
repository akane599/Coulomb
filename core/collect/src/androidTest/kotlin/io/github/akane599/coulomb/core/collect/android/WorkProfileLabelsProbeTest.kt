// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.android

import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.collect.engine.AppLabel
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Whether Coulomb can name a work profile's apps (docs/questions.md, 2026-11-08): it resolves a
 * UID with `getPackagesForUid` from the main user. The test APK holds what the app holds
 * (src/androidTest/AndroidManifest.xml) and asks for its own copy installed in the profile, without
 * `INTERACT_ACROSS_USERS` (Basic mode) and with it (Full mode grants it). Results go to [TAG].
 *
 * Needs a profile with this package in it, so it runs only when given one:
 * `pm create-user --profileOf 0 --managed work`, `am start-user <id>`,
 * `pm install-existing --user <id> io.github.akane599.coulomb.core.collect.test`, then
 * `am instrument -w -e profileUser <id> -e class ...WorkProfileLabelsProbeTest ...`.
 */
@RunWith(AndroidJUnit4::class)
class WorkProfileLabelsProbeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation = instrumentation.uiAutomation

    @After
    fun revoke() {
        shell("pm revoke ${context.packageName} android.permission.INTERACT_ACROSS_USERS")
    }

    @Test
    fun aWorkProfileUidResolvesOrNot() {
        val profile = InstrumentationRegistry.getArguments().getString("profileUser")?.toIntOrNull()
        assumeTrue("needs -e profileUser <id> with this package installed there", profile != null)
        val appId = Process.myUid() % PER_USER_RANGE
        val workUid = checkNotNull(profile) * PER_USER_RANGE + appId

        shell("pm revoke ${context.packageName} android.permission.INTERACT_ACROSS_USERS")
        val basic = probe(workUid)
        shell("pm grant ${context.packageName} android.permission.INTERACT_ACROSS_USERS")
        val full = probe(workUid)

        Log.i(TAG, "profile=$profile workUid=$workUid ownUid=${Process.myUid()}")
        Log.i(TAG, "withoutCrossUser: $basic")
        Log.i(TAG, "withCrossUser: $full")
        // The app is installed for the main user too, so it is named either way, and nothing throws.
        assertThat(
            basic.label.getOrThrow()?.label,
        ).isEqualTo(context.packageManager.getApplicationLabel(context.applicationInfo).toString())
        assertThat(full.label.getOrThrow()?.label).isEqualTo(basic.label.getOrThrow()?.label)
    }

    private class Probe(
        val granted: Boolean,
        val packages: Result<List<String>?>,
        val name: Result<String?>,
        val label: Result<AppLabel?>,
    ) {
        override fun toString(): String =
            "granted=$granted getPackagesForUid=${packages.getOrElse { "threw $it" }} " +
                "getNameForUid=${name.getOrElse { "threw $it" }} label=${label.getOrElse { "threw $it" }}"
    }

    private fun probe(uid: Int): Probe {
        val pm = context.packageManager
        val granted = context.checkSelfPermission("android.permission.INTERACT_ACROSS_USERS") == PackageManager.PERMISSION_GRANTED
        return Probe(
            granted,
            runCatching { pm.getPackagesForUid(uid)?.toList() },
            runCatching { pm.getNameForUid(uid) },
            runCatching { AndroidAppLabels(context).label(uid) },
        )
    }

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
    }

    private companion object {
        const val TAG = "CoulombProfile"

        /**
         * `UserHandle.PER_USER_RANGE`, hidden: a user's UIDs start at `userId * 100000`
         * (core/java/android/os/UserHandle.java, android-17.0.0_r1 L51, android-16.0.0_r4 L49).
         */
        const val PER_USER_RANGE = 100_000
    }
}
