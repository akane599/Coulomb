// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.actions

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.akane599.coulomb.core.analytics.DozeChange
import io.github.akane599.coulomb.core.analytics.DozeProfile
import io.github.akane599.coulomb.core.collect.actions.ActionBlock
import io.github.akane599.coulomb.core.collect.actions.ActionPreview
import io.github.akane599.coulomb.core.collect.actions.ActionRecord
import io.github.akane599.coulomb.core.collect.actions.ActionRequest
import io.github.akane599.coulomb.core.collect.actions.ActionResult
import io.github.akane599.coulomb.core.collect.actions.ActionRisk
import io.github.akane599.coulomb.core.collect.actions.ActionType
import io.github.akane599.coulomb.core.collect.actions.DozeOwner
import io.github.akane599.coulomb.core.collect.actions.DozeState
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.model.ActionOrigin
import io.github.akane599.coulomb.core.model.cmd.StandbyBucket
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneOffset

/** The Action Center (SPEC 13), an app's actions and the preview, in their states. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class, qualifiers = "w412dp-h1900dp-xhdpi")
class ActionCenterScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun on() = center("on", READY)

    @Test
    @Config(qualifiers = "w412dp-h1900dp-night-xhdpi")
    fun onDark() = center("on-dark", READY, dark = true)

    /** SPEC 11.4: the opt-in true black theme, which Settings now offers. */
    @Test
    @Config(qualifiers = "w412dp-h1900dp-night-xhdpi")
    fun onBlack() = center("on-black", READY, dark = true, black = true)

    @Test
    @Config(qualifiers = "tr-w412dp-h1900dp-xhdpi")
    fun onTurkish() = center("on-tr", READY)

    @Test
    @Config(qualifiers = "w412dp-h4200dp")
    fun on200() = center("on-200", READY, fontScale = 2f)

    @Test
    @Config(qualifiers = "w1000dp-h1500dp-xhdpi")
    fun onWide() = center("on-wide", READY)

    @Test
    @Config(qualifiers = "w412dp-h900dp-xhdpi")
    fun off() = center("off", ActionCenterState.Ready(enabled = false, board = null, log = emptyList()))

    @Test
    @Config(qualifiers = "w412dp-h900dp-xhdpi")
    fun offWithALog() = center("off-log", ActionCenterState.Ready(enabled = false, board = null, log = LOG.take(2)))

    @Test
    fun external() =
        center(
            "external",
            READY.copy(
                board =
                    BOARD.copy(
                        doze = DOZE.copy(owner = DozeOwner.EXTERNAL, value = STOCK, idleAfterMs = 5 * MINUTE),
                        balanced = BALANCED.copy(block = ActionBlock.MANAGED_EXTERNALLY, dozeChanges = emptyList()),
                        aggressive = AGGRESSIVE.copy(block = ActionBlock.MANAGED_EXTERNALLY, dozeChanges = emptyList()),
                    ),
                log = emptyList(),
            ),
        )

    @Test
    fun profileApplied() =
        center(
            "profile",
            READY.copy(
                board =
                    BOARD.copy(
                        doze = DOZE.copy(owner = DozeOwner.PROFILE, profile = DozeProfile.BALANCED, idleAfterMs = 30 * MINUTE),
                        balanced = BALANCED.copy(block = ActionBlock.PROFILE_ACTIVE, dozeChanges = emptyList()),
                        aggressive = AGGRESSIVE.copy(block = ActionBlock.PROFILE_ACTIVE, dozeChanges = emptyList()),
                    ),
                log = listOf(PROFILE_ROW) + LOG,
            ),
            note = ActionNote(ActionResult.Done(9, "x"), ActionType.DOZE_PROFILE, revert = false, asked = "x"),
        )

    @Test
    fun nothingToChange() =
        center(
            "nothing",
            READY.copy(
                board =
                    BOARD.copy(
                        doze = DOZE.copy(owner = DozeOwner.BASELINE, value = STOCK, importedName = "2026-11-20", idleAfterMs = 5 * MINUTE),
                        balanced = BALANCED.copy(block = ActionBlock.NOTHING_TO_CHANGE, dozeChanges = emptyList()),
                        aggressive = AGGRESSIVE.copy(dozeChanges = listOf(DozeChange("idle_to", 60 * MINUTE, 120 * MINUTE))),
                        saver = SAVER.copy(block = ActionBlock.CHARGING),
                    ),
                log = listOf(LogRow(record(8, ActionType.DOZE_TAKEOVER, "2026-11-20", STOCK, STOCK), null)),
            ),
        )

    @Test
    fun noWorker() =
        center(
            "no-worker",
            READY.copy(
                board =
                    BOARD.copy(
                        doze = null,
                        balanced = blocked(BALANCED),
                        aggressive = blocked(AGGRESSIVE),
                        saver = blocked(SAVER),
                        reset = blocked(RESET),
                    ),
            ),
        )

    @Test
    @Config(qualifiers = "w412dp-h900dp-xhdpi")
    fun loading() = center("loading", ActionCenterState.Loading)

    @Test
    @Config(qualifiers = "w412dp-h1000dp-xhdpi")
    fun previewRestrict() = sheet("preview-restrict") { ActionPreviewContent(RESTRICT, busy = false, {}, {}, subject = "Halcyon") }

    @Test
    @Config(qualifiers = "tr-w412dp-h1000dp-night-xhdpi")
    fun previewRestrictTurkishDark() =
        sheet("preview-restrict-tr-dark", dark = true) { ActionPreviewContent(RESTRICT, busy = false, {}, {}, subject = "Halcyon") }

    @Test
    @Config(qualifiers = "w412dp-h2400dp")
    fun previewRestrict200() =
        sheet("preview-restrict-200", fontScale = 2f) { ActionPreviewContent(RESTRICT, busy = false, {}, {}, subject = "Halcyon") }

    @Test
    @Config(qualifiers = "w412dp-h1200dp-xhdpi")
    fun previewProfile() = sheet("preview-profile") { ActionPreviewContent(AGGRESSIVE, busy = false, {}, {}) }

    @Test
    @Config(qualifiers = "w412dp-h900dp-xhdpi")
    fun previewReset() = sheet("preview-reset") { ActionPreviewContent(RESET, busy = false, {}, {}) }

    @Test
    @Config(qualifiers = "w412dp-h900dp-xhdpi")
    fun previewNoWorker() =
        sheet("preview-no-worker") { ActionPreviewContent(blocked(RESTRICT), busy = false, {}, {}, subject = "Halcyon") }

    /** Without the helper and without a fixed inverse: the undo needs the value read first. */
    @Test
    @Config(qualifiers = "w412dp-h900dp-xhdpi")
    fun previewNoWorkerBucket() =
        sheet("preview-no-worker-bucket") {
            ActionPreviewContent(
                blocked(
                    ActionPreview(
                        request = ActionRequest.SetStandbyBucket(APP_PACKAGE, StandbyBucket.RARE),
                        risk = ActionRisk.MEDIUM,
                        command = "adb shell am set-standby-bucket --user 0 $APP_PACKAGE rare",
                        revertCommand = null,
                        before = null,
                        after = null,
                        block = null,
                    ),
                ),
                busy = false,
                {},
                {},
                subject = "Halcyon",
            )
        }

    @Test
    @Config(qualifiers = "w412dp-h1000dp-xhdpi")
    fun previewBusy() = sheet("preview-busy") { ActionPreviewContent(RESTRICT, busy = true, {}, {}, subject = "Halcyon") }

    /** SPEC 12.7: what another app asked for is in the log, the refusals with it. */
    @Test
    @Config(qualifiers = "w412dp-h900dp-xhdpi")
    fun automationLog() = center("automation-log", ActionCenterState.Ready(enabled = false, board = null, log = AUTOMATION_LOG))

    @Test
    @Config(qualifiers = "tr-w412dp-h900dp-night-xhdpi")
    fun automationLogTurkishDark() =
        center("automation-log-tr-dark", ActionCenterState.Ready(enabled = false, board = null, log = AUTOMATION_LOG), dark = true)

    @Test
    @Config(qualifiers = "w412dp-h900dp-xhdpi")
    fun automationDetails() =
        sheet("automation-details") {
            LogDetails(
                AUTOMATION_LOG[2],
                io.github.akane599.coulomb.core.ui.format
                    .rememberFormats(),
                CLOCK,
                busy = false,
                {},
                {},
            )
        }

    /** A page at a time: a long log does not lay out every row it holds. */
    @Test
    @Config(qualifiers = "w412dp-h900dp-xhdpi")
    fun aLongLogShowsAPageAndTheRestOnRequest() {
        val long = (1L..60L).map { LogRow(automation(100 + it, "snapshot_now", null, "accepted", minutesAgo = it), null) }
        compose.setContent {
            CoulombTheme {
                ActionCenterContent(
                    ActionCenterState.Ready(enabled = false, board = null, log = long),
                    FlowState(),
                    CenterActions(),
                    clock = CLOCK,
                )
            }
        }

        compose.onAllNodesWithText("Automation call").assertCountEquals(50)
        compose.onNodeWithText("Show older changes").performScrollTo().performClick()
        compose.onAllNodesWithText("Automation call").assertCountEquals(60)
        compose.onNodeWithText("Show older changes").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w412dp-h900dp-xhdpi")
    fun logDetails() =
        sheet("log-details") {
            LogDetails(
                LOG.first(),
                io.github.akane599.coulomb.core.ui.format
                    .rememberFormats(),
                CLOCK,
                busy = false,
                {},
                {},
            )
        }

    @Test
    @Config(qualifiers = "w412dp-h900dp-xhdpi")
    fun app() = sheet("app") { AppActionsList(APP, FlowState(), AppActions()) }

    @Test
    @Config(qualifiers = "tr-w412dp-h900dp-xhdpi")
    fun appTurkish() = sheet("app-tr") { AppActionsList(APP, FlowState(), AppActions()) }

    @Test
    @Config(qualifiers = "w412dp-h2000dp")
    fun app200() = sheet("app-200", fontScale = 2f) { AppActionsList(APP, FlowState(), AppActions()) }

    @Test
    @Config(qualifiers = "w412dp-h900dp-xhdpi")
    fun appRestricted() =
        sheet("app-restricted") {
            AppActionsList(
                APP.copy(
                    packages = listOf("app.halcyon", "app.halcyon.sync"),
                    items =
                        APP.items.mapIndexed { index, item ->
                            if (index ==
                                1
                            ) {
                                AppActionItem(item.preview.copy(before = "ignore", block = ActionBlock.ALREADY), LOG.first().record)
                            } else {
                                item
                            }
                        },
                ),
                FlowState(
                    note = ActionNote(ActionResult.Done(1, "ignore"), ActionType.RESTRICT_BACKGROUND, revert = false, asked = "ignore"),
                ),
                AppActions(),
            )
        }

    @Test
    @Config(qualifiers = "w412dp-h400dp-xhdpi")
    fun appOff() = sheet("app-off") { AppActionsList(AppActionsState.Off, FlowState(), AppActions()) }

    @Test
    @Config(qualifiers = "w412dp-h400dp-xhdpi")
    fun appSystem() =
        sheet("app-system") {
            AppActionsList(
                APP.copy(items = APP.items.map { AppActionItem(it.preview.copy(block = ActionBlock.PROTECTED, before = null), null) }),
                FlowState(),
                AppActions(),
            )
        }

    private fun center(
        name: String,
        state: ActionCenterState,
        dark: Boolean = false,
        fontScale: Float = 1f,
        note: ActionNote? = null,
        black: Boolean = false,
    ) = shoot(name, dark, fontScale, black) { ActionCenterContent(state, FlowState(note = note), CenterActions(), clock = CLOCK) }

    private fun sheet(
        name: String,
        dark: Boolean = false,
        fontScale: Float = 1f,
        content: @Composable () -> Unit,
    ) = shoot(name, dark, fontScale) {
        Box(Modifier.fillMaxSize().background(CoulombTheme.colors.surfaceRaised).padding(16.dp)) { content() }
    }

    private fun shoot(
        name: String,
        dark: Boolean,
        fontScale: Float,
        black: Boolean = false,
        content: @Composable () -> Unit,
    ) {
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale)) {
                CoulombTheme(darkTheme = dark, trueBlack = black) {
                    Box(Modifier.fillMaxSize().background(CoulombTheme.colors.bg)) { content() }
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/actions-$name.png")
    }
}

