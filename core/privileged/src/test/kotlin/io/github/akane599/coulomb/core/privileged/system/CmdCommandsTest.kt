// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.privileged.system

import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.privileged.DurablePermission
import io.github.akane599.coulomb.core.privileged.SettingsNamespace
import org.junit.Test

class CmdCommandsTest {
    private val pkg = "io.github.akane599.coulomb"

    @Test
    fun bootstrapCommandsMatchSpec44() {
        assertThat(CmdCommands.grant(pkg, DurablePermission.BATTERY_STATS, 0))
            .containsExactly("/system/bin/cmd", "package", "grant", "--user", "0", pkg, "android.permission.BATTERY_STATS")
            .inOrder()
        assertThat(CmdCommands.allowUsageStats(pkg, 10))
            .containsExactly("/system/bin/cmd", "appops", "set", "--user", "10", pkg, "GET_USAGE_STATS", "allow")
            .inOrder()
    }

    @Test
    fun readCommandsTakeNoFreeText() {
        assertThat(
            CmdCommands.standbyBuckets(0),
        ).containsExactly("/system/bin/cmd", "activity", "get-standby-bucket", "--user", "0").inOrder()
        assertThat(CmdCommands.settingsGet(SettingsNamespace.GLOBAL, "protect_battery", 0))
            .containsExactly("/system/bin/cmd", "settings", "--user", "0", "get", "global", "protect_battery")
            .inOrder()
    }

    @Test
    fun settingsOutputParsing() {
        assertThat(CmdCommands.parseSettingValue("null\n")).isNull()
        assertThat(CmdCommands.parseSettingValue("1\n")).isEqualTo("1")
        assertThat(CmdCommands.parseSettingKeys("a=1\nb=x=y\n\nnovalue\nc=\n")).containsExactly("a", "b", "c").inOrder()
    }
}
