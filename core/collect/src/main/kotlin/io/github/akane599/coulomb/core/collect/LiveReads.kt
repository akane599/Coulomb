// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect

import io.github.akane599.coulomb.core.model.dumpsys.AlarmDump
import io.github.akane599.coulomb.core.model.dumpsys.JobSchedulerDump
import io.github.akane599.coulomb.core.model.dumpsys.PowerDump
import io.github.akane599.coulomb.core.model.dumpsys.ThermalDump
import io.github.akane599.coulomb.core.parsing.dumpsys.AlarmParser
import io.github.akane599.coulomb.core.parsing.dumpsys.JobSchedulerParser
import io.github.akane599.coulomb.core.parsing.dumpsys.PowerParser
import io.github.akane599.coulomb.core.parsing.dumpsys.ThermalServiceParser
import io.github.akane599.coulomb.core.privileged.DumpResult
import io.github.akane599.coulomb.core.privileged.PrivilegedAccess
import io.github.akane599.coulomb.core.privileged.SystemService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The sources a screen reads only while the user asks (ADR-0068): S12's wakelocks held now, S19's
 * temperatures, and S13's and S10's scheduled jobs and alarms. Each is one read-only dump through
 * the allowlisted services (SPEC 19.1) when the user opens its view, so none costs anything in the
 * background, and nothing read here is stored: tags may hold personal text.
 */
@Singleton
public class LiveReads
    @Inject
    constructor(
        private val access: PrivilegedAccess,
    ) {
        /** S12: the power manager's state, the wakelocks held now and battery saver. */
        public suspend fun power(): PowerDump? = read(SystemService.POWER) { PowerParser.parse(it).value }

        /** S19: the thermal service's status and the sensors read while dumping. */
        public suspend fun thermal(): ThermalDump? = read(SystemService.THERMAL) { ThermalServiceParser.parse(it).value }

        /** S10: pending alarms and the alarms delivered since boot. */
        public suspend fun alarms(): AlarmDump? = read(SystemService.ALARM) { AlarmParser.parse(it).value }

        /**
         * S13: scheduled jobs and what holds them back, and the recent job statistics. The parser
         * keeps the jobs it could read; when its report is not complete the list may be short, and a
         * short list would read as a measured count, so it is withheld (ADR-0013; audit BUG-015).
         */
        public suspend fun jobs(): JobSchedulerDump? =
            read(SystemService.JOB_SCHEDULER) { input ->
                val parsed = JobSchedulerParser.parse(input)
                if (parsed.report.complete) parsed.value else parsed.value.copy(registeredJobs = null)
            }

        /** A whole dump's value; a partial or failed one is null, as a view says it could not read. */
        private suspend fun <T> read(
            service: SystemService,
            parse: (java.io.InputStream) -> T,
        ): T? =
            withContext(Dispatchers.IO) {
                (access.dumpService(service) { input -> parse(input) } as? DumpResult.Complete)?.value
            }
    }
