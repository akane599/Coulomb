// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.settings

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.LayoutDirection
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.then
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.model.AccessTier
import io.github.akane599.coulomb.core.model.Capability
import io.github.akane599.coulomb.core.model.CapabilityId
import io.github.akane599.coulomb.core.model.CapabilityState
import io.github.akane599.coulomb.core.model.FixAction
import io.github.akane599.coulomb.core.model.GrantStatus
import io.github.akane599.coulomb.core.model.ManagerPermission
import io.github.akane599.coulomb.core.model.ManagerStatus
import io.github.akane599.coulomb.core.model.PrivilegeIssue
import io.github.akane599.coulomb.core.model.PrivilegeState
import io.github.akane599.coulomb.core.model.ServerInfo
import io.github.akane599.coulomb.core.model.WorkerFailure
import io.github.akane599.coulomb.core.model.WorkerStatus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneOffset

/**
 * Privileges in each state SPEC 4.5 and 14.3 describe: connected with the full matrix, basic mode,
 * a hang, revoked grants, Shizuku stopped, before the first probe; dark, 200% text, Turkish, Arabic
 * and two columns. A plain Application keeps Hilt out.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class, qualifiers = COMPACT)
class PrivilegesScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun connected() = shoot("connected", linked())

    @Test
    @Config(qualifiers = TALL)
    fun connectedPage() = shoot("connected-page", linked())

    @Test
    @Config(qualifiers = "$TALL-night")
    fun connectedDark() = shoot("connected-dark", linked(), dark = true)

    @Test
    @Config(qualifiers = "w412dp-h5200dp")
    fun connectedText200() = shoot("connected-200", linked(), fontScale = 2f)

    @Test
    @Config(qualifiers = "tr-$TALL")
    fun connectedTurkish() = shoot("connected-tr", linked())

    @Test
    @Config(qualifiers = "ar-$TALL")
    fun connectedArabic() = shoot("connected-ar", linked(), rtl = true)

    @Test
    @Config(qualifiers = "w1280dp-h1400dp")
    fun connectedWide() = shoot("connected-wide", linked())

    @Test
    @Config(qualifiers = TALL)
    fun basic() =
        shoot(
            "basic",
            PrivilegesUiState(
                privilege = PrivilegeState(manager = ManagerStatus.NOT_INSTALLED, issue = PrivilegeIssue.MANAGER_NOT_INSTALLED),
                capabilities = BASIC,
                adbCommands = ADB,
            ),
            ANDROID_17,
        )

    @Test
    @Config(qualifiers = TALL)
    fun hang() =
        shoot(
            "hang",
            PrivilegesUiState(
                privilege =
                    PrivilegeState(
                        manager = ManagerStatus.RUNNING,
                        server = SHELL,
                        permission = ManagerPermission.PENDING,
                        issue = PrivilegeIssue.AUTHORIZATION_HANG,
                    ),
                capabilities = BASIC,
                adbCommands = ADB,
            ),
            ANDROID_17,
        )

    @Test
    @Config(qualifiers = TALL)
    fun revoked() =
        shoot(
            "revoked",
            PrivilegesUiState(
                privilege =
                    PrivilegeState(
                        manager = ManagerStatus.RUNNING,
                        server = SHELL,
                        permission = ManagerPermission.GRANTED,
                        grants = GrantStatus(batteryStats = true),
                        issue = PrivilegeIssue.GRANTS_REVOKED,
                    ),
                capabilities = MATRIX,
                adbCommands = ADB,
                probeFailed = true,
            ),
        )

    @Test
    fun stopped() =
        shoot(
            "stopped",
            PrivilegesUiState(
                privilege = PrivilegeState(manager = ManagerStatus.NOT_RUNNING, grants = ALL, issue = PrivilegeIssue.MANAGER_STOPPED),
                capabilities = MATRIX,
                adbCommands = ADB,
            ),
            DEVICE.copy(managerApp = true),
        )

    @Test
    fun ask() =
        shoot(
            "ask",
            PrivilegesUiState(
                privilege = PrivilegeState(manager = ManagerStatus.RUNNING, server = SHELL, permission = ManagerPermission.NOT_REQUESTED),
                adbCommands = ADB,
                probing = true,
            ),
            ANDROID_17,
        )

    @Test
    @Config(qualifiers = TALL)
    fun helperTimeout() =
        shoot(
            "helper-timeout",
            linked().let {
                it.copy(privilege = it.privilege.copy(worker = WorkerStatus.UNAVAILABLE, workerFailure = WorkerFailure.BIND_TIMEOUT))
            },
        )

    @Test
    @Config(qualifiers = TALL)
    fun exported() = shoot("exported", linked().copy(export = ExportResult.SAVED))

    private fun linked() =
        PrivilegesUiState(
            privilege =
                PrivilegeState(
                    manager = ManagerStatus.RUNNING,
                    server = SHELL,
                    permission = ManagerPermission.GRANTED,
                    worker = WorkerStatus.IDLE,
                    grants = ALL,
                ),
            capabilities = MATRIX,
            adbCommands = ADB,
        )

    @Suppress("LongParameterList") // Test variants.
    private fun shoot(
        name: String,
        state: PrivilegesUiState,
        device: PrivilegesDevice = DEVICE,
        dark: Boolean = false,
        fontScale: Float = 1f,
        rtl: Boolean = false,
    ) {
        val direction = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
        compose.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.FontScale(fontScale) then DeviceConfigurationOverride.LayoutDirection(direction),
            ) {
                CoulombTheme(darkTheme = dark) {
                    Box(Modifier.fillMaxSize().background(CoulombTheme.colors.bg)) {
                        PrivilegesContent(state, device, NoActions)
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/privileges-$name.png")
    }
}

private const val COMPACT = "w412dp-h915dp-xhdpi"
private const val TALL = "w412dp-h2600dp"

/** 2026-11-09 14:00 UTC; the matrix was probed at 13:42. */
private const val NOW_MS = 1_762_696_800_000L
private const val PROBED_MS = NOW_MS - 18 * 60_000L

