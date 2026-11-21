// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.privileged

import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.model.cmd.StandbyBucket
import org.junit.Test

class ActionCommandsTest {
    private val pkg = "com.example.app"
    private val cmd = "/system/bin/cmd"

    @Test
    fun everyAppActionNamesItsUser() {
        assertThat(ActionCommands.argv(PrivilegedAction.ForceStop(pkg), 10))
            .containsExactly(cmd, "activity", "force-stop", "--user", "10", pkg)
            .inOrder()
        assertThat(ActionCommands.argv(PrivilegedAction.SetRunInBackground(pkg, allowed = false), 0))
            .containsExactly(cmd, "appops", "set", "--user", "0", pkg, "RUN_ANY_IN_BACKGROUND", "ignore")
            .inOrder()
        assertThat(ActionCommands.argv(PrivilegedAction.SetStandbyBucket(pkg, StandbyBucket.RESTRICTED), 0))
            .containsExactly(cmd, "activity", "set-standby-bucket", "--user", "0", pkg, "restricted")
            .inOrder()
    }

    @Test
    fun theReadsAnActionIsCheckedWithAskForOneAppOnly() {
        assertThat(ActionCommands.readRunInBackground(pkg, 0))
            .containsExactly(cmd, "appops", "get", "--user", "0", pkg, "RUN_ANY_IN_BACKGROUND")
            .inOrder()
        assertThat(ActionCommands.readStandbyBucket(pkg, 10))
            .containsExactly(cmd, "activity", "get-standby-bucket", "--user", "10", pkg)
            .inOrder()
    }

    @Test
    fun restoringBackgroundRunWritesAllowNeverDefault() {
        assertThat(ActionCommands.argv(PrivilegedAction.SetRunInBackground(pkg, allowed = true), 0).last()).isEqualTo("allow")
    }

    @Test
    fun theDozeAllowlistAddsAndRemovesByPrefix() {
        assertThat(ActionCommands.argv(PrivilegedAction.SetDozeAllowlisted(pkg, listed = true), 0))
            .containsExactly(cmd, "deviceidle", "whitelist", "+$pkg")
            .inOrder()
        assertThat(ActionCommands.argv(PrivilegedAction.SetDozeAllowlisted(pkg, listed = false), 0).last()).isEqualTo("-$pkg")
    }

    @Test
    fun dozeConstantsAreOneArgumentAndUnsetIsADelete() {
        val value = "inactive_to=180000,skip_sensing=true"
        assertThat(ActionCommands.argv(PrivilegedAction.PutDozeConstants(value), 0))
            .containsExactly(cmd, "settings", "--user", "0", "put", "global", "device_idle_constants", value)
            .inOrder()
        // An empty value is still a put: it is one empty argument, not a missing one.
        assertThat(
            ActionCommands.argv(PrivilegedAction.PutDozeConstants(""), 0).takeLast(2),
        ).containsExactly("device_idle_constants", "").inOrder()
        assertThat(ActionCommands.argv(PrivilegedAction.PutDozeConstants(null), 0))
            .containsExactly(cmd, "settings", "--user", "0", "delete", "global", "device_idle_constants")
            .inOrder()
    }

    @Test
    fun theDeviceCommandsTakeNoText() {
        assertThat(ActionCommands.argv(PrivilegedAction.SetBatterySaver(true), 0)).containsExactly(cmd, "power", "set-mode", "1").inOrder()
        assertThat(ActionCommands.argv(PrivilegedAction.SetBatterySaver(false), 0).last()).isEqualTo("0")
        assertThat(ActionCommands.argv(PrivilegedAction.ResetBatteryStats, 0))
            .containsExactly("/system/bin/dumpsys", "batterystats", "--reset")
            .inOrder()
    }

    @Test
    fun onlyWellFormedDozeConstantsMayBeWritten() {
        val good =
            listOf(
                "",
                "inactive_to=180000",
                "light_after_inactive_to=30000,inactive_to=180000,skip_sensing=true,min_time_to_alarm=600000",
                "idle_factor=2.0,light_idle_factor=1.5",
            )
        val bad =
            listOf(
                "inactive_to",
                "inactive_to=",
                "=180000",
                "inactive_to=180000,",
                ",inactive_to=180000",
                "inactive_to=180000;rm",
                "inactive_to=18 0000",
                "--user=0",
                "inactive_to=180000\n",
                "a=1," + "k=1,".repeat(600) + "z=1",
            )
        good.forEach { assertThat(ActionCommands.isDozeConstants(it)).isTrue() }
        bad.forEach { assertThat(ActionCommands.isDozeConstants(it)).isFalse() }
    }

    @Test
    fun theCopyableLineIsWhatTheWorkerRuns() {
        assertThat(ActionCommands.adbLine(ActionCommands.argv(PrivilegedAction.ForceStop(pkg), 0)))
            .isEqualTo("adb shell cmd activity force-stop --user 0 com.example.app")
        assertThat(ActionCommands.adbLine(ActionCommands.argv(PrivilegedAction.PutDozeConstants("a=1,b=2"), 0)))
            .isEqualTo("adb shell cmd settings --user 0 put global device_idle_constants a=1,b=2")
        assertThat(ActionCommands.adbLine(ActionCommands.argv(PrivilegedAction.PutDozeConstants(""), 0)))
            .isEqualTo("adb shell cmd settings --user 0 put global device_idle_constants ''")
        assertThat(ActionCommands.adbLine(ActionCommands.argv(PrivilegedAction.ResetBatteryStats, 0)))
            .isEqualTo("adb shell dumpsys batterystats --reset")
    }
}
