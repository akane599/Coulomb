// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.drain

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.LayoutDirection
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.then
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import io.github.akane599.coulomb.core.data.AppDrain
import io.github.akane599.coulomb.core.data.DrainSpan
import io.github.akane599.coulomb.core.data.LevelEstimate
import io.github.akane599.coulomb.core.data.WakeCoverage
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.model.PrivilegeIssue
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.model.WakeKind
import io.github.akane599.coulomb.core.model.kernel.KernelCoverage.Coverage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Drain in each SPEC 10.11 state and lens, at the lab's 412 x 915 dp, light and dark, at 200% text,
 * at wider sizes, in Turkish and in Arabic. A plain Application keeps Hilt out.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class, qualifiers = COMPACT)
class DrainScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun appsLight() = shoot("apps-light", ready())

    @Test
    @Config(qualifiers = "$COMPACT_SIZE-night-xhdpi")
    fun appsDark() = shoot("apps-dark", ready(), dark = true)

    @Test
    @Config(qualifiers = "$COMPACT_SIZE-night-xhdpi")
    fun appsBlack() = shoot("apps-black", ready(), dark = true, black = true)

    @Test
    fun appsText130() = shoot("apps-130", ready(), fontScale = 1.3f)

    /** A phone on its side: an expanded width with a short height, so the column is capped. */
    @Test
    @Config(qualifiers = "w915dp-h412dp-xhdpi")
    fun appsLandscape() = shoot("apps-landscape", ready())

    /** On its side at 200% text two columns no longer fit, so the screen is one list again. */
    @Test
    @Config(qualifiers = "w915dp-h412dp-xhdpi")
    fun appsLandscapeLargeText() = shoot("apps-landscape-200", ready(), fontScale = 2f)

    /** On its side with an issue: the selector and the block scroll with the summary, so the rows keep their height. */
    @Test
    @Config(qualifiers = "w915dp-h412dp-xhdpi")
    fun privilegeIssueLandscape() = shoot("issue-landscape", ready().copy(issue = PrivilegeIssue.MANAGER_STOPPED))

    /** A 700 dp window beside the rail leaves about 620 dp: one list. */
    @Test
    @Config(qualifiers = "w620dp-h1400dp-xhdpi")
    fun appsMediumBesideRail() = shoot("apps-medium-rail", ready())

    @Test
    @Config(qualifiers = "w412dp-h2200dp-xhdpi")
    fun appsPage() = shoot("apps-page", ready())

    @Test
    @Config(qualifiers = "w412dp-h4000dp-xhdpi")
    fun appsPageLargeText() = shoot("apps-page-200", ready(), fontScale = 2f)

    @Test
    @Config(qualifiers = "w1000dp-h1400dp-xhdpi")
    fun appsExpanded() = shoot("apps-expanded", ready())

    @Test
    @Config(qualifiers = "w700dp-h1400dp-xhdpi")
    fun appsMedium() = shoot("apps-medium", ready())

    @Test
    @Config(qualifiers = "tr-w412dp-h2200dp-xhdpi")
    fun appsTurkish() = shoot("apps-tr", ready())

    @Test
    @Config(qualifiers = "ar-w412dp-h2200dp-xhdpi")
    fun appsArabic() = shoot("apps-ar", ready(), rtl = true)

    /** System apps hidden, then the remainder opened: each small app, the hidden ones as a row, "Show fewer". */
    @Test
    @Config(qualifiers = "w412dp-h2600dp-xhdpi")
    fun remainderOpen() {
        shoot("remainder-open", ready(includeSystem = false), capture = false)
        compose.onNodeWithText("6 more apps", substring = true).performClick()
        compose.onRoot().captureRoboImage("src/test/screenshots/drain-remainder-open.png")
    }

    @Test
    @Config(qualifiers = "w412dp-h2200dp-xhdpi")
    fun scaled() = shoot("scaled", ready(scaled = true))

    /** SPEC 11.12 harden: long names, six-digit figures, an estimated total, a charge, a lower bound, right to left. */
    @Test
    @Config(qualifiers = "w412dp-h2200dp-xhdpi")
    fun hardenedRightToLeft() = shoot("harden-rtl", hardened(), rtl = true)

    @Test
    @Config(qualifiers = "w412dp-h4000dp-xhdpi")
    fun hardenedLargeText() = shoot("harden-200", hardened(), fontScale = 2f)

    @Test
    @Config(qualifiers = "w412dp-h1600dp-xhdpi")
    fun componentsCoarse() = shoot("components-coarse", ready(), lens = Lens.COMPONENTS)

    @Test
    @Config(qualifiers = "w412dp-h1600dp-xhdpi")
    fun componentsFull() = shoot("components-full", ready(report(components = fullComponents())), lens = Lens.COMPONENTS)

    @Test
    @Config(qualifiers = "w412dp-h1600dp-night-xhdpi")
    fun componentsDark() = shoot("components-dark", ready(report(components = fullComponents())), lens = Lens.COMPONENTS, dark = true)

    @Test
    @Config(qualifiers = "w412dp-h2600dp-xhdpi")
    fun wakeups() = shoot("wakeups", ready(), lens = Lens.WAKEUPS)

    @Test
    @Config(qualifiers = "w412dp-h4600dp-xhdpi")
    fun wakeupsLargeText() = shoot("wakeups-200", ready(), lens = Lens.WAKEUPS, fontScale = 2f)

    @Test
    @Config(qualifiers = "w412dp-h2600dp-xhdpi")
    fun wakeupsPartlyCollected() {
        // Root for part of the window, S5 lost at one end: S5 groups partial, S27 never collected.
        val coverage = WakeCoverage(Coverage.PARTIAL, Coverage.PARTIAL, Coverage.NONE)
        val report = report(wakeCoverage = coverage).let { r -> r.copy(wakes = r.wakes.filter { it.kind == WakeKind.PARTIAL }) }
        shoot("wakeups-partial", ready(report), lens = Lens.WAKEUPS)
    }

    @Test
    fun wakeupsNotCollected() {
        val nothing = WakeCoverage(Coverage.NONE, Coverage.NONE, Coverage.NONE)
        shoot(
            "wakeups-not-collected",
            ready(report(wakeCoverage = nothing).copy(wakes = emptyList(), alarms = emptyList())),
            lens = Lens.WAKEUPS,
        )
    }

    @Test
    fun estimatedRange() {
        val estimated =
            labDrains().copy(
                measuredProvenance = Provenance.ESTIMATED,
                levelEstimate = LevelEstimate(spreadMah = 49.0, stretches = 1, withCounter = false),
            )
        shoot("estimated", ready(report(drains = estimated)))
    }

    @Test
    fun staleWhileANewChoiceLoads() = shoot("stale", ready(chosen = DrainSpan.Today).copy(current = false))

    @Test
    fun privilegeIssue() = shoot("issue", ready().copy(issue = PrivilegeIssue.MANAGER_STOPPED))

    @Test
    fun privilegeIssueWithoutData() = shoot("issue-empty", ready(report = null).copy(issue = PrivilegeIssue.MANAGER_STOPPED))

    @Test
    fun lastNight() = shoot("last-night", ready(lastNightReport()))

    @Test
    fun days() = shoot("days", ready(report(DrainSpan.Days(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 6)))))

    @Test
    fun emptySinceUnplugged() = shoot("empty", ready(report = null))

    @Test
    fun emptyLastNight() = shoot("empty-last-night", ready(report = null, chosen = DrainSpan.LastNight))

    @Test
    fun loading() = shoot("loading", DrainUiState.Loading)

    @Test
    fun error() = shoot("error", DrainUiState.Error)

    /** The More sheet is a window of its own, so the capture takes the whole screen. */
    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun moreSheet() {
        shoot("sheet", ready(), capture = false)
        compose.onNodeWithText("More").performClick()
        compose.waitForIdle()
        captureScreenRoboImage("src/test/screenshots/drain-sheet.png")
    }

    /** The reconcile bar's "View as table" action opens its table in a sheet (ADR-0036 §25). */
    @OptIn(ExperimentalRoborazziApi::class, ExperimentalTestApi::class)
    @Test
    fun reconcileTable() {
        shoot("reconcile-table", ready(), capture = false)
        compose.onAllNodes(hasViewAsTable)[0].performCustomAccessibilityActionWithLabel("View as table")
        compose.waitForIdle()
        captureScreenRoboImage("src/test/screenshots/drain-reconcile-table.png")
    }

    @OptIn(ExperimentalRoborazziApi::class, ExperimentalTestApi::class)
    @Test
    fun componentsTable() {
        shoot("components-table", ready(report(components = fullComponents())), lens = Lens.COMPONENTS, capture = false)
        // The reconcile bar comes first; the component bar second.
        compose.onAllNodes(hasViewAsTable)[1].performCustomAccessibilityActionWithLabel("View as table")
        compose.waitForIdle()
        captureScreenRoboImage("src/test/screenshots/drain-components-table.png")
    }

    /** The custom range picker, in a past month so that Material's ring for today never shows. */
    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun rangeDialog() {
        shoot("range", ready(), today = LocalDate.of(2025, 6, 18), capture = false)
        compose.onNodeWithText("More").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Custom range").performClick()
        compose.waitForIdle()
        captureScreenRoboImage("src/test/screenshots/drain-range.png")
    }

    /** In a short window the picker opens for typing the dates. */
    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    @Config(qualifiers = "w915dp-h412dp-xhdpi")
    fun rangeDialogLandscape() {
        shoot("range-landscape", ready(), today = LocalDate.of(2025, 6, 18), capture = false)
        compose.onNodeWithText("More").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Custom range").performClick()
        compose.waitForIdle()
        captureScreenRoboImage("src/test/screenshots/drain-range-landscape.png")
    }

    @Test
    @Config(qualifiers = "tr-w412dp-h1600dp-xhdpi")
    fun componentsTurkish() = shoot("components-tr", ready(report(components = fullComponents())), lens = Lens.COMPONENTS)

    @Test
    @Config(qualifiers = "w412dp-h2000dp-xhdpi")
    fun componentsText130() =
        shoot("components-130", ready(report(components = fullComponents())), lens = Lens.COMPONENTS, fontScale = 1.3f)

    @Test
    @Config(qualifiers = "w412dp-h1600dp-night-xhdpi")
    fun componentsBlack() =
        shoot("components-black", ready(report(components = fullComponents())), lens = Lens.COMPONENTS, dark = true, black = true)

    @Test
    @Config(qualifiers = "w1000dp-h1400dp-xhdpi")
    fun componentsExpanded() = shoot("components-expanded", ready(report(components = fullComponents())), lens = Lens.COMPONENTS)

    @Test
    @Config(qualifiers = "tr-w412dp-h2600dp-xhdpi")
    fun wakeupsTurkish() = shoot("wakeups-tr", ready(), lens = Lens.WAKEUPS)

    @Test
    @Config(qualifiers = "w412dp-h3200dp-xhdpi")
    fun wakeupsText130() = shoot("wakeups-130", ready(), lens = Lens.WAKEUPS, fontScale = 1.3f)

    @Test
    @Config(qualifiers = "w412dp-h2600dp-night-xhdpi")
    fun wakeupsBlack() = shoot("wakeups-black", ready(), lens = Lens.WAKEUPS, dark = true, black = true)

    @Test
    @Config(qualifiers = "w1000dp-h1800dp-xhdpi")
    fun wakeupsExpanded() = shoot("wakeups-expanded", ready(), lens = Lens.WAKEUPS)

    @Test
    @Config(qualifiers = "tr-w412dp-h4600dp-xhdpi")
    fun wakeupsTurkishLargeText() = shoot("wakeups-tr-200", ready(), lens = Lens.WAKEUPS, fontScale = 2f)

    @Test
    @Config(qualifiers = "tr-w412dp-h4000dp-xhdpi")
    fun appsTurkishLargeText() = shoot("apps-tr-200", ready(), fontScale = 2f)

    @Suppress("LongParameterList") // Test variants.
    private fun shoot(
        name: String,
        state: DrainUiState,
        dark: Boolean = false,
        black: Boolean = false,
        fontScale: Float = 1f,
        rtl: Boolean = false,
        lens: Lens = Lens.APPS,
        today: LocalDate = LocalDate.of(2026, 11, 8),
        capture: Boolean = true,
    ) {
        // The ldrtl qualifier does not reach the composition under Robolectric; the override does.
        val direction = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
        compose.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.FontScale(fontScale) then DeviceConfigurationOverride.LayoutDirection(direction),
            ) {
                CoulombTheme(darkTheme = dark, trueBlack = black) {
                    Box(Modifier.fillMaxSize().background(CoulombTheme.colors.bg)) {
                        DrainContent(
                            state = state,
                            actions = NoActions,
                            zone = ZoneOffset.UTC,
                            today = today,
                            initialLens = lens,
                        )
                    }
                }
            }
        }
        if (capture) compose.onRoot().captureRoboImage("src/test/screenshots/drain-$name.png")
    }
}

