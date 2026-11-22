// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.datastore.proto.Settings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

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
    fun theAlarmsAndTheDigestKeepTheirDefaultsUntilSet() =
        runTest(UnconfinedTestDispatcher()) {
            val store = store(folder.root.resolve("d.pb"))
            assertThat(store.settings.first().temperatureAlarmDeciC).isEqualTo(420)
            assertThat(store.settings.first().insightsDigestMinute).isEqualTo(9 * 60)

            store.temperatureAlarm(null)
            store.insightsDigest(false)
            store.insightsDigestMinute(0)
            store.chargeAlarm(true)

            val set = store.settings.first()
            assertThat(set.temperatureAlarmDeciC).isNull()
            assertThat(set.insightsDigest).isFalse()
            // Midnight is a time, not "unset".
            assertThat(set.insightsDigestMinute).isEqualTo(0)
            assertThat(set.chargeAlarm).isTrue()

            store.temperatureAlarm(999)
            assertThat(store.settings.first().temperatureAlarmDeciC).isEqualTo(500)
        }

    @Test
    fun anUnreadableFileGivesTheDefaults() =
        runTest(UnconfinedTestDispatcher()) {
            val file = folder.root.resolve("c.pb").apply { writeBytes(byteArrayOf(0x0A, 0x7F)) }
            assertThat(store(file).settings.first()).isEqualTo(UserSettings())
        }

    /** A store on [file] whose first write fails, as on a full disk. */
    private fun TestScope.failingOnce(file: File): SettingsStore {
        val real =
            DataStoreFactory.create(
                serializer = SettingsSerializer,
                corruptionHandler = ReplaceFileCorruptionHandler { Settings() },
                scope = backgroundScope,
                produceFile = { file },
            )
        var writes = 0
        return SettingsStore(
            object : DataStore<Settings> {
                override val data: Flow<Settings> = real.data

                override suspend fun updateData(transform: suspend (t: Settings) -> Settings): Settings {
                    if (writes++ == 0) throw IOException("disk full")
                    return real.updateData(transform)
                }
            },
        )
    }

    private fun onDisk(file: File): Settings = file.inputStream().use { Settings.ADAPTER.decode(it) }

    /** Audit DATA-002: a restored install whose reset could not be stored still reads its consents as off. */
    @Test
    fun aRestoredInstallWhoseResetFailedReadsItsConsentsOff() =
        runTest(UnconfinedTestDispatcher()) {
            val file = folder.root.resolve("r.pb")
            val restored = failingOnce(file)
            // The file as the backup brought it. The first write, the reset, fails.
            file.outputStream().use { Settings(onboarded = true, action_center = true, automation = true, fahrenheit = true).encode(it) }

            assertThat(runCatching { restored.startedFromBackup() }.isFailure).isTrue()

            val read = restored.settings.first()
            assertThat(read.onboarded).isFalse()
            assertThat(read.actionCenter).isFalse()
            assertThat(read.automation).isFalse()
            assertThat(read.fahrenheit).isTrue()
            assertThat(restored.resetStored.first()).isFalse()
            assertThat(onDisk(file).action_center).isTrue()

            // Onboarding's own write stores the reset with it.
            restored.onboarded()
            val after = restored.settings.first()
            assertThat(after.onboarded).isTrue()
            assertThat(after.actionCenter).isFalse()
            assertThat(after.automation).isFalse()
            assertThat(restored.resetStored.first()).isTrue()
            assertThat(onDisk(file).action_center).isFalse()
            assertThat(onDisk(file).automation).isFalse()
            assertThat(onDisk(file).onboarded).isTrue()
        }

    @Test
    fun twoFirstWritesAfterAFailedResetKeepBoth() =
        runTest(UnconfinedTestDispatcher()) {
            val file = folder.root.resolve("t.pb")
            val restored = failingOnce(file)
            file.outputStream().use { Settings(onboarded = true, action_center = true, automation = true).encode(it) }
            runCatching { restored.startedFromBackup() }

            val first = async { restored.onboarded() }
            val second = async { restored.fahrenheit(true) }
            first.await()
            second.await()

            val read = restored.settings.first()
            assertThat(read.onboarded).isTrue()
            assertThat(read.fahrenheit).isTrue()
            assertThat(read.actionCenter).isFalse()
            assertThat(read.automation).isFalse()
        }
}
