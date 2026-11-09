// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigation3.ui.NavDisplay
import io.github.akane599.coulomb.R
import io.github.akane599.coulomb.core.designsystem.component.CoulombBackButton
import io.github.akane599.coulomb.core.designsystem.component.CoulombCrossfade
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
import io.github.akane599.coulomb.core.designsystem.theme.ProvideSharedTransition
import io.github.akane599.coulomb.core.designsystem.theme.ProvideSharedVisibility
import io.github.akane599.coulomb.core.designsystem.theme.WindowWidth
import io.github.akane599.coulomb.core.designsystem.theme.currentGutter
import io.github.akane599.coulomb.core.designsystem.theme.currentWindowWidth
import io.github.akane599.coulomb.feature.appdetail.AppDetailScreen
import io.github.akane599.coulomb.feature.appdetail.appDetailTitle
import io.github.akane599.coulomb.feature.appdetail.appDetailViewModel
import io.github.akane599.coulomb.feature.charging.ChargingScreen
import io.github.akane599.coulomb.feature.charging.rememberPlugged
import io.github.akane599.coulomb.feature.drain.CompareScreen
import io.github.akane599.coulomb.feature.drain.DrainScreen
import io.github.akane599.coulomb.feature.health.ChargeSessionScreen
import io.github.akane599.coulomb.feature.health.ChargeSessionsScreen
import io.github.akane599.coulomb.feature.health.HealthScreen
import io.github.akane599.coulomb.feature.health.chargeSessionViewModel
import io.github.akane599.coulomb.feature.now.NowScreen
import io.github.akane599.coulomb.navigation.AppNavigator
import io.github.akane599.coulomb.navigation.Destination
import io.github.akane599.coulomb.navigation.ListDetailStrategy
import io.github.akane599.coulomb.navigation.PaneMin
import io.github.akane599.coulomb.navigation.Route
import io.github.akane599.coulomb.navigation.rememberAppNavigator
import io.github.akane599.coulomb.feature.charging.R as ChargingR
import io.github.akane599.coulomb.feature.drain.R as DrainR
import io.github.akane599.coulomb.feature.health.R as HealthR

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
            BoxWithConstraints(Modifier.fillMaxSize()) {
                // SPEC 10.10: list and detail side by side on expanded widths, when both panes fit the text size.
                val panes = PaneMin * 2 * LocalDensity.current.fontScale.coerceAtLeast(1f)
                val split = currentWindowWidth() == WindowWidth.Expanded && maxWidth >= panes
                val strategy = remember(split) { ListDetailStrategy<NavKey>(split) }
                // An app's icon moves from its row to App detail (SPEC 11.6); beside the list, both stay put.
                SharedTransitionLayout {
                    ProvideSharedTransition(this.takeIf { !split }) {
                        NavDisplay(
                            entries = entries.getValue(navigator.current),
                            modifier = Modifier.fillMaxSize().consumeWindowInsets(navigationInsets(rail)),
                            sceneStrategies = listOf(strategy),
                            sharedTransitionScope = this,
                            onBack = navigator::back,
                            transitionSpec = { fade() },
                            popTransitionSpec = { fade() },
                            predictivePopTransitionSpec = { fade() },
                        )
                    }
                }
            }
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
@Suppress("LongParameterList") // One slot per built screen.
class AppScreens(
    val now: @Composable (AppNavigator) -> Unit,
    /** Whether the phone is plugged in, or null before it is known: Charging then takes Now's place. */
    val plugged: @Composable () -> Boolean?,
    val charging: @Composable (AppNavigator) -> Unit,
    val drain: @Composable (AppNavigator) -> Unit,
    val compare: @Composable (AppNavigator) -> Unit,
    /** App detail for a UID; its title slot reads the same entry's model. */
    val appDetail: @Composable (AppNavigator, uid: Int) -> Unit,
    val appDetailTitle: @Composable (uid: Int) -> String,
    val health: @Composable (AppNavigator) -> Unit,
    val chargeSessions: @Composable (AppNavigator) -> Unit,
    val chargeSession: @Composable (AppNavigator, id: Long) -> Unit,
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
                plugged = { rememberPlugged() },
                charging = { ChargingScreen() },
                drain = { navigator ->
                    DrainScreen(
                        onOpenApp = { navigator.openDetail(Route.AppDetail(it)) },
                        onOpenPrivileges = { navigator.open(Route.Privileges) },
                        onOpenCompare = { navigator.open(Route.DrainCompare) },
                        // Shown only while the detail is beside the list; under it, Drain is not on screen.
                        selectedApp = navigator.openApp(Destination.Drain),
                    )
                },
                compare = { navigator -> CompareScreen(onOpenApp = { navigator.openDetail(Route.AppDetail(it)) }) },
                // One model per entry: both calls get the entry's instance for the UID.
                appDetail = { _, uid -> AppDetailScreen(appDetailViewModel(uid)) },
                appDetailTitle = { uid -> appDetailTitle(appDetailViewModel(uid)) },
                health = { navigator ->
                    HealthScreen(
                        onOpenSession = { navigator.openDetail(Route.ChargeSession(it)) },
                        onSeeAll = { navigator.openList(Route.ChargeSessions) },
                        // Shown only while the session is beside the list.
                        selectedSession = navigator.openSession(Destination.Health),
                    )
                },
                chargeSessions = { navigator ->
                    ChargeSessionsScreen(
                        onOpenSession = { navigator.openDetail(Route.ChargeSession(it)) },
                        selected = navigator.openSession(Destination.Health),
                    )
                },
                chargeSession = { _, id -> ChargeSessionScreen(chargeSessionViewModel(id)) },
            )
    }
}

