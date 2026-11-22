// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.datastore.proto.Settings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
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
import java.io.IOException

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
            old.temperatureAlarm(450)
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
                    temperatureAlarmDeciC = 450,
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

    /** A threshold means on: a file switched off here must not switch the alarm on (alarms stay as they are). */
    @Test
    fun aFileNeverTurnsTheTemperatureAlarmOn() =
        runTest(UnconfinedTestDispatcher()) {
            val store = store(folder.root.resolve("c.pb"))
            store.temperatureAlarm(null)
            assertThat(SettingsTransfer(store).apply("""{"temperature_alarm_deci_c": 450}""")).isTrue()
            assertThat(store.settings.first().temperatureAlarmDeciC).isNull()
        }

    @Test
    fun aFileNeverTurnsTheTemperatureAlarmOffAndNestingIsNotASettingsFile() =
        runTest(UnconfinedTestDispatcher()) {
            val store = store(folder.root.resolve("b.pb"))
            assertThat(SettingsTransfer(store).apply("""{"temperature_alarm_deci_c": null, "fahrenheit": true}""")).isTrue()
            assertThat(store.settings.first()).isEqualTo(UserSettings(fahrenheit = true))
            // Brackets enough to overflow a recursive parser, a nested value, and text after the object.
            assertThat(SettingsTransfer(store).apply("{\"theme\":" + "[".repeat(20_000))).isFalse()
            assertThat(SettingsTransfer(store).apply("""{"theme": {"name": "DARK"}}""")).isFalse()
            assertThat(SettingsTransfer(store).apply("""{"theme": "DARK"} x""")).isFalse()
            assertThat(store.settings.first()).isEqualTo(UserSettings(fahrenheit = true))
        }

    /** A store on [file] that counts its writes and fails the first while [fails] is set. */
    private class Counting(
        private val real: DataStore<Settings>,
        var fails: Boolean,
    ) : DataStore<Settings> {
        var writes = 0
        override val data: Flow<Settings> = real.data

        override suspend fun updateData(transform: suspend (t: Settings) -> Settings): Settings {
            if (writes++ == 0 && fails) throw IOException("disk full")
            return real.updateData(transform)
        }
    }

    private fun TestScope.counting(
        file: File,
        fails: Boolean,
    ) = Counting(
        DataStoreFactory.create(
            serializer = SettingsSerializer,
            corruptionHandler = ReplaceFileCorruptionHandler { Settings() },
            scope = backgroundScope,
            produceFile = { file },
        ),
        fails,
    )

    private val wholeFile =
        JSONObject()
            .put("show_system_apps", true)
            .put("scale_to_measured", true)
            .put("charge_target_pct", 90)
            .put("temperature_alarm_deci_c", 450)
            .put("insights_digest_minute", 450)
            .put("theme", "BLACK")
            .put("fahrenheit", true)
            .put("screen_on_sampling_s", 10)
            .toString()

    /** Audit CONC-006: a file's settings are stored together or not at all. */
    @Test
    fun theWholeFileIsOneWrite() =
        runTest(UnconfinedTestDispatcher()) {
            val counting = counting(folder.root.resolve("one.pb"), fails = false)
            val store = SettingsStore(counting)

            assertThat(SettingsTransfer(store).apply(wholeFile)).isTrue()

            assertThat(counting.writes).isEqualTo(1)
            assertThat(store.settings.first()).isEqualTo(
                UserSettings(
                    showSystemApps = true,
                    scaleToMeasured = true,
                    chargeTargetPct = 90,
                    temperatureAlarmDeciC = 450,
                    insightsDigestMinute = 450,
                    theme = ThemeChoice.BLACK,
                    fahrenheit = true,
                    screenOnSamplingS = 10,
                ),
            )
        }

    @Test
    fun aFailedWriteAppliesNothingFromTheFile() =
        runTest(UnconfinedTestDispatcher()) {
            val store = SettingsStore(counting(folder.root.resolve("none.pb"), fails = true))

            val applied = runCatching { SettingsTransfer(store).apply(wholeFile) }

            assertThat(applied.exceptionOrNull()).isInstanceOf(IOException::class.java)
            assertThat(store.settings.first()).isEqualTo(UserSettings())
        }

    /** The alarm's threshold is taken only while the alarm is on here, as before. */
    @Test
    fun anAlarmThatIsOffHereStaysOff() =
        runTest(UnconfinedTestDispatcher()) {
            val store = store(folder.root.resolve("off.pb"))
            store.temperatureAlarm(null)

            assertThat(SettingsTransfer(store).apply(wholeFile)).isTrue()

            assertThat(store.settings.first().temperatureAlarmDeciC).isNull()
        }
}
