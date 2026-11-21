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
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** The settings a bundle carries, and the few of them a file may set (ADR-0062 §12). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsTransferTest {
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
    fun theNeutralSettingsMoveAndTheConsentsStayBehind() =
        runTest(UnconfinedTestDispatcher()) {
            val old = store(folder.root.resolve("old.pb"))
            old.showSystemApps(true)
            old.scaleToMeasured(true)
            old.chargeTarget(90)
            old.temperatureAlarm(null)
            old.insightsDigestMinute(7 * 60 + 30)
            old.theme(ThemeChoice.BLACK)
            old.fahrenheit(true)
            old.screenOnSampling(10)
            // What stands for a grant, a permission or a consent on the old phone.
            old.onboarded()
            old.actionCenter(true)
            old.automation(true)
            old.chargeAlarm(true)
            old.componentBreakdown(true)
            old.liteMode(true)
            old.longRawRetention(true)
            old.developerConsole(true)
            old.insightsDigest(false)
            val json = SettingsTransfer(old).toJson()

            val new = store(folder.root.resolve("new.pb"))
            assertThat(SettingsTransfer(new).apply(json)).isTrue()

            assertThat(new.settings.first()).isEqualTo(
                UserSettings(
                    showSystemApps = true,
                    scaleToMeasured = true,
                    chargeTargetPct = 90,
                    temperatureAlarmDeciC = null,
                    insightsDigestMinute = 7 * 60 + 30,
                    theme = ThemeChoice.BLACK,
                    fahrenheit = true,
                    screenOnSamplingS = 10,
                ),
            )
            // The file still says what the old phone was set to, except what is never written.
            val file = JSONObject(json)
            SettingsTransfer.NEVER_APPLIED.forEach { assertThat(file.has(it)).isTrue() }
            assertThat(file.has("onboarded")).isFalse()
            assertThat(file.has("developer_console")).isFalse()
        }

    @Test
    fun aValueOutsideItsRangeOrOfAnotherTypeIsLeftAsItIsHere() =
        runTest(UnconfinedTestDispatcher()) {
            val store = store(folder.root.resolve("a.pb"))
            val edited =
                """{"charge_target_pct": 5, "temperature_alarm_deci_c": 9000, "theme": "PINK", "fahrenheit": "yes",
                    "screen_on_sampling_s": 1, "insights_digest_minute": 5000, "action_center": true, "onboarded": true}"""
            assertThat(SettingsTransfer(store).apply(edited)).isTrue()
            assertThat(store.settings.first()).isEqualTo(UserSettings())
            assertThat(SettingsTransfer(store).apply("not json")).isFalse()
        }
}