private const val MINUTE = 60_000L
private const val ACTIONS_NOW = 1_763_719_200_000L
private val CLOCK = LogClock(ZoneOffset.UTC, ACTIONS_NOW)
private const val APP_PACKAGE = "app.halcyon"
private const val STOCK = "light_after_inactive_to=30000,inactive_to=180000,idle_after_inactive_to=120000,skip_sensing=true"
private const val AGGRESSIVE_VALUE =
    "light_after_inactive_to=60000,inactive_to=300000,idle_after_inactive_to=300000,motion_inactive_to=300000,idle_to=7200000"

private fun blocked(preview: ActionPreview) =
    preview.copy(
        block = ActionBlock.NO_WORKER,
        before = null,
        after = null,
        // The planner still knows the inverse where it does not depend on a value read from the phone.
        revertCommand = preview.revertCommand.takeIf { preview.request is ActionRequest.RestrictBackground },
        dozeChanges = emptyList(),
        command = preview.command.takeIf { preview.request !is ActionRequest.ApplyDozeProfile },
    )

private fun record(
    id: Long,
    type: ActionType,
    target: String,
    before: String?,
    after: String,
    minutesAgo: Long = id * 37,
    reverted: Boolean = false,
    drift: String? = null,
    command: String? = null,
) = ActionRecord(
    id = id,
    type = type,
    target = target,
    before = before,
    after = after,
    executedWallMs = ACTIONS_NOW - minutesAgo * MINUTE,
    revertedWallMs = if (reverted) ACTIONS_NOW - minutesAgo * MINUTE / 2 else null,
    origin = if (id == 4L) ActionOrigin.INSIGHT else ActionOrigin.USER,
    driftWallMs = drift?.let { ACTIONS_NOW - minutesAgo * MINUTE / 3 },
    driftValue = drift,
    revertCommand = command,
)

