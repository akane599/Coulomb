// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.navigation

import androidx.compose.runtime.mutableStateOf
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppNavigatorTest {
    private val navigator =
        AppNavigator(
            stacks = Destination.entries.associateWith { NavBackStack<NavKey>(it.root) },
            currentState = mutableStateOf(Destination.Now),
        )

    @Test
    fun backFromAnotherDestinationsFirstScreenReturnsToNow() {
        navigator.select(Destination.Drain)
        assertThat(navigator.backReturnsToNow).isTrue()

        navigator.back()

        assertThat(navigator.current).isEqualTo(Destination.Now)
        assertThat(navigator.backReturnsToNow).isFalse()
    }

    @Test
    fun backFromNowsFirstScreenIsLeftToTheSystem() {
        navigator.back()

        assertThat(navigator.current).isEqualTo(Destination.Now)
        assertThat(navigator.backStack).containsExactly(Route.Now)
        assertThat(navigator.backReturnsToNow).isFalse()
    }

    @Test
    fun backPopsInsideTheCurrentStackFirst() {
        navigator.select(Destination.Health)
        navigator.open(Route.Settings)
        assertThat(navigator.backReturnsToNow).isFalse()

        navigator.back()

        assertThat(navigator.current).isEqualTo(Destination.Health)
        assertThat(navigator.backStack).containsExactly(Route.Health)
    }

    @Test
    fun eachDestinationKeepsItsPlace() {
        navigator.select(Destination.Drain)
        navigator.open(Route.Privileges)
        navigator.select(Destination.Now)
        navigator.open(Route.Settings)

        navigator.select(Destination.Drain)

        assertThat(navigator.backStack).containsExactly(Route.Drain, Route.Privileges).inOrder()
        assertThat(navigator.stacks.getValue(Destination.Now)).containsExactly(Route.Now, Route.Settings).inOrder()
    }

    @Test
    fun choosingTheCurrentDestinationAgainReturnsToItsFirstScreen() {
        navigator.open(Route.ActionCenter)
        navigator.open(Route.Settings)

        navigator.select(Destination.Now)

        assertThat(navigator.backStack).containsExactly(Route.Now)
    }
}
