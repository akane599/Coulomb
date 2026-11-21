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

    @Test
    fun anotherDetailReplacesTheOneOnTop() {
        navigator.select(Destination.Drain)
        navigator.openDetail(Route.AppDetail(10_123))

        navigator.openDetail(Route.AppDetail(10_456))

        assertThat(navigator.backStack).containsExactly(Route.Drain, Route.AppDetail(10_456)).inOrder()
        navigator.back()
        assertThat(navigator.backStack).containsExactly(Route.Drain)
    }

    @Test
    fun anotherListClosesTheDetailBesideTheFirst() {
        navigator.select(Destination.Health)
        navigator.openDetail(Route.ChargeSession(7))

        navigator.openList(Route.ChargeSessions)

        assertThat(navigator.backStack).containsExactly(Route.Health, Route.ChargeSessions).inOrder()
        navigator.back()
        assertThat(navigator.backStack).containsExactly(Route.Health)
    }

    @Test
    fun anotherNightReplacesTheOneBesideTheList() {
        navigator.select(Destination.Timeline)
        navigator.openList(Route.NightReport)
        navigator.openDetail(Route.Night(41))
        assertThat(navigator.openNight(Destination.Timeline)).isEqualTo(41)

        navigator.openDetail(Route.Night(40))

        assertThat(navigator.backStack).containsExactly(Route.Timeline, Route.NightReport, Route.Night(40)).inOrder()
        navigator.back()
        assertThat(navigator.openNight(Destination.Timeline)).isNull()
        assertThat(navigator.backStack).containsExactly(Route.Timeline, Route.NightReport).inOrder()
    }

    @Test
    fun theOpenAppIsTheDetailOnTopOfItsStack() {
        navigator.select(Destination.Drain)
        assertThat(navigator.openApp(Destination.Drain)).isNull()

        navigator.openDetail(Route.AppDetail(10_123))
        assertThat(navigator.openApp(Destination.Drain)).isEqualTo(10_123)
        assertThat(navigator.openApp(Destination.Now)).isNull()

        navigator.back()
        assertThat(navigator.openApp(Destination.Drain)).isNull()
    }

    @Test
    fun aDetailOpensAboveCompare() {
        navigator.select(Destination.Drain)
        navigator.open(Route.DrainCompare)

        navigator.openDetail(Route.AppDetail(10_123))

        assertThat(navigator.backStack).containsExactly(Route.Drain, Route.DrainCompare, Route.AppDetail(10_123)).inOrder()
    }

    @Test
    fun aTapOutsideTheAppShowsItsScreen() {
        // A widget's destination: selected, at its first screen.
        navigator.select(Destination.Drain)
        navigator.open(Route.DrainCompare)
        navigator.show(Route.Health)
        assertThat(navigator.current).isEqualTo(Destination.Health)
        navigator.show(Route.Drain)
        assertThat(navigator.backStack).containsExactly(Route.Drain)

        // The Night Report lives under Timeline, and a second tap does not stack it.
        navigator.show(Route.NightReport)
        navigator.show(Route.NightReport)
        assertThat(navigator.current).isEqualTo(Destination.Timeline)
        assertThat(navigator.backStack).containsExactly(Route.Timeline, Route.NightReport).inOrder()

        // Privileges opens over wherever the user is, once.
        navigator.show(Route.Privileges)
        navigator.show(Route.Privileges)
        assertThat(navigator.backStack).containsExactly(Route.Timeline, Route.NightReport, Route.Privileges).inOrder()
    }
}
