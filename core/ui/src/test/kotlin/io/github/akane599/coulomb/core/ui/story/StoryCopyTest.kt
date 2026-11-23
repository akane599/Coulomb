// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.ui.story

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.model.InsightState
import io.github.akane599.coulomb.core.model.insight.Evidence
import io.github.akane599.coulomb.core.model.insight.EvidenceUnit
import io.github.akane599.coulomb.core.model.insight.Insight
import io.github.akane599.coulomb.core.model.insight.RuleId
import io.github.akane599.coulomb.core.model.story.Story
import io.github.akane599.coulomb.core.model.story.StoryCause
import io.github.akane599.coulomb.core.model.story.StorySpan
import io.github.akane599.coulomb.core.model.story.StorySubject
import io.github.akane599.coulomb.core.ui.format.Formats
import io.github.akane599.coulomb.core.ui.insight.InsightCopy
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Locale

/** SPEC 9.3: the story's sentences, with the two examples the copy must be able to produce. */
@RunWith(AndroidJUnit4::class)
class StoryCopyTest {
    private val minute = 60_000L
    private val now = 1_800_000_000_000L

    private fun copy(language: String): StoryCopy {
        val resources = ApplicationProvider.getApplicationContext<Context>().resources
        val formats = Formats(resources, Locale.forLanguageTag(language), "HH:mm", "HH:mm:ss")
        return StoryCopy(resources, formats, InsightCopy(resources, formats))
    }

    /** The sentences as plain text: no-break spaces as spaces, word joiners removed. */
    private fun StoryCopy.plain(story: Story): List<String> = sentences(story).map { it.text.replace('\u00A0', ' ').replace("\u2060", "") }

    private fun StoryCopy.plainLine(story: Story): String = line(story).text.replace('\u00A0', ' ').replace("\u2060", "")

    private val night = StorySpan(now - 432 * minute, now, 432 * minute, levelDropPct = 6, ratePctPerHour = 0.8)

    /** SPEC 9.3's example: a night, half of whose drain came from one app's 41 wakeups. */
    private val example =
        Story(
            subject = StorySubject.ScreenOff(overnight = true),
            span = night,
            appMah = null,
            appShare = null,
            causes = listOf(StoryCause.App(10_123, "Instagram", 0.5, wakeups = 41, heldAwakeMs = null)),
            deepSleepShare = 0.91,
            anomaly = null,
        )

    private fun insight(
        rule: RuleId,
        uid: Int?,
    ) = Insight(
        7,
        rule,
        rule.severity,
        InsightState.NEW,
        null,
        uid,
        null,
        Evidence(2.57, 2.0, EvidenceUnit.HOURS, now - 60 * minute, now),
        now,
    )

    /** Every cause kind, an anomaly, and shares that are not a half. */
    private val full =
        example.copy(
            subject = StorySubject.ScreenOff(overnight = false),
            causes =
                listOf(
                    StoryCause.App(10_200, "Halcyon", 0.33, wakeups = null, heldAwakeMs = 34 * minute),
                    StoryCause.Unattributed(0.2),
                    StoryCause.Device(0.12),
                ),
            deepSleepShare = null,
            anomaly = insight(RuleId.EXCESSIVE_WAKELOCK, 10_200),
        )

    @Test
    @Config(qualifiers = "en")
    fun englishProducesTheSpecsExample() {
        assertThat(copy("en").plain(example))
            .containsExactly(
                "Your screen was off for 7 h 12 min overnight and the battery fell 6% (0.8 %/h).",
                "Half of that came from Instagram waking the phone 41 times.",
                "The phone was in deep sleep 91% of the time.",
            ).inOrder()
    }

