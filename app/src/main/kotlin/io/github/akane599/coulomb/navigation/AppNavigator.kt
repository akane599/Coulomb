// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack

/**
 * One back stack per destination, so each keeps its place while another is shown. Back pops the
 * current stack; from a destination's first screen it returns to Now, and from Now's first screen
 * it leaves the app. Choosing the current destination again returns to its first screen.
 */
@Stable
class AppNavigator internal constructor(
    val stacks: Map<Destination, NavBackStack<NavKey>>,
    currentState: MutableState<Destination>,
) {
    /** The destination on screen. */
    var current: Destination by currentState
        private set

    /** The current destination's stack, first screen first. */
    val backStack: NavBackStack<NavKey> get() = stacks.getValue(current)

    /** Whether back stays in the app by switching to Now. */
    val backReturnsToNow: Boolean get() = current != Destination.Now && backStack.size == 1

    fun select(destination: Destination) {
        if (destination == current) {
            popToFirst(backStack)
        } else {
            current = destination
        }
    }

    /** Opens [route] on top of the current destination's stack. */
    fun open(route: Route) {
        backStack.add(route)
    }

    /**
     * Opens a detail [route], replacing a detail already on top: beside the list on expanded
     * widths (SPEC 10.10), choosing another row swaps the detail instead of stacking it.
     */
    fun openDetail(route: Route) {
        if (backStack.lastOrNull() is Route.AppDetail) backStack.removeAt(backStack.lastIndex)
        backStack.add(route)
    }

    /** The UID of the App detail on top of [destination]'s stack, if one is. */
    fun openApp(destination: Destination): Int? = (stacks.getValue(destination).lastOrNull() as? Route.AppDetail)?.uid

    fun back() {
        when {
            backStack.size > 1 -> backStack.removeAt(backStack.lastIndex)
            current != Destination.Now -> current = Destination.Now
        }
    }

    private fun popToFirst(stack: NavBackStack<NavKey>) {
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
    }
}

@Composable
fun rememberAppNavigator(): AppNavigator {
    // One call per destination, in a fixed order, so each stack keeps its saved-state slot.
    val now = rememberNavBackStack(Route.Now)
    val drain = rememberNavBackStack(Route.Drain)
    val timeline = rememberNavBackStack(Route.Timeline)
    val health = rememberNavBackStack(Route.Health)
    val current = rememberSaveable { mutableStateOf(Destination.Now) }
    return remember(now, drain, timeline, health, current) {
        AppNavigator(
            stacks =
                mapOf(
                    Destination.Now to now,
                    Destination.Drain to drain,
                    Destination.Timeline to timeline,
                    Destination.Health to health,
                ),
            currentState = current,
        )
    }
}
