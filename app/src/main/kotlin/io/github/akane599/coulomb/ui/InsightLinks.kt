// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.akane599.coulomb.core.model.insight.ActionId
import io.github.akane599.coulomb.core.model.insight.Insight
import io.github.akane599.coulomb.feature.actions.AppActionsSheet
import io.github.akane599.coulomb.feature.actions.appActionsViewModel
import io.github.akane599.coulomb.navigation.AppNavigator
import io.github.akane599.coulomb.navigation.Route

/**
 * Where the button of an insight's card leads (SPEC 9.2 "Action offered"). An action for an app
 * opens that app's actions as a sheet over [content], in place (DESIGN.md, Insight cards); the
 * others open a screen of Coulomb or a page of the system's settings.
 */
@Composable
fun InsightLinks(
    navigator: AppNavigator,
    content: @Composable (onInsightAction: (ActionId, Insight) -> Unit) -> Unit,
) {
    val context = LocalContext.current
    // The app and the insight whose actions are open.
    var asked by rememberSaveable { mutableStateOf<Pair<Int, Long>?>(null) }
    content { action, insight ->
        val uid = insight.uid
        when (action) {
            ActionId.RESTRICT_BACKGROUND, ActionId.STANDBY_BUCKET -> if (uid != null) asked = uid to insight.id

            ActionId.OPEN_LOCATION_PERMISSION -> if (uid != null) context.openAppInfo(uid)

            // The public page that holds location and, one step in, Wi-Fi and Bluetooth scanning.
            ActionId.OPEN_SCANNING_SETTINGS -> context.open(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))

            else -> navigator.follow(action, insight)
        }
    }
    asked?.let { (uid, insightId) ->
        AppActionsSheet(
            appActionsViewModel(uid, insightId),
            onDismiss = { asked = null },
            onOpenActionCenter = { navigator.open(Route.ActionCenter) },
        )
    }
}

/**
 * The actions that open a screen of Coulomb. A link shows a destination's first screen, never one
 * left open there: `show`, as Now's own links do, not the bar's `select` (audit BUG-004). Internal
 * for its test.
 */
internal fun AppNavigator.follow(
    action: ActionId,
    insight: Insight,
) {
    when (action) {
        ActionId.OPEN_APP_DETAIL -> {
            insight.uid?.let {
                // App detail lives below Drain's list, whose window it reads.
                show(Route.Drain)
                openDetail(Route.AppDetail(it))
            }
        }

        ActionId.OPEN_SESSION -> {
            insight.evidence.sessionId?.let {
                show(Route.NightReport)
                openDetail(Route.Night(it))
            }
        }

        ActionId.OPEN_NIGHT_REPORT -> {
            show(Route.NightReport)
        }

        ActionId.OPEN_HEALTH -> {
            show(Route.Health)
        }

        ActionId.OPEN_DOZE -> {
            open(Route.ActionCenter)
        }

        // Coulomb's own use and the monitoring mode are in Settings (SPEC 6.9, 9 R20).
        ActionId.OPEN_SELF_DIAGNOSTIC -> {
            open(Route.Settings)
        }

        // Handled where the card is, or without a place to lead to yet (InsightActions.label).
        else -> {}
    }
}

/** The system's page for the app of [uid], where its permissions are. */
private fun Context.openAppInfo(uid: Int) {
    val packageName = packageManager.getPackagesForUid(uid)?.firstOrNull() ?: return
    open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
}

private fun Context.open(intent: Intent) {
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // A build without the page: nothing to open, and nothing lost.
    }
}
