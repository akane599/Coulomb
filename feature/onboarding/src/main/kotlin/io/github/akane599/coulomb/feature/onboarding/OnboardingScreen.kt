// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.onboarding

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.PredictiveBackHandler
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
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
import io.github.akane599.coulomb.core.ui.privilege.SystemScreen
import io.github.akane599.coulomb.core.ui.privilege.open

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
        val retryProbe: () -> Unit,
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
    // Predictive back between steps (SPEC 3, DESIGN.md): the step fades with the gesture and the
    // previous one crossfades in on commit, as the shell's pop does. At the first step back leaves.
    var backProgress by remember { mutableFloatStateOf(0f) }
    PredictiveBackHandler(enabled = state.step != OnboardingStep.WELCOME) { events ->
        try {
            events.collect { backProgress = it.progress }
            viewModel.back()
        } finally {
            backProgress = 0f
        }
    }
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
                retryProbe = viewModel::retryProbe,
            )
        }
    OnboardingContent(state, device, actions, modifier, backProgress)
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
    backProgress: Float = 0f,
) {
    val colors = CoulombTheme.colors
    val reduced = CoulombTheme.reducedMotion
    Column(modifier.fillMaxSize().background(colors.bg)) {
        CoulombTopAppBar(
            title = stringResource(R.string.onboarding_set_up),
            navigationIcon = if (state.step == OnboardingStep.WELCOME) null else ({ CoulombBackButton(onClick = actions.back) }),
        )
        val preview =
            Modifier.graphicsLayer {
                alpha = 1f - backProgress * BACK_FADE
                if (!reduced) scaleX = 1f - backProgress * BACK_SCALE
                if (!reduced) scaleY = scaleX
            }
        CoulombCrossfade(state.step, Modifier.weight(1f).fillMaxWidth().then(preview)) { step ->
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
            }
        }
        // Outside the crossfade: the footer stays while the step changes.
        Footer(caption = footerCaption(state.step)) { FooterButtons(state.step, state, actions) }
    }
}

/** How far the step fades and shrinks at the end of a back gesture (DESIGN.md: a quiet preview). */
private const val BACK_FADE = 0.4f
private const val BACK_SCALE = 0.04f

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
                .padding(horizontal = currentGutter(), vertical = CoulombSpacing.Space12)
                // The body's column, so the buttons end where the step does on wide windows.
                .wrapContentWidth(Alignment.Start)
                .widthIn(max = MaxContentWidth)
                .fillMaxWidth(),
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

private val MaxContentWidth = 720.dp
private const val SAMSUNG = "samsung"
