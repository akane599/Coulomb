// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.data

import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * When an app was last updated, from `PackageInfo.lastUpdateTime` (ADR-0041 §5): the platform keeps
 * only the latest update, so App detail's trend has at most one marker. The app holds
 * `QUERY_ALL_PACKAGES`, so other packages are visible.
 */
@Singleton
public class AppUpdates
    @Inject
    internal constructor(
        @ApplicationContext context: Context,
    ) {
        private val pm = context.packageManager

        /**
         * The latest update among [packages], or null when none was updated after its install or
         * none is installed. Blocking: call it off the main thread.
         */
        public fun lastUpdate(packages: List<String>): Long? =
            packages
                .mapNotNull { name ->
                    try {
                        pm.getPackageInfo(name, 0).takeIf { it.lastUpdateTime > it.firstInstallTime }?.lastUpdateTime
                    } catch (_: PackageManager.NameNotFoundException) {
                        null
                    }
                }.maxOrNull()

        internal companion object {
            private val NAME = Regex("\"([^\"]+)\"")

            /** `uid_meta.packagesJson`, `["a","b"]` as the collector writes it. */
            fun packages(json: String): List<String> = NAME.findAll(json).map { it.groupValues[1] }.toList()
        }
    }