private fun automation(
    id: Long,
    target: String,
    before: String?,
    after: String,
    minutesAgo: Long,
    type: ActionType = ActionType.AUTOMATION_CALL,
) = record(id, type, target, before, after, minutesAgo).copy(origin = ActionOrigin.AUTOMATION)

private val AUTOMATION_LOG =
    listOf(
        LogRow(automation(25, "snapshot_now app.northwind.tasks", null, "accepted", minutesAgo = 12), null),
        LogRow(automation(24, "set_charge_target app.northwind.tasks", "80", "65", minutesAgo = 95), null),
        LogRow(
            automation(23, "apply_doze_profile app.northwind.tasks", "aggressive", "refused:needs_first_apply", minutesAgo = 9 * 60),
            null,
        ),
        LogRow(automation(22, "apply_doze_profile", "balanced", "stock", minutesAgo = 11 * 60), null),
        LogRow(
            automation(21, "balanced", "", "light_after_inactive_to=180000", minutesAgo = 20 * 60, type = ActionType.DOZE_PROFILE)
                .copy(revertedWallMs = ACTIONS_NOW - 11 * 60 * MINUTE),
            null,
        ),
        LogRow(automation(20, "set_charge_target", "101", "refused:bad_target", minutesAgo = 26 * 60), null),
    )