private fun appEntries(
    navigator: AppNavigator,
    screens: AppScreens,
): (NavKey) -> NavEntry<NavKey> =
    entryProvider {
        entry<Route.Now> {
            // ADR-0046 §1: Charging in place of Now while plugged in, under its own name.
            val plugged = screens.plugged()
            val title = if (plugged == true) stringResource(ChargingR.string.charging_title) else null
            DestinationScreen(Destination.Now, navigator::open, title) {
                CoulombCrossfade(plugged) { shown ->
                    when (shown) {
                        true -> screens.charging(navigator)
                        false -> screens.now(navigator)
                        null -> Unit
                    }
                }
            }
        }
        entry<Route.Drain>(metadata = ListDetailStrategy.list) {
            DestinationScreen(Destination.Drain, navigator::open) { screens.drain(navigator) }
        }
        entry<Route.DrainCompare>(metadata = ListDetailStrategy.list) {
            InnerScreen(stringResource(DrainR.string.drain_compare_title), navigator::back) { screens.compare(navigator) }
        }
        entry<Route.AppDetail>(metadata = ListDetailStrategy.detail) { key ->
            InnerScreen(screens.appDetailTitle(key.uid), navigator::back, pane = true) { screens.appDetail(navigator, key.uid) }
        }
        entry<Route.Timeline> { DestinationScreen(Destination.Timeline, navigator::open) { NotBuilt() } }
        entry<Route.Health>(metadata = ListDetailStrategy.list) {
            DestinationScreen(Destination.Health, navigator::open) { screens.health(navigator) }
        }
        entry<Route.ChargeSessions>(metadata = ListDetailStrategy.list) {
            InnerScreen(stringResource(HealthR.string.health_all_sessions_title), navigator::back) { screens.chargeSessions(navigator) }
        }
        entry<Route.ChargeSession>(metadata = ListDetailStrategy.detail) { key ->
            InnerScreen(stringResource(HealthR.string.health_session_title), navigator::back, pane = true) {
                screens.chargeSession(navigator, key.id)
            }
        }
        entry<Route.ActionCenter> { InnerScreen(stringResource(R.string.screen_action_center), navigator::back) { NotBuilt() } }
        entry<Route.Privileges> { InnerScreen(stringResource(R.string.screen_privileges), navigator::back) { NotBuilt() } }
        entry<Route.Settings> { InnerScreen(stringResource(R.string.screen_settings), navigator::back) { NotBuilt() } }
    }

/** A destination's first screen: its name, then Action Center, Privileges and Settings (DESIGN.md, Navigation). */
@Composable
private fun DestinationScreen(
    destination: Destination,
    onOpen: (Route) -> Unit,
    title: String? = null,
    content: @Composable () -> Unit,
) {
    ShellScreen(
        title = title ?: stringResource(destination.label),
        actions = {
            CoulombIconButton(CoulombIcons.FactCheck, stringResource(R.string.screen_action_center), { onOpen(Route.ActionCenter) })
            CoulombIconButton(CoulombIcons.ShieldPerson, stringResource(R.string.screen_privileges), { onOpen(Route.Privileges) })
            CoulombIconButton(CoulombIcons.Settings, stringResource(R.string.screen_settings), { onOpen(Route.Settings) })
        },
        content = content,
    )
}

/**
 * A screen below a destination: back, then its name. A [pane] screen can change beside a list
 * (SPEC 10.10), so TalkBack announces its title when it does.
 */
@Composable
private fun InnerScreen(
    title: String,
    onBack: () -> Unit,
    pane: Boolean = false,
    content: @Composable () -> Unit,
) {
    ShellScreen(
        title = title,
        navigationIcon = { CoulombBackButton(onClick = onBack) },
        modifier = if (pane && title.isNotEmpty()) Modifier.semantics { paneTitle = title } else Modifier,
        content = content,
    )
}

@Composable
private fun ShellScreen(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxSize().background(CoulombTheme.colors.bg)) {
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
            // Each screen's own enter and exit, for an element shared with the next screen.
            ProvideSharedVisibility(LocalNavAnimatedContentScope.current, content)
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
