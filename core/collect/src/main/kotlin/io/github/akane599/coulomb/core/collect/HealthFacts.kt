// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.akane599.coulomb.core.collect.android.AndroidHealthSource
import io.github.akane599.coulomb.core.model.BatteryDates
import io.github.akane599.coulomb.core.privileged.PrivilegedAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the Health screen reads from the device when it opens, not from the database (SPEC 5 S3:
 * "Daily and on Health screen open"; ADR-0044 §4).
 */
@Singleton
public class HealthFacts
    @Inject
    constructor(
        @ApplicationContext context: Context,
        access: PrivilegedAccess,
    ) {
        private val source = AndroidHealthSource(context, access)

        /** The battery's manufacture and first-use dates, as of [today]. */
        public suspend fun dates(today: LocalDate): BatteryDates = withContext(Dispatchers.IO) { source.dates(today) }
    }