private val LOG =
    listOf(
        LogRow(
            record(
                4,
                ActionType.RESTRICT_BACKGROUND,
                APP_PACKAGE,
                "allow",
                "ignore",
                command = "adb shell cmd appops set --user 0 $APP_PACKAGE RUN_ANY_IN_BACKGROUND allow",
            ),
            "Halcyon",
        ),
        LogRow(record(3, ActionType.STANDBY_BUCKET, "app.tidewater.mail", "active", "rare", drift = "working_set"), "Tidewater Mail"),
        LogRow(record(2, ActionType.FORCE_STOP, "app.lumen.player", null, "stopped", minutesAgo = 26 * 60), "Lumen Player"),
        LogRow(record(1, ActionType.BATTERY_SAVER, "device", "off", "on", minutesAgo = 50 * 60, reverted = true), null),
    )

private val PROFILE_ROW =
    LogRow(
        record(
            9,
            ActionType.DOZE_PROFILE,
            "balanced",
            null,
            "inactive_to=900000,idle_after_inactive_to=900000",
            minutesAgo = 1,
            command = "adb shell cmd settings --user 0 delete global device_idle_constants",
        ),
        null,
    )

private val DOZE = DozeState(owner = DozeOwner.NOBODY, value = null, profile = null, importedName = null, idleAfterMs = 60 * MINUTE)

