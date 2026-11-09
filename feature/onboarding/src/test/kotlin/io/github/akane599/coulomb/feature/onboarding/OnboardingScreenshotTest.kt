// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.onboarding

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.LayoutDirection
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.then
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.model.AccessTier
import io.github.akane599.coulomb.core.model.Capability
import io.github.akane599.coulomb.core.model.CapabilityId
import io.github.akane599.coulomb.core.model.CapabilityState
import io.github.akane599.coulomb.core.model.GrantStatus
import io.github.akane599.coulomb.core.model.ManagerPermission
import io.github.akane599.coulomb.core.model.ManagerStatus
import io.github.akane599.coulomb.core.model.PrivilegeIssue
import io.github.akane599.coulomb.core.model.PrivilegeState
import io.github.akane599.coulomb.core.model.ServerInfo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Each onboarding step in each state it can be in (SPEC 14.1), the result step as in the lab, dark,
 * at 200% text, in Turkish and in Arabic. A plain Application keeps Hilt out.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class, qualifiers = COMPACT)
class OnboardingScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun welcome() = shoot("welcome", OnboardingUiState())

    @Test
    @Config(qualifiers = "w412dp-h915dp-night-xhdpi")
    fun welcomeDark() = shoot("welcome-dark", OnboardingUiState(), dark = true)

    @Test
    fun welcomeText200() = shoot("welcome-200", OnboardingUiState(), fontScale = 2f)

    @Test
    @Config(qualifiers = "tr-$COMPACT")
    fun welcomeTurkish() = shoot("welcome-tr", OnboardingUiState())

    @Test
    fun permissionsShizuku() = shoot("permissions-shizuku", OnboardingUiState(step = OnboardingStep.PERMISSIONS))

    @Test
    fun permissionsBasic() =
        shoot(
            "permissions-basic",
            OnboardingUiState(step = OnboardingStep.PERMISSIONS, path = OnboardingPath.BASIC),
            OnboardingDevice(notificationsAllowed = true),
        )

    @Test
    fun permissionsDenied() =
        shoot(
            "permissions-denied",
            OnboardingUiState(step = OnboardingStep.PERMISSIONS, path = OnboardingPath.BASIC),
            OnboardingDevice(notificationsDenied = true),
        )

    @Test
    fun shizukuNotInstalled() = shoot("shizuku-missing", shizuku(ManagerStatus.NOT_INSTALLED), ANDROID_17)

    @Test
    fun shizukuAdbShown() {
        shoot("shizuku-missing-adb", shizuku(ManagerStatus.NOT_INSTALLED), ANDROID_17, capture = false)
        compose.onNodeWithText("Show ADB commands").performClick()
        compose.onRoot().captureRoboImage(path("shizuku-missing-adb"))
    }

    @Test
    fun shizukuStopped() = shoot("shizuku-stopped", shizuku(ManagerStatus.NOT_RUNNING), OnboardingDevice(managerApp = true, sdkInt = 36))

    @Test
    fun shizukuAsk() = shoot("shizuku-ask", shizuku(ManagerStatus.RUNNING, ManagerPermission.NOT_REQUESTED), ANDROID_17)

    @Test
    fun shizukuWaiting() = shoot("shizuku-waiting", shizuku(ManagerStatus.RUNNING, ManagerPermission.PENDING), ANDROID_17)

    @Test
    @Config(qualifiers = TALL)
    fun shizukuHang() =
        shoot("shizuku-hang", shizuku(ManagerStatus.RUNNING, ManagerPermission.PENDING, PrivilegeIssue.AUTHORIZATION_HANG), ANDROID_17)

    @Test
    @Config(qualifiers = TALL)
    fun shizukuDenied() = shoot("shizuku-denied", shizuku(ManagerStatus.RUNNING, ManagerPermission.DENIED), OnboardingDevice(sdkInt = 36))

    @Test
    fun shizukuConnected() = shoot("shizuku-connected", shizuku(ManagerStatus.RUNNING, ManagerPermission.GRANTED, root = true), ANDROID_17)

    @Test
    fun resultLab() = shoot("result-lab", labResult(), ONE_UI)

    @Test
    @Config(qualifiers = TALL)
    fun resultPage() = shoot("result-page", labResult(), ONE_UI)

    @Test
    @Config(qualifiers = "$TALL-night")
    fun resultDark() = shoot("result-dark", labResult(), ONE_UI, dark = true)

    @Test
    @Config(qualifiers = "w412dp-h3200dp")
    fun resultText200() = shoot("result-200", labResult(), ONE_UI, fontScale = 2f)

    @Test
    @Config(qualifiers = "tr-$TALL")
    fun resultTurkish() = shoot("result-tr", labResult(), ONE_UI)

    @Test
    @Config(qualifiers = "ar-$TALL")
    fun resultArabic() = shoot("result-ar", labResult(), ONE_UI, rtl = true)

    @Test
    @Config(qualifiers = TALL)
    fun resultBasic() =
        shoot(
            "result-basic",
            OnboardingUiState(
                step = OnboardingStep.RESULT,
                path = OnboardingPath.BASIC,
                privilege = PrivilegeState(manager = ManagerStatus.NOT_INSTALLED),
                adbCommands = ADB,
                result = ResultState.Ready(BASIC),
            ),
        )

    @Test
    fun resultChecking() = shoot("result-checking", labResult().copy(result = ResultState.Checking))

    @Test
    fun resultFailed() = shoot("result-failed", labResult().copy(result = ResultState.Failed))

    private fun shizuku(
        manager: ManagerStatus,
        permission: ManagerPermission = ManagerPermission.UNKNOWN,
        issue: PrivilegeIssue? = null,
        root: Boolean = false,
    ): OnboardingUiState {
        val server = if (manager == ManagerStatus.RUNNING) ServerInfo(13, if (root) 0 else 2000, null, false, "fork", "1") else null
        val privilege =
            PrivilegeState(
                manager = manager,
                server = server,
                permission = permission,
                issue = issue,
                grants = if (permission == ManagerPermission.GRANTED) ALL else GrantStatus(),
            )
        return OnboardingUiState(
            step = OnboardingStep.SHIZUKU,
            privilege = privilege,
            stage = shizukuStage(privilege, bootstrapDone = permission == ManagerPermission.GRANTED),
            adbCommands = ADB,
        )
    }

    private fun labResult() =
        OnboardingUiState(
            step = OnboardingStep.RESULT,
            privilege = PrivilegeState(manager = ManagerStatus.RUNNING, permission = ManagerPermission.GRANTED, grants = ALL),
            stage = ShizukuStage.CONNECTED,
            adbCommands = ADB,
            result = ResultState.Ready(DEEP),
        )

    @Suppress("LongParameterList") // Test variants.
    private fun shoot(
        name: String,
        state: OnboardingUiState,
        device: OnboardingDevice = OnboardingDevice(sdkInt = 36),
        dark: Boolean = false,
        fontScale: Float = 1f,
        rtl: Boolean = false,
        capture: Boolean = true,
    ) {
        // The ldrtl qualifier does not reach the composition under Robolectric; the override does.
        val direction = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
        compose.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.FontScale(fontScale) then DeviceConfigurationOverride.LayoutDirection(direction),
            ) {
                CoulombTheme(darkTheme = dark) {
                    Box(Modifier.fillMaxSize().background(CoulombTheme.colors.bg)) {
                        OnboardingContent(state, device, NoActions)
                    }
                }
            }
        }
        if (capture) compose.onRoot().captureRoboImage(path(name))
    }

    private fun path(name: String) = "src/test/screenshots/onboarding-$name.png"
}