    @Test
    @Config(qualifiers = "tr")
    fun turkishProducesTheSpecsExample() {
        assertThat(copy("tr").plain(example))
            .containsExactly(
                "Gece ekran 7 sa 12 dk kapalıydı ve pil %6 azaldı (0,8 %/sa).",
                "Bunun yarısı Instagram’ın telefonu 41 kez uyandırmasından geldi.",
                "Cihaz zamanın %91’inde derin uykudaydı.",
            ).inOrder()
    }

    @Test
    @Config(qualifiers = "en")
    fun everyKindOfCauseAndTheAnomalyHaveTheirSentence() {
        val copy = copy("en")
        assertThat(copy.plain(full))
            .containsExactly(
                "Your screen was off for 7 h 12 min and the battery fell 6% (0.8 %/h).",
                "33% of that came from Halcyon keeping the phone awake for 34 min.",
                "20% of it could not be assigned to any app or part.",
                "The phone itself, idle and on standby, took 12% of it.",
                "Halcyon kept the phone awake for 2 h 34 min in a day.",
            ).inOrder()
        // Each sentence says what it is about, so a screen can link it.
        assertThat(copy.sentences(full).map { it.about })
            .containsExactly(null, StoryLink.App(10_200), null, null, StoryLink.Insight(7))
            .inOrder()
        val plainApp = full.copy(causes = listOf(StoryCause.App(10_200, "Halcyon", 0.52, null, null)), anomaly = null)
        assertThat(copy.plain(plainApp).last()).isEqualTo("Halcyon used half of it.")
    }

    @Test
    @Config(qualifiers = "tr")
    fun turkishCausesTakeTheirSuffixes() {
        val copy = copy("tr")
        assertThat(copy.plain(full))
            .containsExactly(
                "Ekran 7 sa 12 dk kapalıydı ve pil %6 azaldı (0,8 %/sa).",
                "Bunun %33 kadarı Halcyon’un telefonu 34 dk uyanık tutmasından geldi.",
                "Bunun %20 kadarı hiçbir uygulamaya ya da parçaya atanamadı.",
                "Telefonun kendisi, boşta ve beklemede, bunun %12 kadarını aldı.",
                "Halcyon telefonu bir günde 2 sa 34 dk uyanık tuttu.",
            ).inOrder()
        // A name that cannot take the suffix by rule gets a wording that needs none.
        val odd = full.copy(causes = listOf(StoryCause.App(10_300, "Maps 2", 0.5, wakeups = 12, heldAwakeMs = null)), anomaly = null)
        assertThat(copy.plain(odd).last()).isEqualTo("Bunun yarısı Maps 2 uygulamasının telefonu 12 kez uyandırmasından geldi.")
    }

    @Test
    @Config(qualifiers = "en")
    fun theOtherVerdictsAndAnAppsOwnStory() {
        val copy = copy("en")
        val since =
            Story(
                StorySubject.SinceUnplugged,
                StorySpan(now - 312 * minute, now, 312 * minute, 26, null),
                null,
                null,
                emptyList(),
                null,
                null,
            )
        assertThat(copy.plain(since)).containsExactly("Since unplugged 5 h 12 min ago, the battery fell 26%.")
        assertThat(
            copy.plain(since.copy(span = since.span.copy(levelDropPct = 0))),
        ).containsExactly("Unplugged 5 h 12 min ago; the level hasn’t changed yet.")
        assertThat(copy.plain(example.copy(span = night.copy(ratePctPerHour = null), causes = emptyList(), deepSleepShare = null)))
            .containsExactly("Your screen was off for 7 h 12 min overnight and the battery fell 6%.")
        // One line for Now and the widgets: the verdict, with the largest app when one is a cause.
        val causes = listOf(StoryCause.Device(0.4), StoryCause.App(10_200, "Halcyon", 0.38, wakeups = null, heldAwakeMs = null))
        assertThat(copy.plainLine(since.copy(causes = causes)))
            .isEqualTo("Since unplugged 5 h 12 min ago, the battery fell 26%; Halcyon used 38% of it.")
        assertThat(copy.plainLine(since.copy(causes = causes.take(1)))).isEqualTo("Since unplugged 5 h 12 min ago, the battery fell 26%.")
        assertThat(copy.plainLine(since.copy(span = since.span.copy(levelDropPct = 0), causes = causes)))
            .isEqualTo("Unplugged 5 h 12 min ago; the level hasn’t changed yet.")
        val app =
            Story(
                subject = StorySubject.App(10_200, "Halcyon"),
                span = night,
                appMah = 402.0,
                appShare = 0.33,
                causes = listOf(StoryCause.App(10_200, "Halcyon", 0.33, wakeups = 41, heldAwakeMs = 34 * minute)),
                deepSleepShare = 0.91,
                anomaly = insight(RuleId.EXCESSIVE_WAKELOCK, 10_200),
            )
        assertThat(copy.plain(app))
            .containsExactly(
                "Halcyon used 402 mAh in this stretch, 33% of the drain.",
                "It woke the phone 41 times.",
                "It kept the phone awake for 34 min.",
                "Halcyon kept the phone awake for 2 h 34 min in a day.",
            ).inOrder()
        assertThat(copy.plain(app.copy(appMah = null, appShare = null, causes = emptyList(), anomaly = null)))
            .containsExactly("Halcyon has no recorded use in this stretch.")
    }

