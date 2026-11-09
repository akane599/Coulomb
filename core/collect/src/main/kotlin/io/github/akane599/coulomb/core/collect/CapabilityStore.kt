// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect

import android.util.Log
import io.github.akane599.coulomb.core.database.dao.DeviceDao
import io.github.akane599.coulomb.core.database.entity.CapabilityEntity
import io.github.akane599.coulomb.core.model.Capability
import io.github.akane599.coulomb.core.model.CapabilityId
import io.github.akane599.coulomb.core.privileged.PrivilegeConfig
import io.github.akane599.coulomb.core.privileged.capability.CapabilityProber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Capability Matrix (SPEC 4.6) as stored in the `capability` table, and the one way to run the
 * probe suite (ADR-0047 §5): at first start and after an OS update ([probeIfStale]), on the
 * onboarding result step and on "Re-run probes" ([probe]). One run at a time.
 */
@Singleton
public class CapabilityStore
    @Inject
    internal constructor(
        private val prober: CapabilityProber,
        private val devices: DeviceDao,
        private val config: PrivilegeConfig,
    ) {
        private val lock = Mutex()
        private val running = MutableStateFlow(false)

        /** The stored capabilities, in [CapabilityId] order; empty before the first run. */
        public val capabilities: Flow<List<Capability>> =
            devices
                .observeCapabilities()
                .map { rows -> rows.mapNotNull { it.toModel() }.sortedBy { it.id.ordinal } }
                .distinctUntilChanged()
                // Each row's detail is JSON; parse it off the main thread.
                .flowOn(Dispatchers.Default)

        /** True while a probe run is in progress. */
        public val probing: StateFlow<Boolean> = running.asStateFlow()

        /**
         * Runs the probe suite and stores what it finds. A failed run throws and leaves the stored
         * results as they were; a caller already waiting on a run gets the next one.
         */
        public suspend fun probe(): List<Capability> =
            lock.withLock {
                running.value = true
                try {
                    withContext(Dispatchers.IO) {
                        val found = prober.probeAll()
                        devices.upsertCapabilities(found.map { it.toEntity() })
                        found
                    }
                } finally {
                    running.value = false
                }
            }

        /**
         * Probes when nothing is stored or the results come from another OS build (SPEC 4.6). Never
         * throws: a failed run is logged and the next start tries again.
         */
        public suspend fun probeIfStale() {
            try {
                val stored = devices.observeCapabilities().first()
                if (stored.isEmpty() || stored.any { it.fingerprint != config.fingerprint }) probe()
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                // A probe, Room or the platform failed; the stored matrix stays as it was.
                Log.w(TAG, "start-up probe failed", e)
            }
        }

        private companion object {
            const val TAG = "CapabilityStore"
        }
    }

internal fun Capability.toEntity(): CapabilityEntity =
    CapabilityEntity(
        id = id.key,
        state = state,
        tier = tier,
        reason = reason,
        fixAction = fixAction,
        probedAtWall = probedAtWallMs,
        fingerprint = fingerprint,
        detailJson = JSONObject(detail).toString(),
    )

/** Null for a row whose id a later build no longer knows. */
internal fun CapabilityEntity.toModel(): Capability? {
    val known = CapabilityId.entries.firstOrNull { it.key == id } ?: return null
    val json = runCatching { JSONObject(detailJson) }.getOrNull()
    val detail =
        json
            ?.keys()
            ?.asSequence()
            ?.associateWith { json.optString(it) }
            .orEmpty()
    return Capability(known, state, tier, reason, fixAction, probedAtWall, fingerprint, detail)
}