private const val COMPACT_SIZE = "w412dp-h915dp"

/** A node that offers "View as table" to TalkBack (ADR-0023 §5). */
private val hasViewAsTable =
    SemanticsMatcher("offers View as table") { node ->
        node.config
            .getOrNull(SemanticsActions.CustomActions)
            .orEmpty()
            .any { it.label == "View as table" }
    }
private const val COMPACT = "$COMPACT_SIZE-xhdpi"

private val NoActions =
    DrainActions(
        choose = {},
        refresh = {},
        includeSystem = {},
        scale = {},
        retry = {},
        openApp = {},
        openPrivileges = {},
        openCompare = {},
        icon = { _, _ -> null },
    )

/** Last night: 23:40 to 07:10 with the screen off, lab-sized figures. */
private fun lastNightReport() =
    report(DrainSpan.LastNight).let {
        val from = NOW - 14 * HOUR - 40 * MINUTE
        val to = NOW - 7 * HOUR - 10 * MINUTE
        // A tenth of the day's apps; the modeled total is their sum with the device and the rest.
        val apps = it.drains.apps.map { app -> app.copy(mah = app.mah / 10) }
        val modeled = apps.sumOf { app -> app.mah } + 9.0 + 1.8
        it.copy(
            drains =
                it.drains.copy(
                    fromWallMs = from,
                    toWallMs = to,
                    measuredMah = 162.0,
                    modeledMah = modeled,
                    apps = apps,
                    otherMah = 1.8,
                    deviceMah = 9.0,
                ),
            startLevel = 81,
            endLevel = 76,
            durationMs = to - from,
            screenOffMs = to - from,
            lowerBound = true,
        )
    }

private fun hardened(): DrainUiState.Ready {
    val base = report()
    val apps =
        listOf(
            AppDrain(10_123, "Very Long Navigation And Offline Maps Application Name", false, null, 123_456.0),
            AppDrain(10_241, "Wayline", isSystem = false, iconPackage = null, mah = 4.2),
            AppDrain(10_999, null, isSystem = false, iconPackage = null, mah = 0.3),
        )
    val drains =
        base.drains.copy(
            measuredMah = 140_000.0,
            measuredProvenance = Provenance.ESTIMATED,
            modeledMah = 124_000.0,
            apps = apps,
            otherMah = 120.0,
            deviceMah = 300.0,
        )
    return ready(base.copy(drains = drains, chargedInSpan = true, lowerBound = true))
}
