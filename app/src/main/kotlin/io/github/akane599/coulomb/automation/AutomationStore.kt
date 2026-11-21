// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.automation

import android.content.Context
import android.os.Process
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.akane599.coulomb.core.collect.SnapshotObserver
import io.github.akane599.coulomb.core.common.AutomationContract
import io.github.akane599.coulomb.core.data.BatteryReading
import io.github.akane599.coulomb.core.data.BatteryRepository
import io.github.akane599.coulomb.core.data.DrainRepository
import io.github.akane599.coulomb.core.data.NightRepository
import io.github.akane599.coulomb.core.datastore.SettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the provider answers with (SPEC 12.7). Nothing is read until a caller asks. The night and
 * the drains are then kept until the next heavy snapshot, which is what changes them; the level
 * and the rates are read again once they are [LEVEL_AGE_MS] old, so a caller that polls costs one
 * read in that time, not one per query.
 */
@Singleton
class AutomationStore
    @Inject
    internal constructor(
        @ApplicationContext private val context: Context,
        private val battery: BatteryRepository,
        private val drains: DrainRepository,
        private val nights: NightRepository,
        private val settings: SettingsStore,
    ) : SnapshotObserver {
        private val lock = Mutex()
        private var slow: Map<String, List<AutomationRow>>? = null
        private var reading: BatteryReading? = null
        private var readAtMs = Long.MIN_VALUE

        /** A heavy snapshot may change the night and the drains; any snapshot changes the level. */
        override suspend fun onSnapshot(heavy: Boolean) {
            lock.withLock {
                if (heavy) slow = null
                readAtMs = Long.MIN_VALUE
            }
        }

        /** The rows of [path], or null while the Automation API is off. */
        internal suspend fun rows(path: String): List<AutomationRow>? {
            val now = settings.settings.first()
            if (!now.automation) return null
            return lock.withLock {
                when (path) {
                    AutomationContract.PATH_INFO -> AutomationFacts.info(versionName(), now.liteMode)
                    AutomationContract.PATH_LEVEL -> AutomationFacts.level(reading()?.battery)
                    AutomationContract.PATH_RATES -> AutomationFacts.rates(reading())
                    else -> slow().getValue(path)
                }
            }
        }

        private suspend fun reading(): BatteryReading? {
            val nowMs = SystemClock.elapsedRealtime()
            if (readAtMs == Long.MIN_VALUE || nowMs - readAtMs >= LEVEL_AGE_MS) {
                reading = battery.reading.first()
                readAtMs = nowMs
            }
            return reading
        }

        private suspend fun slow(): Map<String, List<AutomationRow>> =
            slow ?: mapOf(
                AutomationContract.PATH_NIGHT to AutomationFacts.night(nights.nights.first().maxByOrNull { it.endWallMs }),
                AutomationContract.PATH_DRAINS to AutomationFacts.drains(drains.sinceUnplugged.first(), Process.myUid() / PER_USER_RANGE),
            ).also { slow = it }

        private fun versionName(): String =
            context.packageManager
                .getPackageInfo(context.packageName, 0)
                .versionName
                .orEmpty()

        private companion object {
            const val LEVEL_AGE_MS = 30_000L

            /** `UserHandle.PER_USER_RANGE`. */
            const val PER_USER_RANGE = 100_000
        }
    }
