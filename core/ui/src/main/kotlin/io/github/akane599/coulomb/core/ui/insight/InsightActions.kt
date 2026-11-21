// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.ui.insight

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.akane599.coulomb.core.model.insight.ActionId
import io.github.akane599.coulomb.core.model.insight.Insight
import io.github.akane599.coulomb.core.ui.R

/** The button of an insight's card (SPEC 9.2 "Action offered"; DESIGN.md, Insight cards). */
public object InsightActions {
    /**
     * The first of [offered] that [insight] can carry out and that is not in [except], or null:
     * an action for an app needs the app, and a night needs its session. An action with no place
     * to lead to yet (battery protection, the self-diagnostic, Lite mode) has no button.
     */
    public fun pick(
        offered: List<ActionId>,
        insight: Insight,
        except: Set<ActionId> = emptySet(),
    ): ActionId? = offered.firstOrNull { it !in except && label(it) != null && possible(it, insight) }

    private fun possible(
        action: ActionId,
        insight: Insight,
    ): Boolean =
        when (action) {
            ActionId.RESTRICT_BACKGROUND,
            ActionId.STANDBY_BUCKET,
            ActionId.OPEN_APP_DETAIL,
            ActionId.OPEN_LOCATION_PERMISSION,
            -> insight.uid != null

            ActionId.OPEN_SESSION -> insight.evidence.sessionId != null

            else -> true
        }

    /** The button's words, which name where it leads; null for an action without a place. */
    @StringRes
    public fun label(action: ActionId): Int? =
        when (action) {
            ActionId.RESTRICT_BACKGROUND, ActionId.STANDBY_BUCKET -> R.string.insight_action_app_actions
            ActionId.OPEN_APP_DETAIL -> R.string.insight_action_app
            ActionId.OPEN_SESSION -> R.string.insight_action_night
            ActionId.OPEN_NIGHT_REPORT -> R.string.insight_action_nights
            ActionId.OPEN_LOCATION_PERMISSION -> R.string.insight_action_app_info
            ActionId.OPEN_SCANNING_SETTINGS -> R.string.insight_action_location
            ActionId.OPEN_HEALTH -> R.string.insight_action_health
            ActionId.OPEN_DOZE -> R.string.insight_action_doze
            ActionId.OPEN_BATTERY_PROTECTION, ActionId.OPEN_SELF_DIAGNOSTIC, ActionId.SWITCH_TO_LITE -> null
        }
}

/** The card's button for [insight], which runs [onAction] with the action it stands for; null when it has none. */
@Composable
public fun insightAction(
    offered: List<ActionId>,
    insight: Insight,
    except: Set<ActionId> = emptySet(),
    onAction: (ActionId, Insight) -> Unit,
): InsightAction? {
    val action = InsightActions.pick(offered, insight, except) ?: return null
    val label = InsightActions.label(action) ?: return null
    return InsightAction(stringResource(label)) { onAction(action, insight) }
}
