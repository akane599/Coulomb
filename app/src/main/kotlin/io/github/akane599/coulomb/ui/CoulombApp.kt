// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldLayout
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import io.github.akane599.coulomb.R
import io.github.akane599.coulomb.core.designsystem.component.CoulombBackButton
import io.github.akane599.coulomb.core.designsystem.component.CoulombIconButton
import io.github.akane599.coulomb.core.designsystem.component.CoulombNavigationBar
import io.github.akane599.coulomb.core.designsystem.component.CoulombNavigationBarItem
import io.github.akane599.coulomb.core.designsystem.component.CoulombNavigationRail
import io.github.akane599.coulomb.core.designsystem.component.CoulombNavigationRailItem
import io.github.akane599.coulomb.core.designsystem.component.CoulombTopAppBar
import io.github.akane599.coulomb.core.designsystem.icon.CoulombIcons
import io.github.akane599.coulomb.core.designsystem.theme.CoulombMotion
import io.github.akane599.coulomb.core.designsystem.theme.CoulombSpacing
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.designsystem.theme.WindowWidth
import io.github.akane599.coulomb.core.designsystem.theme.currentGutter
import io.github.akane599.coulomb.core.designsystem.theme.currentWindowWidth
import io.github.akane599.coulomb.feature.drain.CompareScreen
import io.github.akane599.coulomb.feature.drain.DrainScreen
import io.github.akane599.coulomb.feature.now.NowScreen
import io.github.akane599.coulomb.navigation.AppNavigator
import io.github.akane599.coulomb.navigation.Destination
import io.github.akane599.coulomb.navigation.Route
import io.github.akane599.coulomb.navigation.rememberAppNavigator
import io.github.akane599.coulomb.feature.drain.R as DrainR

/**
 * The shell of SPEC 10.1: the bottom bar on compact widths and the rail from medium up, laid out by
 * Material's `NavigationSuiteScaffoldLayout` around Coulomb's own bar and rail (DESIGN.md's 64 dp
 * pill). Each destination's stack is decorated on its own, so a destination that is not on screen
 * keeps its saved state and ViewModels (`rememberDecoratedNavEntries`).
 */
@Composable
fun CoulombApp(
    modifier: Modifier = Modifier,
    navigator: AppNavigator = rememberAppNavigator(),
    screens: AppScreens = AppScreens.Default,
) {
    val rail = currentWindowWidth() != WindowWidth.Compact
    val provider = remember(navigator, screens) { appEntries(navigator, screens) }
    val entries =
        Destination.entries.associateWith { destination ->
            key(destination) {
                rememberDecoratedNavEntries(
                    backStack = navigator.stacks.getValue(destination),
                    entryDecorators =
                        listOf(
                            rememberSaveableStateHolderNavEntryDecorator(),
                            rememberViewModelStoreNavEntryDecorator(),
                        ),
                    entryProvider = provider,
                )
            }
        }

    Box(modifier.fillMaxSize().background(CoulombTheme.colors.bg)) {
        NavigationSuiteScaffoldLayout(
            navigationSuite = { Navigation(rail, navigator) },
            navigationSuiteType = if (rail) NavigationSuiteType.NavigationRail else NavigationSuiteType.NavigationBar,
        ) {
            // NavDisplay handles back inside a stack; at the first screen of another destination, back goes to Now.
            BackHandler(enabled = navigator.backReturnsToNow, onBack = navigator::back)
            NavDisplay(
                entries = entries.getValue(navigator.current),
                modifier = Modifier.fillMaxSize().consumeWindowInsets(navigationInsets(rail)),
                onBack = navigator::back,
                transitionSpec = { fade() },
                popTransitionSpec = { fade() },
                predictivePopTransitionSpec = { fade() },
            )
        }
    }
}

/** The insets the bar or rail already pads for, so screens do not pad for them again. */
@Composable
private fun navigationInsets(rail: Boolean): WindowInsets =
    if (rail) {
        WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Start)
    } else {
        WindowInsets.systemBars.only(WindowInsetsSides.Bottom)
    }

/** Screens cross-fade; motion explains change only (SPEC 11.6). Back stays seekable. */
private fun fade(): ContentTransform = ContentTransform(fadeIn(CoulombMotion.effects()), fadeOut(CoulombMotion.effects()))

