// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.surface

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.analytics.ChargeTime
import io.github.akane599.coulomb.core.analytics.ChargeTimeBasis
import io.github.akane599.coulomb.core.data.ChargingNow
import io.github.akane599.coulomb.core.data.LiveCharger
import io.github.akane599.coulomb.core.model.PrivilegeIssue
import io.github.akane599.coulomb.core.ui.insight.InsightText
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** What the notifications of SPEC 12 say, as the platform receives them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class SurfaceNotifierTest {
    private val context = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val notifier = SurfaceNotifier(context)

    @Before
    fun channels() = notifier.ensureChannels()

    private fun posted(): List<Notification> = shadowOf(manager).allNotifications

    private fun Notification.title() = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.plain()

    private fun Notification.text() = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.plain()

    /** Figures join their unit with a no-break space, and a signed one is a left-to-right isolate. */
    private fun String.plain() =
        map {
            if (it.code == NBSP ||
                it.code == NARROW_NBSP
            ) {
                ' '
            } else {
                it
            }
        }.filterNot { it.code in ISOLATES }.joinToString("")

    private fun charge(watts: Double?) =
        ChargingNow(
            levelPct = 62,
            plugType = 1,
            watts = watts,
            currentMa = null,
            voltageMv = 4_200,
            tempDeciC = 300,
            tempRiseDeciC = null,
            phase = null,
            charger = LiveCharger.Learning(1),
            targetPct = 80,
            belowHighest = false,
            toTarget = ChargeTime(18.0, 24.0, ChargeTimeBasis.entries.first(), 3),
            toFull = null,
            startWallMs = 0,
            startLevelPct = 20,
            curve = emptyList(),
            wallMs = 1,
        )

    @Test
    fun theChannelsAreTheFourOfTheSpec() {
        val channels = manager.notificationChannels.associateBy { it.id }
        assertThat(channels.keys).containsExactly("charging", "alerts", "insights", "privileges")
        assertThat(channels.getValue("alerts").importance).isEqualTo(NotificationManager.IMPORTANCE_HIGH)
        // Charging is silent.
        assertThat(channels.getValue("charging").sound).isNull()
    }

    @Test
    fun theLiveUpdateSaysLevelPowerAndTimeToTheTarget() {
        notifier.charging(charge(18.2))
        val live = posted().single()
        assertThat(live.title()).isEqualTo("Charging · 62% · 18 W")
        assertThat(live.text()).isEqualTo("80% in 18 min – 24 min")
        assertThat(
            live.extras
                .getCharSequence("android.shortCriticalText")
                ?.toString()
                ?.plain(),
        ).isEqualTo("62% · 18 W")
        assertThat(live.flags and Notification.FLAG_ONGOING_EVENT).isNotEqualTo(0)
        assertThat(live.extras.getBoolean("android.requestPromotedOngoing")).isTrue()
        assertThat(live.extras.getInt(Notification.EXTRA_PROGRESS)).isEqualTo(42)
    }

    @Test
    fun withoutACalibratedCurrentTheLiveUpdateSaysTheLevelOnly() {
        notifier.charging(charge(null))
        assertThat(posted().single().title()).isEqualTo("Charging · 62%")
    }

    @Test
    fun theSummaryReplacesTheLiveUpdateAndLeavesByItself() {
        notifier.charging(charge(18.2))
        notifier.chargeEnded(ChargeSummary(gainedPct = 46, durationMs = 38 * 60_000L, averageWatts = 21.3))
        val summary = posted().single()
        assertThat(summary.title()).isEqualTo("Charge ended")
        assertThat(summary.text()).isEqualTo("+46% in 38 min, average 21 W")
        assertThat(summary.timeoutAfter).isEqualTo(10_000L)
        assertThat(summary.flags and Notification.FLAG_ONGOING_EVENT).isEqualTo(0)
    }

    @Test
    fun theAlertsNameTheirFigure() {
        notifier.alert(Alert.TargetReached(80))
        notifier.alert(Alert.Hot(423))
        assertThat(posted().map { it.title() }).containsExactly("Charged to 80%", "Battery at 42.3 °C")
        assertThat(posted().map { it.channelId }.toSet()).containsExactly("alerts")
    }

    @Test
    fun theDigestIsOneNotificationForAllNewInsights() {
        notifier.digest(listOf(InsightText("First", "One."), InsightText("Second", "Two.")))
        assertThat(posted().single().title()).isEqualTo("2 new insights")
        notifier.digest(listOf(InsightText("Only", "One.")))
        // The same notification, now the insight itself.
        assertThat(posted().single().title()).isEqualTo("Only")
        assertThat(posted().single().channelId).isEqualTo("insights")
    }

    @Test
    fun aLostAccessIsPostedAndWithdrawn() {
        notifier.privileges(PrivilegeIssue.MANAGER_STOPPED)
        assertThat(posted().single().title()).isEqualTo("Coulomb lost its access")
        notifier.privileges(null)
        assertThat(posted()).isEmpty()
    }

    private companion object {
        const val NBSP = 0x00A0
        const val NARROW_NBSP = 0x202F
        val ISOLATES = setOf(0x2066, 0x2069)
    }
}
