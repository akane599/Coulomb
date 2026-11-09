// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.onboarding

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.akane599.coulomb.core.designsystem.component.CoulombBackButton
import io.github.akane599.coulomb.core.designsystem.component.CoulombCrossfade
import io.github.akane599.coulomb.core.designsystem.component.CoulombStepper
import io.github.akane599.coulomb.core.designsystem.component.CoulombTopAppBar
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.designsystem.theme.currentGutter

/** The system screens onboarding opens. */
internal enum class SystemScreen { NOTIFICATION_SETTINGS, USAGE_ACCESS, BATTERY_SETTINGS, SHIZUKU_APP, FORK_PAGE, SUI_PAGE }

/** What onboarding reads from the phone rather than from Coulomb's state. */
@Immutable
internal data class OnboardingDevice(
    val notificationsAllowed: Boolean = false,
    /** The system dialog said no once, so the button opens the app's notification settings. */
    val notificationsDenied: Boolean = false,
    /** One UI puts apps to sleep; only there does the result step ask to keep the monitor running (SPEC 14.2). */
    val oneUi: Boolean = false,
    val sdkInt: Int = Build.VERSION.SDK_INT,
    /** A Shizuku-compatible manager app can be opened. */
    val managerApp: Boolean = false,
)

@Immutable
internal class OnboardingActions
    @Suppress("LongParameterList") // One callback per thing the user can do in onboarding.
    constructor(
        val choose: (OnboardingPath) -> Unit,
        val next: () -> Unit,
        val back: () -> Unit,
        val requestNotifications: () -> Unit,
        val allowShizuku: () -> Unit,
        val open: (SystemScreen) -> Unit,
        val finish: () -> Unit,
    )

/**
 * Onboarding in place of the shell until "Show my battery" (SPEC 14.1, ADR-0047). Grants and the
 * notification permission are read again on each resume, since the user may change them in
 * Settings or over ADB and come back.
 */
@Composable
public fun OnboardingScreen(
    modifier: Modifier = Modifier,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var notificationsAllowed by remember { mutableStateOf(context.notificationsAllowed()) }
    var notificationsDenied by rememberSaveable { mutableStateOf(false) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            notificationsAllowed = granted
            if (!granted) notificationsDenied = true
        }
    LifecycleResumeEffect(viewModel) {
        notificationsAllowed = context.notificationsAllowed()
        viewModel.refresh()
        onPauseOrDispose {}
    }
    BackHandler(enabled = state.step != OnboardingStep.WELCOME) { viewModel.back() }
    val managerApp = remember(state.privilege.manager) { viewModel.managerIntent() != null }
    val device =
        OnboardingDevice(
            notificationsAllowed = notificationsAllowed,
            notificationsDenied = notificationsDenied,
            oneUi = remember { Build.MANUFACTURER.equals(SAMSUNG, ignoreCase = true) },
            managerApp = managerApp,
        )
    val actions =
        remember(viewModel, launcher, context) {
            OnboardingActions(
                choose = viewModel::choose,
                next = viewModel::next,
                back = { viewModel.back() },
                requestNotifications = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                allowShizuku = viewModel::allowShizuku,
                open = { screen -> context.open(screen, viewModel::managerIntent) },
                finish = viewModel::finish,
            )
        }
    OnboardingContent(state, device, actions, modifier)
}

/**
 * The step under the top bar and the stepper, and its sticky footer on `bg` with a top hairline
 * (DESIGN.md). The body scrolls; each step starts at its top and crossfades in.
 */
