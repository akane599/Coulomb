// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.insights

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.akane599.coulomb.core.insights.AppStandby
import io.github.akane599.coulomb.core.model.cmd.StandbyBucket
import io.github.akane599.coulomb.core.parsing.cmd.CmdOutputs
import io.github.akane599.coulomb.core.privileged.DumpResult
import io.github.akane599.coulomb.core.privileged.PrivilegedAccess
import io.github.akane599.coulomb.core.privileged.PrivilegedQuery
import javax.inject.Inject
import javax.inject.Singleton

/** Each app's standby bucket and when it was last opened, joined by package. Pure. */
internal object StandbyFacts {
    /**
     * [lastUsed] holds a package only when the phone has a record of its use; a time of zero is
     * no record. An app without a UID (uninstalled since) is left out.
     */
    fun of(
        buckets: Map<String, StandbyBucket>,
        lastUsed: Map<String, Long>,
        uidOf: (String) -> Int?,
    ): List<AppStandby> =
        buckets
            .mapNotNull { (name, bucket) ->
                val uid = uidOf(name) ?: return@mapNotNull null
                AppStandby(uid, active = bucket == StandbyBucket.ACTIVE, lastOpenedWallMs = lastUsed[name]?.takeIf { it > 0 })
            }
            // Packages that share a UID are one app to the rules: Active if any is, opened when the latest was.
            .groupBy { it.uid }
            .map { (uid, rows) -> AppStandby(uid, rows.any { it.active }, rows.mapNotNull { it.lastOpenedWallMs }.maxOrNull()) }
            .sortedBy { it.uid }
}

/**
 * R16's input (SPEC 9.2): `am get-standby-bucket` through the worker and the platform's usage
 * records. Read at most once a day, while the worker is bound for the heavy snapshot the insights
 * follow; between reads the last answer stands. Without the worker, or without usage access, it
 * is empty and the rule finds nothing.
 */
@Singleton
class AppStandbyReader
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val access: PrivilegedAccess,
    ) {
        private var readAtWallMs = 0L
        private var last: List<AppStandby> = emptyList()

        suspend fun read(nowWallMs: Long = System.currentTimeMillis()): List<AppStandby> {
            if (nowWallMs - readAtWallMs in 0 until DAY_MS) return last
            val buckets = buckets() ?: return last
            last = StandbyFacts.of(buckets, lastUsed(nowWallMs), ::uidOf)
            readAtWallMs = nowWallMs
            return last
        }

        private suspend fun buckets(): Map<String, StandbyBucket>? {
            val result =
                access.runQuery(PrivilegedQuery.STANDBY_BUCKETS, timeoutMs = QUERY_TIMEOUT_MS) { input ->
                    CmdOutputs.standbyBuckets(input.readBytes().toString(Charsets.UTF_8))
                }
            return (result as? DumpResult.Complete)?.value
        }

        /**
         * `UsageStats.getLastTimeUsed` per package over the records the platform keeps for the
         * range; empty without usage access (`queryAndAggregateUsageStats` then returns nothing).
         */
        private fun lastUsed(nowWallMs: Long): Map<String, Long> =
            context
                .getSystemService(UsageStatsManager::class.java)
                ?.queryAndAggregateUsageStats(nowWallMs - RANGE_DAYS * DAY_MS, nowWallMs)
                .orEmpty()
                .mapValues { it.value.lastTimeUsed }

        private fun uidOf(packageName: String): Int? =
            try {
                context.packageManager.getPackageUid(packageName, 0)
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }

        private companion object {
            const val DAY_MS = 86_400_000L

            /** Far enough back that an app unused for R16's week still has its last use on record. */
            const val RANGE_DAYS = 90L
            const val QUERY_TIMEOUT_MS = 3_000L
        }
    }
