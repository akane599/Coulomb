// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.parsing

import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.parsing.aosp.service.BatteryStatsServiceDumpProto
import org.junit.Test
import java.io.File

/** `BatteryStats.CHECKIN_VERSION`, written as `report_version` by both builds. */
private const val CHECKIN_VERSION = 36

/** The vendored AOSP schema decodes both real `--proto` dumps (SPEC 5.1). */
class AospProtoSchemaTest {
    private val fixtures = File(System.getProperty("coulomb.fixtures"))

    @Test
    fun bothFixturesDecodeWithTheVendoredSchema() {
        for (dir in listOf("sm-s938b/S938BXXSDDZJ1", "avd-pixel-api37/16571990")) {
            val bytes = File(fixtures, "$dir/batterystats.proto.bin").readBytes()
            val dump = BatteryStatsServiceDumpProto.ADAPTER.decode(bytes)
            val stats = requireNotNull(dump.batterystats) { dir }
            assertThat(stats.report_version).isEqualTo(CHECKIN_VERSION)
            assertThat(dump.unknownFields.size + stats.unknownFields.size).isEqualTo(0)
            assertThat(stats.uids).isNotEmpty()
            assertThat(stats.system).isNotNull()
        }
    }
}
