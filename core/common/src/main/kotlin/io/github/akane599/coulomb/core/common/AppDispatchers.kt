// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * SPEC 15.5: database and package reads on a limited-parallelism IO dispatcher, computation on
 * Default. Passed in, so tests run everything on one test dispatcher.
 */
public class AppDispatchers(
    public val io: CoroutineDispatcher,
    public val default: CoroutineDispatcher,
) {
    public companion object {
        /** Reads that may run at once; Room serialises writes on its own. */
        public const val IO_PARALLELISM: Int = 4

        public fun production(): AppDispatchers = AppDispatchers(Dispatchers.IO.limitedParallelism(IO_PARALLELISM), Dispatchers.Default)
    }
}

/**
 * The process-wide scope that shared read flows live in (ADR-0032), so screens that subscribe one
 * after another reuse one upstream instead of each starting its own.
 */
public class AppScope(
    scope: CoroutineScope,
) : CoroutineScope by scope {
    public constructor(dispatchers: AppDispatchers) : this(CoroutineScope(SupervisorJob() + dispatchers.default))
}
