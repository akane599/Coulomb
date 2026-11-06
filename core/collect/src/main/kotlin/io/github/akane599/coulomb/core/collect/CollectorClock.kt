// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicInteger

/** The two clocks the collector stamps data with (SPEC 7.8): elapsed for order, wall for display. */
public interface CollectorClock {
    /** `SystemClock.elapsedRealtimeNanos()`: counts through sleep, restarts at boot. */
    public fun elapsedNs(): Long

    public fun wallMs(): Long

    /**
     * How many wall-clock changes (`ACTION_TIME_CHANGED`) were seen so far. A history read that
     * overlapped one cannot pair the history clock with the wall clock (ADR-0018).
     */
    public fun wallChanges(): Int
}

/** The device's clocks; the service's time receiver calls [noteWallChange]. */
internal object SystemCollectorClock : CollectorClock {
    private val changes = AtomicInteger()

    override fun elapsedNs(): Long = SystemClock.elapsedRealtimeNanos()

    override fun wallMs(): Long = System.currentTimeMillis()

    override fun wallChanges(): Int = changes.get()

    fun noteWallChange() {
        changes.incrementAndGet()
    }
}
