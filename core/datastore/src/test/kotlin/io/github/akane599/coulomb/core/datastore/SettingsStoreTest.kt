// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.datastore

import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.datastore.proto.Settings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun TestScope.store(file: File) =
        SettingsStore(
            DataStoreFactory.create(
                serializer = SettingsSerializer,
                corruptionHandler = ReplaceFileCorruptionHandler { Settings() },
                scope = backgroundScope,
                produceFile = { file },
            ),
        )

    @Test
    fun everySettingStartsAtSpec144sDefault() =
        runTest(UnconfinedTestDispatcher()) {
            assertThat(store(folder.root.resolve("a.pb")).settings.first()).isEqualTo(UserSettings())
            assertThat(UserSettings().showSystemApps).isFalse()
            assertThat(UserSettings().scaleToMeasured).isFalse()
            assertThat(UserSettings().componentBreakdown).isFalse()
            assertThat(UserSettings().chargeTargetPct).isEqualTo(80)
        }

    @Test
    fun theChargeTargetStaysInSpec144sRange() =
        runTest(UnconfinedTestDispatcher()) {
            val store = store(folder.root.resolve("d.pb"))
            store.chargeTarget(85)
            assertThat(store.settings.first().chargeTargetPct).isEqualTo(85)
            store.chargeTarget(120)
            assertThat(store.settings.first().chargeTargetPct).isEqualTo(100)
            store.chargeTarget(10)
            assertThat(store.settings.first().chargeTargetPct).isEqualTo(50)
        }

    @Test
    fun aChoiceIsKeptOnDisk() =
        runTest(UnconfinedTestDispatcher()) {
            val file = folder.root.resolve("b.pb")
            store(file).showSystemApps(true)
            assertThat(SettingsSerializer.readFrom(file.inputStream()).show_system_apps).isTrue()
        }

    @Test
    fun anUnreadableFileGivesTheDefaults() =
        runTest(UnconfinedTestDispatcher()) {
            val file = folder.root.resolve("c.pb").apply { writeBytes(byteArrayOf(0x0A, 0x7F)) }
            assertThat(store(file).settings.first()).isEqualTo(UserSettings())
        }
}