@Composable
private fun Navigation(
    rail: Boolean,
    navigator: AppNavigator,
) {
    if (rail) {
        CoulombNavigationRail {
            Destination.entries.forEach { destination ->
                CoulombNavigationRailItem(
                    selected = destination == navigator.current,
                    onClick = { navigator.select(destination) },
                    icon = destination.icon,
                    selectedIcon = destination.selectedIcon,
                    label = stringResource(destination.label),
                )
            }
        }
    } else {
        CoulombNavigationBar {
            Destination.entries.forEach { destination ->
                CoulombNavigationBarItem(
                    selected = destination == navigator.current,
                    onClick = { navigator.select(destination) },
                    icon = destination.icon,
                    selectedIcon = destination.selectedIcon,
                    label = stringResource(destination.label),
                )
            }
        }
    }
}

/** The built destination screens. Tests swap in stand-ins, so the shell renders without Hilt. */
@Immutable
class AppScreens(
    val now: @Composable (AppNavigator) -> Unit,
    val drain: @Composable (AppNavigator) -> Unit,
    val compare: @Composable (AppNavigator) -> Unit,
) {
    companion object {
        val Default: AppScreens =
            AppScreens(
                now = { navigator ->
                    NowScreen(
                        onSeeAllDrains = { navigator.select(Destination.Drain) },
                        onOpenPrivileges = { navigator.open(Route.Privileges) },
                    )
                },
                // App detail arrives later in P4; until then the rows open nothing.
                drain = { navigator ->
                    DrainScreen(
                        onOpenApp = null,
                        onOpenPrivileges = { navigator.open(Route.Privileges) },
                        onOpenCompare = { navigator.open(Route.DrainCompare) },
                    )
                },
                compare = { CompareScreen(onOpenApp = null) },
            )
    }
}

private fun appEntries(
    navigator: AppNavigator,
    screens: AppScreens,
): (NavKey) -> NavEntry<NavKey> =
    entryProvider {
        entry<Route.Now> { DestinationScreen(Destination.Now, navigator::open) { screens.now(navigator) } }
        entry<Route.Drain> { DestinationScreen(Destination.Drain, navigator::open) { screens.drain(navigator) } }
        entry<Route.DrainCompare> { InnerScreen(DrainR.string.drain_compare_title, navigator::back) { screens.compare(navigator) } }
        entry<Route.Timeline> { DestinationScreen(Destination.Timeline, navigator::open) { NotBuilt() } }
        entry<Route.Health> { DestinationScreen(Destination.Health, navigator::open) { NotBuilt() } }
        entry<Route.ActionCenter> { InnerScreen(R.string.screen_action_center, navigator::back) { NotBuilt() } }
        entry<Route.Privileges> { InnerScreen(R.string.screen_privileges, navigator::back) { NotBuilt() } }
        entry<Route.Settings> { InnerScreen(R.string.screen_settings, navigator::back) { NotBuilt() } }
    }

/** A destination's first screen: its name, then Action Center, Privileges and Settings (DESIGN.md, Navigation). */
@Composable
private fun DestinationScreen(
    destination: Destination,
    onOpen: (Route) -> Unit,
    content: @Composable () -> Unit,
) {
    ShellScreen(
        title = stringResource(destination.label),
        actions = {
            CoulombIconButton(CoulombIcons.FactCheck, stringResource(R.string.screen_action_center), { onOpen(Route.ActionCenter) })
            CoulombIconButton(CoulombIcons.ShieldPerson, stringResource(R.string.screen_privileges), { onOpen(Route.Privileges) })
            CoulombIconButton(CoulombIcons.Settings, stringResource(R.string.screen_settings), { onOpen(Route.Settings) })
        },
        content = content,
    )
}

/** A screen below a destination: back, then its name. */
@Composable
private fun InnerScreen(
    @StringRes title: Int,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    ShellScreen(
        title = stringResource(title),
        navigationIcon = { CoulombBackButton(onClick = onBack) },
        content = content,
    )
}

@Composable
private fun ShellScreen(
    title: String,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(CoulombTheme.colors.bg)) {
        CoulombTopAppBar(title = title, navigationIcon = navigationIcon, actions = actions)
        // The top bar pads the top; the bar or the rail pads its own edge (navigationInsets). The
        // content pads what is left: on the rail, the bottom bar and an end-side bar or cutout.
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)),
        ) {
            content()
        }
    }
}

/** Placeholder for a screen whose phase has not shipped (SPEC 17); replaced screen by screen in P4 and P5. */
@Composable
private fun NotBuilt() {
    Text(
        text = stringResource(R.string.screen_not_built),
        modifier = Modifier.padding(horizontal = currentGutter(), vertical = CoulombSpacing.Space24),
        color = CoulombTheme.colors.textSecondary,
        style = CoulombTheme.typography.body,
    )
}
