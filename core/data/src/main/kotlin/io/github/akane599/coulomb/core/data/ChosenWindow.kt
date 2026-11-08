// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The window Drain and App detail show (SPEC 10.3, 10.4). Kept in memory only: it lasts while the
 * app runs, and a new start opens Since unplugged, as Now does (ADR-0036).
 */
@Singleton
public class ChosenWindow
    @Inject
    constructor() {
        private val chosen = MutableStateFlow<DrainSpan>(DrainSpan.SinceUnplugged)

        public val span: StateFlow<DrainSpan> = chosen.asStateFlow()

        public fun choose(span: DrainSpan) {
            chosen.value = span
        }
    }