    @Test
    @Config(qualifiers = "tr")
    fun noSentencePassesTwentyTwoWords() {
        for (language in listOf("tr")) {
            val copy = copy(language)
            for (story in listOf(example, full)) {
                for (sentence in copy.plain(story)) assertThat(sentence.split(' ').size).isAtMost(22)
            }
        }
    }

    @Test
    fun turkishSuffixesFollowTheWordBeforeThem() {
        assertThat(TurkishSuffix.genitive("Instagram")).isEqualTo("Instagram’ın")
        assertThat(TurkishSuffix.genitive("Halcyon")).isEqualTo("Halcyon’un")
        assertThat(TurkishSuffix.genitive("Kamera")).isEqualTo("Kamera’nın")
        assertThat(TurkishSuffix.genitive("Telefon Yöneticisi")).isEqualTo("Telefon Yöneticisi’nin")
        assertThat(TurkishSuffix.genitive("Müzik")).isEqualTo("Müzik’in")
        assertThat(TurkishSuffix.genitive("Görüntü")).isEqualTo("Görüntü’nün")
        assertThat(TurkishSuffix.genitive("Maps 2")).isNull()
        assertThat(TurkishSuffix.genitive("X")).isNull()
        assertThat(listOf(0, 3, 6, 10, 40, 60, 88, 91, 100).map(TurkishSuffix::percentIn))
            .containsExactly("ında", "ünde", "sında", "unda", "ında", "ında", "inde", "inde", "ünde")
            .inOrder()
        assertThat(TurkishSuffix.percentIn(101)).isNull()
    }

    private val sinceUnplugged =
        Story(StorySubject.SinceUnplugged, StorySpan(now - 312 * minute, now, 312 * minute, 0, null), null, null, emptyList(), null, null)

    private fun Story.dropping(points: Int?) = copy(span = span.copy(levelDropPct = points, ratePctPerHour = null))

    private fun lastReading(
        language: String,
        points: Int?,
    ): String {
        val resources = ApplicationProvider.getApplicationContext<Context>().resources
        val formats = Formats(resources, Locale.forLanguageTag(language), "HH:mm", "HH:mm:ss")
        return lastReadingLine(resources, formats, sinceUnplugged.dropping(points).span).replace('\u00A0', ' ').replace("\u2060", "")
    }

