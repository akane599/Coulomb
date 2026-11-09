// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import io.github.akane599.coulomb.core.designsystem.component.CoulombCrossfade
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.feature.onboarding.OnboardingScreen
import io.github.akane599.coulomb.feature.onboarding.rememberOnboarded

/**
 * Onboarding until it is done, then the shell (ADR-0047 §1). Until the flag is read the screen
 * stays `bg`, so neither flashes; "Show my battery" crossfades to Now.
 */
@Composable
fun CoulombRoot(modifier: Modifier = Modifier) {
    val onboarded = rememberOnboarded()
    // Test tags reach UI Automator as resource ids, for the Macrobenchmark (SPEC 16.5).
    Box(modifier.fillMaxSize().background(CoulombTheme.colors.bg).semantics { testTagsAsResourceId = true }) {
        if (onboarded != null) {
            CoulombCrossfade(onboarded, Modifier.fillMaxSize()) { done ->
                if (done) CoulombApp() else OnboardingScreen()
            }
        }
    }
}
