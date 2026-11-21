// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.actions

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.akane599.coulomb.core.collect.actions.ActionBlock
import io.github.akane599.coulomb.core.collect.actions.ActionCenter
import io.github.akane599.coulomb.core.collect.actions.ActionPreview
import io.github.akane599.coulomb.core.collect.actions.ActionRecord
import io.github.akane599.coulomb.core.collect.actions.ActionRequest
import io.github.akane599.coulomb.core.datastore.SettingsStore
import io.github.akane599.coulomb.core.insights.InsightStore
import io.github.akane599.coulomb.core.model.ActionOrigin
import io.github.akane599.coulomb.core.model.cmd.StandbyBucket
import io.github.akane599.coulomb.core.privileged.PrivilegedAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

/** One action for the app, and the change of that kind Coulomb made and that is still in force. */
internal data class AppActionItem(
    val preview: ActionPreview,
    val active: ActionRecord?,
)

internal sealed interface AppActionsState {
    data object Loading : AppActionsState

    /** The Action Center is off: the phone was not asked anything. */
    data object Off : AppActionsState

    /** An app of another user or profile; every command names the user Coulomb runs as. */
    data object OtherProfile : AppActionsState

    /** No package is installed for the UID. */
    data object None : AppActionsState

    data class Ready(
        /** Every package of the UID; more than one for apps that share it. */
        val packages: List<String>,
        val selected: String,
        val label: String,
        val items: List<AppActionItem>,
    ) : AppActionsState
}

/**
 * The actions for one app (SPEC 10.4 item 8, SPEC 13.2) with the current state of each. It asks
 * the phone only while it is shown and the Action Center is on.
 */
@HiltViewModel(assistedFactory = AppActionsViewModel.Factory::class)
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LongParameterList") // The app and the insight it is for, then what it reads and writes through.
public class AppActionsViewModel
    @AssistedInject
    constructor(
        @Assisted private val uid: Int,
        /** The insight whose card opened the actions; null from App detail. */
        @Assisted insightId: Long?,
        private val center: ActionCenter,
        insights: InsightStore,
        store: SettingsStore,
        access: PrivilegedAccess,
        @ApplicationContext private val context: Context,
    ) : ViewModel() {
        private val tick = MutableStateFlow(0)
        private val chosen = MutableStateFlow<String?>(null)
        internal val flow =
            ActionFlow(center, viewModelScope, if (insightId == null) ActionOrigin.USER else ActionOrigin.INSIGHT, insightId) {
                // SPEC 9.4: the insight whose action ran is acted on, and leaves the open ones.
                if (insightId != null) insights.acted(insightId, System.currentTimeMillis())
            }

        internal val state: StateFlow<AppActionsState> =
            combine(
                store.settings.map { it.actionCenter }.distinctUntilChanged(),
                center.records,
                access.state.map { it.worker }.distinctUntilChanged(),
                chosen,
                tick,
            ) { on, records, _, chosen, _ -> Triple(on, records, chosen) }
                .mapLatest { (on, records, chosen) ->
                    delay(SETTLE_MS)
                    load(on, records, chosen)
                }.flowOn(Dispatchers.Default)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AppActionsState.Loading)

        internal fun refresh() {
            tick.value++
        }

        /** For a UID several packages share: the one the actions are for. */
        internal fun choose(packageName: String) {
            chosen.value = packageName
        }

        private suspend fun load(
            on: Boolean,
            records: List<ActionRecord>,
            chosen: String?,
        ): AppActionsState {
            val packages = if (uid / PER_USER_RANGE == Process.myUid() / PER_USER_RANGE) packagesOf(uid) else null
            val selected = packages?.let { chosen?.takeIf(it::contains) ?: it.firstOrNull() }
            return when {
                !on -> AppActionsState.Off
                packages == null -> AppActionsState.OtherProfile
                selected == null -> AppActionsState.None
                else -> AppActionsState.Ready(packages, selected, labelOf(selected), items(selected, records))
            }
        }

        private suspend fun items(
            packageName: String,
            records: List<ActionRecord>,
        ): List<AppActionItem> {
            val previews =
                center
                    .board(
                        listOf(
                            ActionRequest.ForceStop(packageName),
                            ActionRequest.RestrictBackground(packageName),
                            ActionRequest.SetStandbyBucket(packageName, StandbyBucket.RARE),
                            ActionRequest.SetStandbyBucket(packageName, StandbyBucket.RESTRICTED),
                            ActionRequest.DozeAllowlist(packageName, listed = true),
                            ActionRequest.DozeAllowlist(packageName, listed = false),
                        ),
                    ).previews
            val add = previews[previews.lastIndex - 1]
            // On the user's list already: the action is to take it off.
            val allowlist = if (add.block == ActionBlock.ALREADY) previews.last() else add
            val active = records.filter { it.active && it.target == packageName }
            return (previews.dropLast(2) + allowlist).map { preview ->
                AppActionItem(preview, active.firstOrNull { it.belongsTo(preview) })
            }
        }

        /**
         * Rare and Restricted are one kind in the log. The change shows on the row that names its
         * value; one Android set short of both (ADR-0060 §11) shows on Rare's, the first.
         */
        private fun ActionRecord.belongsTo(preview: ActionPreview): Boolean {
            val bucket = (preview.request as? ActionRequest.SetStandbyBucket)?.bucket
            return type == preview.request.type &&
                when (bucket) {
                    null -> true
                    StandbyBucket.RARE -> after != StandbyBucket.RESTRICTED.cliName
                    else -> after == bucket.cliName
                }
        }

        private fun packagesOf(uid: Int): List<String> =
            context.packageManager
                .getPackagesForUid(uid)
                ?.sorted()
                .orEmpty()

        private fun labelOf(packageName: String): String {
            val packages = context.packageManager
            return try {
                packages.getApplicationLabel(packages.getApplicationInfo(packageName, 0)).toString()
            } catch (_: PackageManager.NameNotFoundException) {
                packageName
            }
        }

        @AssistedFactory
        public interface Factory {
            public fun create(
                uid: Int,
                insightId: Long?,
            ): AppActionsViewModel
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
            const val SETTLE_MS = 120L
            const val PER_USER_RANGE = 100_000
        }
    }
