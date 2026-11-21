// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.ui.insight

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.model.InsightState
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.model.insight.Evidence
import io.github.akane599.coulomb.core.model.insight.EvidenceUnit
import io.github.akane599.coulomb.core.model.insight.Insight
import io.github.akane599.coulomb.core.model.insight.RuleId
import io.github.akane599.coulomb.core.ui.format.Formats
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.util.Locale

/** SPEC 9.3 and 11.10: the words are specific, carry their numbers in the locale's form, and exist in both languages. */
@RunWith(AndroidJUnit4::class)
class InsightCopyTest {
    private val day = 86_400_000L
    private val now = 1_800_000_000_000L

    private fun copy(language: String): InsightCopy {
        val resources = ApplicationProvider.getApplicationContext<Context>().resources
        return InsightCopy(resources, Formats(resources, Locale.forLanguageTag(language), "HH:mm", "HH:mm:ss"))
    }

    /** The text with its no-break spaces as plain ones, so expectations stay readable. */
    private fun InsightCopy.plain(
        insight: Insight,
        appName: String?,
    ): InsightText = text(insight, appName).let { InsightText(it.title.replace('\u00A0', ' '), it.body.replace('\u00A0', ' ')) }

    private fun insight(
        rule: RuleId,
        measured: Double,
        threshold: Double,
        values: Map<String, Double> = emptyMap(),
        labels: Map<String, String> = emptyMap(),
        fromWallMs: Long = now - day,
    ) = Insight(
        1,
        rule,
        rule.severity,
        InsightState.NEW,
        null,
        null,
        null,
        Evidence(measured, threshold, EvidenceUnit.COUNT, fromWallMs, now, values, labels),
        now,
    )

    /** One insight per rule, with every value its copy reads. */
    private val all =
        listOf(
            insight(RuleId.EXCESSIVE_WAKELOCK, 2.57, 2.0),
            insight(RuleId.LONG_WAKELOCK_TAG, 113.0, 60.0, labels = mapOf("tag" to "*job*/sync")),
            insight(RuleId.POOR_DEEP_SLEEP, 20.4, 15.0, mapOf("hours" to 3.2)),
            insight(RuleId.HIGH_SCREEN_OFF_DRAIN, 1.24, 1.0, mapOf("median" to 0.5)),
            insight(RuleId.ALARM_STORM, 12.5, 12.0, mapOf("alarms" to 25.0, "screenOffHours" to 2.0)),
            insight(RuleId.BACKGROUND_LOCATION, 11.0, 10.0),
            insight(RuleId.WEAK_SIGNAL_TAX, 40.0, 30.0, mapOf("modemShare" to 20.0)),
            insight(RuleId.SCAN_HEAVY, 30.0, 20.0, labels = mapOf("radio" to "bluetooth")),
            insight(RuleId.SENSOR_HOLDER, 45.0, 30.0),
            insight(RuleId.JOB_CHURN, 1240.0, 50.0),
            insight(RuleId.HOT_CHARGING, 11.0, 10.0, mapOf("tempC" to 40.0, "peakC" to 42.5)),
            insight(RuleId.HOT_BATTERY, 45.5, 45.0),
            insight(RuleId.SLOW_CHARGING, 9.0, 17.5, mapOf("baselineW" to 25.0)),
            insight(RuleId.CAPACITY_DROP, 3.5, 3.0, mapOf("fromPct" to 95.0, "toPct" to 91.5), fromWallMs = now - 30 * day),
            insight(RuleId.LONG_AT_FULL, 2.5, 2.0),
            insight(RuleId.UNUSED_ACTIVE_APP, 10.0, 7.0),
            insight(RuleId.DOZE_NEVER_DEEP, 3.0, 3.0),
            insight(RuleId.UPDATE_REGRESSION, 55.0, 40.0, mapOf("median" to 20.0)),
            insight(RuleId.UNATTRIBUTED_GAP, 35.0, 20.0, mapOf("measuredMah" to 1240.0, "unattributedMah" to 434.0)),
            insight(RuleId.SELF_COST, 0.5, 0.3),
        )

    private fun everyRuleSpeaks(language: String) {
        assertThat(all.map { it.rule }).isEqualTo(RuleId.entries)
        for (insight in all) {
            for (appName in listOf("Halcyon", null)) {
                val text = copy(language).text(insight, appName)
                for (part in listOf(text.title, text.body)) {
                    assertThat(part).isNotEmpty()
                    // No unfilled placeholder, no exclamation mark, no doubled space (SPEC 9.3).
                    assertThat(part).doesNotContainMatch("%\\d\\$|!|  ")
                    assertThat(part.lowercase()).doesNotContainMatch("optimi[sz]e|boost|optimize et|hızlandır")
                }
            }
        }
    }

    @Test
    @Config(qualifiers = "en")
    fun everyRuleSpeaksEnglish() = everyRuleSpeaks("en")

    @Test
    @Config(qualifiers = "tr")
    fun everyRuleSpeaksTurkish() = everyRuleSpeaks("tr")

