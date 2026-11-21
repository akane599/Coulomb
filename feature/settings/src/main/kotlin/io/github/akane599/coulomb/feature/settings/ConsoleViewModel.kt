// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.settings

import android.database.SQLException
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.akane599.coulomb.core.data.ConsoleQuery
import io.github.akane599.coulomb.core.data.ConsoleTable
import io.github.akane599.coulomb.core.data.DeveloperConsole
import io.github.akane599.coulomb.core.datastore.SettingsStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

/** The console's query, its answer once read, and whether the read failed. */
@Immutable
internal data class ConsoleUiState(
    val query: ConsoleQuery = ConsoleQuery.STORAGE,
    val table: ConsoleTable? = null,
    val failed: Boolean = false,
)

/** SPEC 14.4 "Developer console": one read-only typed query at a time, read when it is chosen. */
@HiltViewModel
public class ConsoleViewModel
    @Inject
    internal constructor(
        private val console: DeveloperConsole,
        private val settings: SettingsStore,
    ) : ViewModel() {
        private val ui = MutableStateFlow(ConsoleUiState())
        internal val state: StateFlow<ConsoleUiState> = ui.asStateFlow()
        private var reading: Job? = null

        init {
            select(ConsoleQuery.STORAGE)
        }

        internal fun select(query: ConsoleQuery) {
            reading?.cancel()
            ui.update { ConsoleUiState(query) }
            reading =
                viewModelScope.launch {
                    try {
                        val table = console.run(query)
                        ui.update { it.copy(table = table) }
                    } catch (e: SQLException) {
                        Log.w(TAG, "console query $query failed", e)
                        ui.update { it.copy(failed = true) }
                    }
                }
        }

        /** Hides the console again; the seven taps bring it back. */
        internal fun hide() {
            viewModelScope.launch {
                try {
                    settings.developerConsole(false)
                } catch (e: IOException) {
                    Log.w(TAG, "console not hidden", e)
                }
            }
        }

        private companion object {
            const val TAG = "Console"
        }
    }