@Composable
internal fun OnboardingContent(
    state: OnboardingUiState,
    device: OnboardingDevice,
    actions: OnboardingActions,
    modifier: Modifier = Modifier,
) {
    val colors = CoulombTheme.colors
    Column(modifier.fillMaxSize().background(colors.bg)) {
        CoulombTopAppBar(
            title = stringResource(R.string.onboarding_set_up),
            navigationIcon = if (state.step == OnboardingStep.WELCOME) null else ({ CoulombBackButton(onClick = actions.back) }),
        )
        CoulombCrossfade(state.step, Modifier.weight(1f).fillMaxWidth()) { step ->
            Column(Modifier.fillMaxSize()) {
                val scroll = key(step) { rememberScrollState() }
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                        .verticalScroll(scroll)
                        .padding(start = currentGutter(), end = currentGutter(), bottom = CoulombSpacing.Space32)
                        .wrapContentWidth(Alignment.Start)
                        .widthIn(max = MaxContentWidth)
                        .fillMaxWidth(),
                ) {
                    CoulombStepper(
                        current = state.stepNumber,
                        count = state.stepCount,
                        label = stringResource(R.string.onboarding_step, state.stepNumber, state.stepCount),
                        modifier = Modifier.padding(top = CoulombSpacing.Space8),
                    )
                    when (step) {
                        OnboardingStep.WELCOME -> WelcomeStep()
                        OnboardingStep.PERMISSIONS -> PermissionsStep(state, device, actions)
                        OnboardingStep.SHIZUKU -> ShizukuStep(state, device, actions)
                        OnboardingStep.RESULT -> ResultStep(state, device, actions)
                    }
                }
                Footer(caption = footerCaption(step)) { FooterButtons(step, state, actions) }
            }
        }
    }
}

private fun footerCaption(step: OnboardingStep): Int? = if (step == OnboardingStep.RESULT) R.string.onboarding_first_results else null

/**
 * The lab's `.onb-actions`: a caption above the buttons when there is one, the buttons at the end,
 * wrapping onto a second line rather than squeezing at large text.
 */
@Composable
private fun Footer(
    caption: Int?,
    buttons: @Composable FlowRowScope.() -> Unit,
) {
    val colors = CoulombTheme.colors
    Column(Modifier.fillMaxWidth().background(colors.bg)) {
        HorizontalDivider(thickness = 1.dp, color = colors.hairline)
        Column(
            Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                .padding(horizontal = currentGutter(), vertical = CoulombSpacing.Space12),
        ) {
            caption?.let {
                Text(
                    stringResource(it),
                    Modifier.fillMaxWidth().padding(bottom = CoulombSpacing.Space8),
                    color = colors.textSecondary,
                    style = CoulombTheme.typography.caption,
                )
            }
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(CoulombSpacing.Space8, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(CoulombSpacing.Space8),
            ) {
                buttons()
            }
        }
    }
}

private fun Context.notificationsAllowed(): Boolean = NotificationManagerCompat.from(this).areNotificationsEnabled()

/**
 * Opens [screen]. Intents verified in AOSP (android-16.0.0_r4 / android-17.0.0_r1):
 * `Settings.ACTION_APP_NOTIFICATION_SETTINGS` with `EXTRA_APP_PACKAGE` (Settings.java L2506, L2566 /
 * L2551, L2611), `Settings.ACTION_USAGE_ACCESS_SETTINGS` (L578 / L586),
 * `Intent.ACTION_POWER_USAGE_SUMMARY` (Intent.java L1735 / L1744) and, where an OEM lacks that,
 * `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` (Settings.java L1636 / L1682). A screen the phone
 * does not have is logged and nothing opens.
 */
private fun Context.open(
    screen: SystemScreen,
    managerIntent: () -> Intent?,
) {
    val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri())
    val intents =
        when (screen) {
            SystemScreen.NOTIFICATION_SETTINGS -> {
                listOf(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName), details)
            }

            SystemScreen.USAGE_ACCESS -> {
                listOf(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }

            SystemScreen.BATTERY_SETTINGS -> {
                listOf(Intent(Intent.ACTION_POWER_USAGE_SUMMARY), details)
            }

            SystemScreen.SHIZUKU_APP -> {
                listOfNotNull(managerIntent())
            }

            SystemScreen.FORK_PAGE -> {
                listOf(Intent(Intent.ACTION_VIEW, FORK_URL.toUri()))
            }

            SystemScreen.SUI_PAGE -> {
                listOf(Intent(Intent.ACTION_VIEW, SUI_URL.toUri()))
            }
        }
    val opened =
        intents.any { intent ->
            try {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "no activity for ${intent.action}", e)
                false
            }
        }
    if (!opened) Log.w(TAG, "could not open $screen")
}

private val MaxContentWidth = 720.dp
private const val SAMSUNG = "samsung"
private const val TAG = "Onboarding"

/** The fork the owner chose to name first (ADR-0028, ADR-0047 §4), and Sui (SPEC 3). */
private const val FORK_URL = "https://github.com/thedjchi/Shizuku"
private const val SUI_URL = "https://github.com/RikkaApps/Sui"
