// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A pull to refresh's spinner: on from the pull until [request] returns, or [timeoutMs] at most.
 * For a snapshot, [request] returns once the collector has handled it, stored or debounced. The
 * end is the request's own, not the next change of some read model: that ended the spinner on an
 * unrelated change, and kept it for the whole wait when the pull was debounced (audit BUG-017). A
 * pull while one runs is ignored.
 */
public class PullRefresh(
    private val scope: CoroutineScope,
    private val timeoutMs: Long,
    private val request: suspend () -> Unit,
) {
    private val running = MutableStateFlow(false)
    public val isRefreshing: StateFlow<Boolean> = running.asStateFlow()

    public fun pull() {
        if (!running.compareAndSet(expect = false, update = true)) return
        scope.launch {
            try {
                withTimeoutOrNull(timeoutMs) { request() }
            } finally {
                running.value = false
            }
        }
    }
}
