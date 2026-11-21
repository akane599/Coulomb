// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.parsing.cmd

import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.model.cmd.AppOpMode
import io.github.akane599.coulomb.core.model.cmd.AppOpReading
import io.github.akane599.coulomb.core.model.cmd.DozeAllowlist
import io.github.akane599.coulomb.core.model.cmd.StandbyBucket
import org.junit.Test

class CmdOutputsTest {
    private val op = "RUN_ANY_IN_BACKGROUND"

    @Test
    fun anOpWithNoRecordHasItsDefault() {
        val reading = CmdOutputs.appOp("No operations.\nDefault mode: allow\n", op)
        assertThat(reading).isEqualTo(AppOpReading(uidMode = null, packageMode = null, defaultMode = AppOpMode.ALLOW))
        assertThat(reading?.effective).isEqualTo(AppOpMode.ALLOW)
    }

    @Test
    fun aRecordIsReadWithWhateverFollowsItsMode() {
        assertThat(CmdOutputs.appOp("$op: ignore\n", op)?.effective).isEqualTo(AppOpMode.IGNORE)
        assertThat(CmdOutputs.appOp("$op: allow; time=+3m12s45ms ago; duration=+1s203ms\n", op)?.packageMode).isEqualTo(AppOpMode.ALLOW)
        assertThat(CmdOutputs.appOp("$op: default; rejectTime=+2h0m0s0ms ago\r\n", op)?.packageMode).isEqualTo(AppOpMode.DEFAULT)
        assertThat(CmdOutputs.appOp("$op: allow (running)\n", op)?.packageMode).isEqualTo(AppOpMode.ALLOW)
    }

    @Test
    fun aUidModeWinsOverThePackages() {
        val reading = CmdOutputs.appOp("Uid mode: $op: ignore\n$op: allow\n", op)
        assertThat(reading).isEqualTo(AppOpReading(AppOpMode.IGNORE, AppOpMode.ALLOW, null))
        assertThat(reading?.effective).isEqualTo(AppOpMode.IGNORE)
        assertThat(CmdOutputs.appOp("Uid mode: $op: deny\n", op)?.effective).isEqualTo(AppOpMode.DENY)
    }

    @Test
    fun outputItDoesNotUnderstandIsNoReading() {
        assertThat(CmdOutputs.appOp("", op)).isNull()
        assertThat(CmdOutputs.appOp("Default mode: allow\n", op)).isNull()
        assertThat(CmdOutputs.appOp("No operations.\n", op)).isNull()
        assertThat(CmdOutputs.appOp("WAKE_LOCK: allow\n", op)).isNull()
        assertThat(CmdOutputs.appOp("$op: mode=7\n", op)).isNull()
        assertThat(CmdOutputs.appOp("$op: allow\n$op: ignore\n", op)).isNull()
        assertThat(CmdOutputs.appOp("No operations.\n$op: allow\n", op)).isNull()
        assertThat(CmdOutputs.appOp("No operations.\nDefault mode: sometimes\n", op)).isNull()
        assertThat(CmdOutputs.appOp("Error: No UID for com.example in user 0\n", op)).isNull()
        // A longer op that starts with this one's name is another op.
        assertThat(CmdOutputs.appOp("${op}_MORE: allow\n", op)).isNull()
    }

    @Test
    fun oneBucketIsItsNumber() {
        assertThat(CmdOutputs.standbyBucket("45\n")).isEqualTo(StandbyBucket.RESTRICTED)
        assertThat(CmdOutputs.standbyBucket("5\n")).isEqualTo(StandbyBucket.EXEMPTED)
        assertThat(CmdOutputs.standbyBucket("46\n")).isNull()
        assertThat(CmdOutputs.standbyBucket("")).isNull()
        assertThat(CmdOutputs.standbyBucket("10\n20\n")).isNull()
        assertThat(CmdOutputs.standbyBucket("com.example.app: 10\n")).isNull()
    }

    @Test
    fun everyAppsBucketIsReadByPackage() {
        assertThat(CmdOutputs.standbyBuckets("com.example.one: 10\ncom.example.two: 40\ncom.example.odd: 46\n"))
            .containsExactly("com.example.one", StandbyBucket.ACTIVE, "com.example.two", StandbyBucket.RARE)
        assertThat(CmdOutputs.standbyBuckets("")).isEmpty()
        assertThat(CmdOutputs.standbyBuckets("com.example.one: 10\nException occurred\n")).isNull()
    }

    @Test
    fun theDozeAllowlistKeepsItsThreeLists() {
        val out =
            "system-excidle,com.example.cal,10050\nsystem,com.example.sys,1001\n" +
                "user,com.example.app,10234\nuser,com.example.two,10235\n"
        assertThat(CmdOutputs.dozeAllowlist(out))
            .isEqualTo(DozeAllowlist(setOf("com.example.cal"), setOf("com.example.sys"), setOf("com.example.app", "com.example.two")))
        assertThat(CmdOutputs.dozeAllowlist("")).isEqualTo(DozeAllowlist(emptySet(), emptySet(), emptySet()))
        assertThat(CmdOutputs.dozeAllowlist("Permission Denial: can't dump DeviceIdleController\n")).isNull()
        assertThat(CmdOutputs.dozeAllowlist("user,com.example.app\n")).isNull()
    }
}