private const val COMPACT = "w412dp-h915dp-xhdpi"
private const val TALL = "w412dp-h1800dp"
private val ANDROID_17 = OnboardingDevice(sdkInt = 37)
private val ONE_UI = OnboardingDevice(oneUi = true, sdkInt = 37)

private val ALL = GrantStatus(batteryStats = true, dump = true, usageStats = true, usageStatsPermission = true, interactAcrossUsers = true)

private val ADB =
    listOf(
        "adb shell pm grant io.github.akane599.coulomb android.permission.BATTERY_STATS",
        "adb shell pm grant io.github.akane599.coulomb android.permission.DUMP",
        "adb shell pm grant io.github.akane599.coulomb android.permission.PACKAGE_USAGE_STATS",
        "adb shell appops set io.github.akane599.coulomb GET_USAGE_STATS allow",
        "adb shell pm grant io.github.akane599.coulomb android.permission.INTERACT_ACROSS_USERS",
    )

private fun capability(id: CapabilityId) = Capability(id, CapabilityState.AVAILABLE, AccessTier.T1_DURABLE_GRANTS, null, null, 0L, "f")

private val DEEP =
    listOf(
        CapabilityId.BATTERY_CHANGED,
        CapabilityId.CURRENT_NOW,
        CapabilityId.BATTERYSTATS_PROTO,
        CapabilityId.BATTERYSTATS_HISTORY,
        CapabilityId.WAKEUP_SOURCES,
    ).map(::capability)

private val BASIC = listOf(CapabilityId.BATTERY_CHANGED, CapabilityId.CURRENT_NOW).map(::capability)

private val NoActions = OnboardingActions({}, {}, {}, {}, {}, {}, {})
