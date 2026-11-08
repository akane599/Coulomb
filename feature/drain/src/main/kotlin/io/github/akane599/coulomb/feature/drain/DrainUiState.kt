// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.drain

import androidx.compose.runtime.Immutable
import io.github.akane599.coulomb.core.data.DrainReport
import io.github.akane599.coulomb.core.data.DrainSpan
import io.github.akane599.coulomb.core.model.PrivilegeIssue

/** What Drain shows (SPEC 10.3), in the states of SPEC 10.11. */
internal sealed interface DrainUiState {
    /** Nothing read yet: the static skeleton. */
    data object Loading : DrainUiState

    /** The records could not be read. */
    data object Error : DrainUiState

    @Immutable
    data class Ready(
        /** The window the selector shows as chosen. */
        val chosen: DrainSpan,
        /** The chosen window's report; while a new choice loads, the previous one's. Null: no data. */
        val report: DrainReport?,
        /** False while [report] still belongs to an earlier choice. */
        val current: Boolean,
        /** The Apps lens lists system UIDs (SPEC 10.3). */
        val includeSystem: Boolean,
        /** Apps scaled to the measured total, labelled Derived (SPEC 6.5); off by default. */
        val scaled: Boolean,
        /** What keeps snapshots from being taken, if anything (SPEC 4.5, 10.11 partial). */
        val issue: PrivilegeIssue? = null,
    ) : DrainUiState
}