private val DEVICE = PrivilegesDevice(sdkInt = 36, managerApp = true, managerLabel = "Shizuku", nowMs = NOW_MS, zone = ZoneOffset.UTC)
private val ANDROID_17 = DEVICE.copy(sdkInt = 37)
private val SHELL = ServerInfo(13, ServerInfo.SHELL_UID, "u:r:shell:s0", false, "fork", "13.6.0.r1086")

private val ALL = GrantStatus(batteryStats = true, dump = true, usageStats = true, usageStatsPermission = true, interactAcrossUsers = true)

private val ADB =
    listOf(
        "adb shell pm grant io.github.akane599.coulomb android.permission.BATTERY_STATS",
        "adb shell pm grant io.github.akane599.coulomb android.permission.DUMP",
        "adb shell pm grant io.github.akane599.coulomb android.permission.PACKAGE_USAGE_STATS",
        "adb shell appops set io.github.akane599.coulomb GET_USAGE_STATS allow",
        "adb shell pm grant io.github.akane599.coulomb android.permission.INTERACT_ACROSS_USERS",
    )

private fun cap(
    id: CapabilityId,
    tier: AccessTier,
    state: CapabilityState = CapabilityState.AVAILABLE,
    reason: String? = null,
    fix: FixAction? = null,
) = Capability(id, state, tier, reason, fix, PROBED_MS, "f")