    @Test
    @Config(qualifiers = "en")
    fun englishCarriesItsNumbers() {
        val copy = copy("en")
        assertThat(copy.plain(all[0], "Halcyon").title).isEqualTo("Halcyon kept the phone awake for 2 h 34 min")
        assertThat(copy.plain(all[0], "Halcyon").body).startsWith("Android counts more than 2 h of wakelocks")
        assertThat(copy.plain(all[1], null).body).startsWith("An app held “*job*/sync” through one session.")
        assertThat(copy.plain(all[3], null).title).isEqualTo("Screen-off drain was 1.2 %/h")
        assertThat(copy.plain(all[7], null).title).isEqualTo("Bluetooth scanning ran for 30 min")
        assertThat(copy.plain(all[9], "Halcyon").title).isEqualTo("Halcyon ran 1,240 background jobs in a day")
        assertThat(copy.plain(all[11], "Halcyon").body).startsWith("Halcyon was using the most power then.")
        assertThat(copy.plain(all[11], null).body).startsWith("Above 45.0 °C a battery ages quickly.")
        assertThat(copy.plain(all[13], null).body).startsWith("From 95% to 92% within 30 days.")
        assertThat(copy.plain(all[18], null).title).isEqualTo("35% of the drain is unattributed")
        assertThat(copy.plain(all[18], null).body).startsWith("Android's power model assigned 806 mAh of the measured 1,240 mAh")
        assertThat(copy.plain(all[19], null).title).isEqualTo("Coulomb used 0.5% of the battery in a day")
    }

    @Test
    @Config(qualifiers = "tr")
    fun turkishCarriesItsNumbersInItsOwnForm() {
        val copy = copy("tr")
        assertThat(copy.plain(all[0], "Halcyon").title).isEqualTo("Halcyon telefonu 2 sa 34 dk uyanık tuttu")
        // SPEC 9.3's own examples of the form: "0,8 %/sa", "1.240 mAh".
        assertThat(copy.plain(all[3], null).title).isEqualTo("Ekran kapalıyken tüketim 1,2 %/sa oldu")
        assertThat(copy.plain(all[9], "Halcyon").title).isEqualTo("Halcyon bir günde 1.240 arka plan işi çalıştırdı")
        assertThat(copy.plain(all[18], null).title).isEqualTo("Tüketimin %35 kadarı atanamadı")
        assertThat(copy.plain(all[18], null).body).contains("ölçülen 1.240 mAh tüketimin 806 mAh kadarını")
        assertThat(copy.plain(all[19], null).title).isEqualTo("Coulomb bir günde pilin %0,5 kadarını kullandı")
        assertThat(copy.plain(all[14], null).title).isEqualTo("Pil 2 sa 30 dk boyunca %100’de kaldı")
    }

    @Test
    @Config(qualifiers = "en")
    fun theEvidenceTableNamesTheRuleItsThresholdAndWhatWasMeasured() {
        val copy = copy("en")
        val zone = ZoneId.of("UTC")
        val wakelock = all[0].copy(evidence = all[0].evidence.copy(unit = EvidenceUnit.HOURS))
        val table = copy.evidence(wakelock, zone, now)
        assertThat(table.title).isEqualTo("Why you are seeing this")
        assertThat(table.rows.map { row -> row.map { it.replace('\u00A0', ' ') } })
            .containsExactly(
                listOf("Rule", "Excessive wakelock"),
                listOf("Measured", "2 h 34 min"),
                listOf("Threshold", "2 h"),
                // From the day before to now: only the earlier end names its day.
                listOf("Period", "Thu 08:00 – 08:00"),
            ).inOrder()
        assertThat(table.provenance).isEqualTo(Provenance.MEASURED)
        // A rule that compares with the user's own past shows that too.
        val night = all[3].copy(evidence = all[3].evidence.copy(unit = EvidenceUnit.PERCENT_PER_HOUR))
        assertThat(copy.evidence(night, zone, now).rows.map { it.first() })
            .containsExactly("Rule", "Measured", "Threshold", "Your usual", "Period")
            .inOrder()
        assertThat(copy.evidence(night, zone, now).provenance).isEqualTo(Provenance.DERIVED)
    }

    @Test
    @Config(qualifiers = "tr")
    fun everyRuleAndUnitHasItsWordsInTheTable() {
        val copy = copy("tr")
        for (insight in all) {
            val table = copy.evidence(insight, ZoneId.of("UTC"), now)
            assertThat(table.rows.flatten().none { it.isBlank() || it.contains(Regex("%\\d\\$")) }).isTrue()
        }
        for (unit in EvidenceUnit.entries) assertThat(copy.value(1.5, unit)).isNotEmpty()
        assertThat(copy.value(0.3, EvidenceUnit.PERCENT)).isEqualTo("%0,3")
        assertThat(copy.value(12.5, EvidenceUnit.PER_HOUR)).isEqualTo("Saatte 12,5")
    }
}