    /**
     * Audit BUG-018: the gauge can correct upwards on battery. A rise is what was measured, and it
     * matches the figure beside the sentence; "did not change" is for no change only, and a level
     * not known at both ends says nothing of the level.
     */
    @Test
    @Config(qualifiers = "en")
    fun aRiseOrAnUnknownLevelIsNeverNoChange() {
        val copy = copy("en")
        val quiet = example.copy(causes = emptyList(), deepSleepShare = null)

        assertThat(copy.plain(sinceUnplugged.dropping(-1))).containsExactly("Since unplugged 5 h 12 min ago, the level rose 1%.")
        assertThat(copy.plain(sinceUnplugged.dropping(null))).containsExactly("Unplugged 5 h 12 min ago.")
        assertThat(copy.plain(sinceUnplugged.dropping(0))).containsExactly("Unplugged 5 h 12 min ago; the level hasn’t changed yet.")
        assertThat(copy.plainLine(sinceUnplugged.dropping(-1))).isEqualTo("Since unplugged 5 h 12 min ago, the level rose 1%.")

        assertThat(copy.plain(quiet.dropping(-2))).containsExactly("Your screen was off for 7 h 12 min overnight; the level rose 2%.")
        assertThat(copy.plain(quiet.dropping(null))).containsExactly("Your screen was off for 7 h 12 min overnight.")
        assertThat(copy.plain(quiet.dropping(0))).containsExactly("Your screen was off for 7 h 12 min overnight; the level did not change.")
        val nap = quiet.copy(subject = StorySubject.ScreenOff(overnight = false))
        assertThat(copy.plain(nap.dropping(-2))).containsExactly("Your screen was off for 7 h 12 min; the level rose 2%.")
        assertThat(copy.plain(nap.dropping(null))).containsExactly("Your screen was off for 7 h 12 min.")

        assertThat(lastReading("en", -1)).isEqualTo("From the unplug to the last reading, 5 h 12 min, the level rose 1%.")
        assertThat(lastReading("en", null)).isEqualTo("From the unplug to the last reading, 5 h 12 min.")
        assertThat(lastReading("en", 0)).isEqualTo("From the unplug to the last reading, 5 h 12 min, the level did not change.")
        assertThat(lastReading("en", 3)).isEqualTo("From the unplug to the last reading, 5 h 12 min, the battery fell 3%.")
    }

    @Test
    @Config(qualifiers = "tr")
    fun turkishARiseIsARise() {
        val copy = copy("tr")

        assertThat(copy.plain(sinceUnplugged.dropping(-1))).containsExactly("Şarjdan çıkalı 5 sa 12 dk oldu ve seviye %1 arttı.")
        assertThat(copy.plain(sinceUnplugged.dropping(null))).containsExactly("Şarjdan çıkalı 5 sa 12 dk oldu.")
        assertThat(copy.plain(example.copy(causes = emptyList(), deepSleepShare = null).dropping(-2)))
            .containsExactly("Gece ekran 7 sa 12 dk kapalıydı; seviye %2 arttı.")
        assertThat(lastReading("tr", -1)).isEqualTo("Şarjdan çıkıştan son okumaya kadar 5 sa 12 dk geçti ve seviye %1 arttı.")
        assertThat(lastReading("tr", null)).isEqualTo("Şarjdan çıkıştan son okumaya kadar 5 sa 12 dk geçti.")
    }

    /**
     * Audit BUG-021: a share within half a point of all or nothing is shown as ">%99" or "<%1". The
     * suffix belongs to the number on screen, so those take the wording without one, not 100's or 0's.
     */
    @Test
    @Config(qualifiers = "tr")
    fun turkishDeepSleepAtAShownBoundTakesNoSuffix() {
        val copy = copy("tr")
        for (share in listOf(0.996, 0.004)) {
            val sentence = copy.plain(example.copy(deepSleepShare = share)).last()
            assertThat(sentence).contains("kadarında")
            assertThat(sentence).doesNotContain("’")
        }
        // A whole percentage keeps its suffix.
        assertThat(copy.plain(example.copy(deepSleepShare = 0.99)).last()).isEqualTo("Cihaz zamanın %99’unda derin uykudaydı.")
    }
}
