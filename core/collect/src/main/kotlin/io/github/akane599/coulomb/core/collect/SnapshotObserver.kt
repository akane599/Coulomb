// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

/**
 * Something that follows the collector's snapshots: SPEC 7.5's last steps, the insight engine and
 * the surfaces outside the app (SPEC 12). It runs on the collector's single consumer after the
 * snapshot is stored, so it sees the stored state and delays the next event while it runs. A
 * failure is logged and does not stop collection.
 */
public fun interface SnapshotObserver {
    /** A snapshot was stored; [heavy] when it read the platform's statistics. */
    public suspend fun onSnapshot(heavy: Boolean)

    /**
     * The level changed on the charger and was stored as a sample, not a snapshot (ADR-0015). Only
     * what follows each percent there belongs here: the Charging Live Update and the alarms
     * (SPEC 12.2, 12.3; audit BUG-003). Off the charger nothing is called per percent.
     */
    public suspend fun onLevel() {}
}

/** The set is empty unless a module binds observers into it. */
@Module
@InstallIn(SingletonComponent::class)
public interface SnapshotObserverModule {
    @Multibinds
    public fun observers(): Set<SnapshotObserver>
}