private val BALANCED =
    ActionPreview(
        request = ActionRequest.ApplyDozeProfile(DozeProfile.BALANCED),
        risk = ActionRisk.MEDIUM,
        command = "adb shell cmd settings --user 0 put global device_idle_constants inactive_to=900000,idle_after_inactive_to=900000",
        revertCommand = "adb shell cmd settings --user 0 delete global device_idle_constants",
        before = null,
        after = "inactive_to=900000,idle_after_inactive_to=900000",
        block = null,
        dozeChanges =
            listOf(
                DozeChange("inactive_to", 30 * MINUTE, 15 * MINUTE),
                DozeChange(
                    "idle_after_inactive_to",
                    30 * MINUTE,
                    15 * MINUTE,
                ),
            ),
    )

private val AGGRESSIVE =
    BALANCED.copy(
        request = ActionRequest.ApplyDozeProfile(DozeProfile.AGGRESSIVE),
        command = "adb shell cmd settings --user 0 put global device_idle_constants $AGGRESSIVE_VALUE",
        after = AGGRESSIVE_VALUE,
        dozeChanges =
            listOf(
                DozeChange("light_after_inactive_to", 4 * MINUTE, MINUTE),
                DozeChange("inactive_to", 30 * MINUTE, 5 * MINUTE),
                DozeChange("idle_after_inactive_to", 30 * MINUTE, 5 * MINUTE),
                DozeChange("motion_inactive_to", 10 * MINUTE, 5 * MINUTE),
                DozeChange("idle_to", 60 * MINUTE, 120 * MINUTE),
            ),
    )

private val SAVER =
    ActionPreview(
        request = ActionRequest.BatterySaver(on = true),
        risk = ActionRisk.LOW,
        command = "adb shell cmd power set-mode 1",
        revertCommand = "adb shell cmd power set-mode 0",
        before = "off",
        after = "on",
        block = null,
    )

private val RESET =
    ActionPreview(
        request = ActionRequest.ResetBatteryStats,
        risk = ActionRisk.HIGH,
        command = "adb shell dumpsys batterystats --reset",
        revertCommand = null,
        before = null,
        after = "reset",
        block = null,
    )

private val TAKE_OVER =
    ActionPreview(ActionRequest.TakeOverDoze("2026-11-21"), ActionRisk.LOW, null, null, null, "", ActionBlock.NOTHING_TO_TAKE_OVER)

private val BOARD = DeviceBoard(DOZE, BALANCED, AGGRESSIVE, TAKE_OVER, SAVER, RESET)

private val READY = ActionCenterState.Ready(enabled = true, board = BOARD, log = LOG)

private val RESTRICT =
    ActionPreview(
        request = ActionRequest.RestrictBackground(APP_PACKAGE),
        risk = ActionRisk.MEDIUM,
        command = "adb shell cmd appops set --user 0 $APP_PACKAGE RUN_ANY_IN_BACKGROUND ignore",
        revertCommand = "adb shell cmd appops set --user 0 $APP_PACKAGE RUN_ANY_IN_BACKGROUND allow",
        before = "allow",
        after = "ignore",
        block = null,
    )

private fun appPreview(
    request: ActionRequest,
    before: String?,
    after: String,
    block: ActionBlock? = null,
) = ActionPreview(request, ActionRisk.MEDIUM, "adb shell true", null, before, after, block)

private val APP =
    AppActionsState.Ready(
        packages = listOf(APP_PACKAGE),
        selected = APP_PACKAGE,
        label = "Halcyon",
        items =
            listOf(
                AppActionItem(appPreview(ActionRequest.ForceStop(APP_PACKAGE), null, "stopped"), null),
                AppActionItem(RESTRICT, null),
                AppActionItem(appPreview(ActionRequest.SetStandbyBucket(APP_PACKAGE, StandbyBucket.RARE), "working_set", "rare"), null),
                AppActionItem(
                    appPreview(ActionRequest.SetStandbyBucket(APP_PACKAGE, StandbyBucket.RESTRICTED), "working_set", "restricted"),
                    null,
                ),
                AppActionItem(
                    appPreview(ActionRequest.DozeAllowlist(APP_PACKAGE, listed = true), "unlisted", "listed", ActionBlock.SYSTEM_LISTED),
                    null,
                ),
            ),
    )