/** As the API 37 emulator probes with a fork running as shell. */
private val MATRIX =
    listOf(
        cap(CapabilityId.BATTERY_CHANGED, AccessTier.T0_UNPRIVILEGED),
        cap(CapabilityId.CURRENT_NOW, AccessTier.T0_UNPRIVILEGED),
        cap(CapabilityId.CHARGE_COUNTER, AccessTier.T0_UNPRIVILEGED),
        cap(CapabilityId.HEALTH_PROPERTIES, AccessTier.T0_UNPRIVILEGED, CapabilityState.DEGRADED, "not supported", FixAction.NONE),
        cap(CapabilityId.BATTERYSTATS_USAGE, AccessTier.T1_DURABLE_GRANTS),
        cap(CapabilityId.BATTERYSTATS_FLAGS, AccessTier.T1_DURABLE_GRANTS),
        cap(CapabilityId.BATTERYSTATS_PROTO, AccessTier.T1_DURABLE_GRANTS),
        cap(CapabilityId.BATTERYSTATS_CHECKIN, AccessTier.T1_DURABLE_GRANTS),
        cap(CapabilityId.BATTERYSTATS_HISTORY, AccessTier.T1_DURABLE_GRANTS),
        cap(CapabilityId.USAGE_STATS, AccessTier.T0_UNPRIVILEGED),
        cap(CapabilityId.STANDBY_BUCKETS, AccessTier.T0_UNPRIVILEGED, CapabilityState.DEGRADED, "own uid only", FixAction.RUN_BOOTSTRAP),
        cap(CapabilityId.POWER_MONITORS, AccessTier.T0_UNPRIVILEGED, CapabilityState.UNAVAILABLE, "none exposed", FixAction.NONE),
        cap(CapabilityId.UID_HEALTH_STATS, AccessTier.T1_DURABLE_GRANTS),
        cap(
            CapabilityId.PROMOTED_NOTIFICATIONS,
            AccessTier.T0_UNPRIVILEGED,
            CapabilityState.UNAVAILABLE,
            "promotion not allowed",
            FixAction.GRANT_NOTIFICATIONS,
        ),
        cap(CapabilityId.ALARM_DUMP, AccessTier.T2_PRIVILEGED_WORKER),
        cap(CapabilityId.DEVICEIDLE_DUMP, AccessTier.T2_PRIVILEGED_WORKER),
        cap(CapabilityId.POWER_DUMP, AccessTier.T2_PRIVILEGED_WORKER),
        cap(CapabilityId.JOBSCHEDULER_DUMP, AccessTier.T2_PRIVILEGED_WORKER),
        cap(CapabilityId.THERMAL_DUMP, AccessTier.T2_PRIVILEGED_WORKER),
        cap(CapabilityId.BATTERY_DUMP, AccessTier.T2_PRIVILEGED_WORKER),
        cap(
            CapabilityId.PROTECTION_SETTINGS,
            AccessTier.T2_PRIVILEGED_WORKER,
            CapabilityState.UNAVAILABLE,
            "no battery-related keys",
            FixAction.NONE,
        ),
        cap(CapabilityId.POWER_SUPPLY_SYSFS, AccessTier.T2_PRIVILEGED_WORKER, CapabilityState.DEGRADED, "some files unreadable"),
        cap(
            CapabilityId.KERNEL_WAKELOCKS,
            AccessTier.T2_PRIVILEGED_WORKER,
            CapabilityState.UNAVAILABLE,
            "no source readable",
            FixAction.NEEDS_ROOT,
        ),
        cap(CapabilityId.WORKER_BIND, AccessTier.T2_PRIVILEGED_WORKER),
        cap(CapabilityId.WAKEUP_SOURCES, AccessTier.T3_ROOT, CapabilityState.UNAVAILABLE, "selinux", FixAction.NEEDS_ROOT),
        cap(CapabilityId.SUSPEND_STATS, AccessTier.T3_ROOT, CapabilityState.UNAVAILABLE, "selinux", FixAction.NEEDS_ROOT),
        cap(CapabilityId.WAKEUP_REASONS, AccessTier.T3_ROOT, CapabilityState.UNAVAILABLE, "selinux", FixAction.NEEDS_ROOT),
        cap(CapabilityId.KERNEL_LOG, AccessTier.T3_ROOT, CapabilityState.UNAVAILABLE, "selinux", FixAction.NEEDS_ROOT),
    )

private val BASIC =
    listOf(
        cap(CapabilityId.BATTERY_CHANGED, AccessTier.T0_UNPRIVILEGED),
        cap(CapabilityId.CURRENT_NOW, AccessTier.T0_UNPRIVILEGED),
        cap(
            CapabilityId.BATTERYSTATS_USAGE,
            AccessTier.T1_DURABLE_GRANTS,
            CapabilityState.UNAVAILABLE,
            "not granted",
            FixAction.CONNECT_SHIZUKU,
        ),
        cap(CapabilityId.ALARM_DUMP, AccessTier.T2_PRIVILEGED_WORKER, CapabilityState.UNAVAILABLE, "not bound", FixAction.CONNECT_SHIZUKU),
    )

private val NoActions = PrivilegesActions({}, {}, {}, {}, {})
