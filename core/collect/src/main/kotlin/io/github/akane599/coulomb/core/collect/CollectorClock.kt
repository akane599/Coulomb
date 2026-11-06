// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect

import android.os.SystemClock

/** The two clocks the collector stamps data with (SPEC 7.8): elapsed for order, wall for display. */
public interface CollectorClock {
    /** `SystemClock.elapsedRealtimeNanos()`: counts through sleep, restarts at boot. */
    public fun elapsedNs(): Long

    public fun wallMs(): Long
}

internal object SystemCollectorClock : CollectorClock {
    override fun elapsedNs(): Long = SystemClock.elapsedRealtimeNanos()

    override fun wallMs(): Long = System.currentTimeMillis()
}
