// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.parsing.dumpsys

import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.model.dumpsys.OemBattery
import io.github.akane599.coulomb.core.model.dumpsys.OemHealthCodec
import io.github.akane599.coulomb.core.model.dumpsys.OemHealthValue
import io.github.akane599.coulomb.core.parsing.Fixtures
import org.junit.Test
import java.io.ByteArrayInputStream
import java.time.LocalDate

class OemBatteryReaderTest {
    private fun extras(device: String) =
        BatteryServiceParser.parse(ByteArrayInputStream(Fixtures.bytes(device, "battery.txt"))).value.extras

    @Test
    fun thePhoneReportsBothSamsungHealthValuesAndItsFirstUseDate() {
        val oem = OemBatteryReader.read(extras(Fixtures.PHONE))
        assertThat(oem.health)
            .containsExactly(OemHealthValue("mSavedBatteryAsoc", 100), OemHealthValue("mSavedBatteryBsoh", 96))
            .inOrder()
        assertThat(oem.firstUseDate).isEqualTo(LocalDate.of(2025, 10, 30))
    }

    @Test
    fun theEmulatorHasNoOemValues() {
        assertThat(OemBatteryReader.read(extras(Fixtures.AVD))).isEqualTo(OemBattery.NONE)
    }

    @Test
    fun implausibleValuesAreDroppedNotClamped() {
        val oem =
            OemBatteryReader.read(
                mapOf(
                    "mSavedBatteryAsoc" to "[0]",
                    "mSavedBatteryBsoh" to "151",
                    "battery FirstUseDate" to "[20251340]",
                ),
            )
        assertThat(oem).isEqualTo(OemBattery.NONE)
    }

    @Test
    fun anUnsetDateAndOddTextAreIgnored() {
        val oem =
            OemBatteryReader.read(
                mapOf(
                    "mSavedBatteryAsoc" to "[ 97 ]",
                    "mSavedBatteryBsoh" to "96%",
                    "battery FirstUseDate" to "[00000000]",
                ),
            )
        assertThat(oem.health).containsExactly(OemHealthValue("mSavedBatteryAsoc", 97))
        assertThat(oem.firstUseDate).isNull()
    }

    @Test
    fun storedValuesRoundTrip() {
        val values = listOf(OemHealthValue("mSavedBatteryAsoc", 100), OemHealthValue("mSavedBatteryBsoh", 96))
        val text = OemHealthCodec.encode(values)
        assertThat(text).isEqualTo("mSavedBatteryAsoc=100;mSavedBatteryBsoh=96")
        assertThat(OemHealthCodec.decode(text)).isEqualTo(values)
        assertThat(OemHealthCodec.encode(emptyList())).isNull()
        assertThat(OemHealthCodec.decode(null)).isEmpty()
        assertThat(OemHealthCodec.decode("broken;mSavedBatteryAsoc=x;k=90")).containsExactly(OemHealthValue("k", 90))
    }
}
