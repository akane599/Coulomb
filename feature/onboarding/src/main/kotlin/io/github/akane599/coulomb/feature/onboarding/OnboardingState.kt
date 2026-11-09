// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.onboarding

import androidx.compose.runtime.Immutable
import io.github.akane599.coulomb.core.model.Capability
import io.github.akane599.coulomb.core.model.ManagerPermission
import io.github.akane599.coulomb.core.model.ManagerStatus
import io.github.akane599.coulomb.core.model.PrivilegeIssue
import io.github.akane599.coulomb.core.model.PrivilegeState

/** The two ways through onboarding (ADR-0047 §2). */
internal enum class OnboardingPath { SHIZUKU, BASIC }

/** The SPEC 14.1 screens. Basic mode skips [SHIZUKU]. */
internal enum class OnboardingStep { WELCOME, PERMISSIONS, SHIZUKU, RESULT }

/** What step 3 shows, from the manager's state (SPEC 14.1 step 3, 4.5). */
internal enum class ShizukuStage {
    CHECKING,
    NOT_INSTALLED,
    STOPPED,

    /** Running; Coulomb has not been allowed yet. */
    ASK,

    /** The manager's dialog is open. */
    WAITING,

    /** A hang, a denial or a server too old: the SPEC 4.5 copy and the ADB commands. */
    PROBLEM,

    /** Allowed; the bootstrap is granting the durable permissions. */
    GRANTING,
    CONNECTED,
}

/**
 * Step 3's stage. A granted permission wins over a too-old server, because the bootstrap's binder
 * route still grants (SPEC 4.3); a hang shows as soon as the 5 s timeout says so (SPEC 4.5).
 */
internal fun shizukuStage(
    state: PrivilegeState,
    bootstrapDone: Boolean,
): ShizukuStage =
    when {
        state.manager == ManagerStatus.UNKNOWN -> ShizukuStage.CHECKING
        state.manager == ManagerStatus.NOT_INSTALLED -> ShizukuStage.NOT_INSTALLED
        state.manager == ManagerStatus.NOT_RUNNING -> ShizukuStage.STOPPED
        state.permission == ManagerPermission.GRANTED -> if (bootstrapDone) ShizukuStage.CONNECTED else ShizukuStage.GRANTING
        state.problem() != null -> ShizukuStage.PROBLEM
        state.permission == ManagerPermission.PENDING -> ShizukuStage.WAITING
        state.permission == ManagerPermission.UNKNOWN -> ShizukuStage.CHECKING
        else -> ShizukuStage.ASK
    }

/** The SPEC 4.5 issue step 3 explains, if any. */
internal fun PrivilegeState.problem(): PrivilegeIssue? =
    when {
        issue == PrivilegeIssue.AUTHORIZATION_HANG || issue == PrivilegeIssue.SERVER_TOO_OLD -> issue
        issue == PrivilegeIssue.PERMISSION_DENIED || permission == ManagerPermission.DENIED -> PrivilegeIssue.PERMISSION_DENIED
        else -> null
    }

/** Where the result step's capability summary stands. */
internal sealed interface ResultState {
    data object Checking : ResultState

    data object Failed : ResultState

    data class Ready(
        val capabilities: List<Capability>,
    ) : ResultState
}

@Immutable
internal data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.WELCOME,
    val path: OnboardingPath = OnboardingPath.SHIZUKU,
    val privilege: PrivilegeState = PrivilegeState(),
    val stage: ShizukuStage = ShizukuStage.CHECKING,
    /** `adb shell` lines for what is still missing (all of them before a bootstrap). */
    val adbCommands: List<String> = emptyList(),
    val result: ResultState = ResultState.Checking,
) {
    /** Basic mode has three steps, counted as three (ADR-0047 §2). */
    val stepCount: Int get() = if (path == OnboardingPath.BASIC) BASIC_STEPS else SHIZUKU_STEPS

    val stepNumber: Int
        get() =
            when (step) {
                OnboardingStep.WELCOME -> 1
                OnboardingStep.PERMISSIONS -> 2
                OnboardingStep.SHIZUKU -> 3
                OnboardingStep.RESULT -> stepCount
            }

    private companion object {
        const val BASIC_STEPS = 3
        const val SHIZUKU_STEPS = 4
    }
}

/** The step after [step] on [path]; null after the last. */
internal fun next(
    step: OnboardingStep,
    path: OnboardingPath,
): OnboardingStep? =
    when (step) {
        OnboardingStep.WELCOME -> OnboardingStep.PERMISSIONS
        OnboardingStep.PERMISSIONS -> if (path == OnboardingPath.SHIZUKU) OnboardingStep.SHIZUKU else OnboardingStep.RESULT
        OnboardingStep.SHIZUKU -> OnboardingStep.RESULT
        OnboardingStep.RESULT -> null
    }

/** The step before [step] on [path]; null at the first, where back leaves the app. */
internal fun previous(
    step: OnboardingStep,
    path: OnboardingPath,
): OnboardingStep? =
    when (step) {
        OnboardingStep.WELCOME -> null
        OnboardingStep.PERMISSIONS -> OnboardingStep.WELCOME
        OnboardingStep.SHIZUKU -> OnboardingStep.PERMISSIONS
        OnboardingStep.RESULT -> if (path == OnboardingPath.SHIZUKU) OnboardingStep.SHIZUKU else OnboardingStep.PERMISSIONS
    }
