// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.insights

/** The rules about temperature, charging and capacity (SPEC 9.2). */
internal object BatteryRules {
    private const val MINUTES_PER_HOUR = 60.0
    private const val MS_PER_SECOND = 1000.0

    /**
     * R11: the battery is above 40 °C for more than 10 min of one charge. A reading counts for the
     * time until the next one; a pair further apart than the sampler ever leaves them (or out of
     * order, after a clock change) counts for nothing rather than for a guess.
     */
    val hotCharging =
        rule(RuleId.HOT_CHARGING, setOf(RuleInput.CHARGE_SESSIONS)) {
            val minutes = limit("minutes")
            val maxGapMs = limit("maxSampleGapSeconds") * MS_PER_SECOND
            ctx.chargeSessions.mapNotNull { session ->
                val hotMs =
                    session.samples.zipWithNext().sumOf { (at, next) ->
                        val ms = next.wallMs - at.wallMs
                        val hot = at.charging && at.tempC > limit("tempC")
                        if (hot && ms > 0 && ms <= maxGapMs) ms else 0L
                    }
                if (hotMs / MS_PER_MINUTE <= minutes) return@mapNotNull null
                val values = mapOf("tempC" to limit("tempC"), "peakC" to session.samples.maxOf { it.tempC })
                val evidence = session.span.evidence(hotMs / MS_PER_MINUTE, minutes, EvidenceUnit.MINUTES, values, sessionId = session.id)
                found(Subject.session(session.id), evidence)
            }
        }

    /** R12: the battery is above 45 °C at any time. The card shows the top consumer at that moment. */
    val hotBattery =
        rule(RuleId.HOT_BATTERY, setOf(RuleInput.TEMPERATURE), listOf(ActionId.OPEN_APP_DETAIL)) {
            val hottest = ctx.temperatures.maxByOrNull { it.tempC }
            if (hottest == null || hottest.tempC <= limit("tempC")) return@rule emptyList()
            val evidence = Span(hottest.wallMs, hottest.wallMs).evidence(hottest.tempC, limit("tempC"), EvidenceUnit.CELSIUS)
            listOf(found(null, evidence, relatedUid = hottest.topUid))
        }

    /** R13: a charge's power is below 70% of what its charger usually gives. */
    val slowCharging =
        rule(RuleId.SLOW_CHARGING, setOf(RuleInput.CHARGE_SESSIONS)) {
            ctx.chargeSessions.mapNotNull { session ->
                val watts = session.avgW ?: return@mapNotNull null
                val usual = session.baselineW?.takeIf { it > 0 } ?: return@mapNotNull null
                val threshold = usual * limit("baselineShare")
                if (watts >= threshold) return@mapNotNull null
                val labels = session.chargerName?.let { mapOf("charger" to it) }.orEmpty()
                val values = mapOf("baselineW" to usual)
                val evidence = session.span.evidence(watts, threshold, EvidenceUnit.WATTS, values, labels, session.id)
                found(session.chargerId?.let(Subject::charger), evidence)
            }
        }

    /**
     * R14: the capacity estimate falls by more than 3 points within 30 days. The latest day is
     * compared with the oldest one in those 30 days, and only when they are far enough apart that the
     * difference is not one estimate's noise (ADR-0054).
     */
    val capacityDrop =
        rule(RuleId.CAPACITY_DROP, setOf(RuleInput.HEALTH), listOf(ActionId.OPEN_HEALTH)) {
            val latest = ctx.health.lastOrNull() ?: return@rule emptyList()
            val oldest = latest.wallMs - limit("days") * MS_PER_DAY
            val newest = latest.wallMs - limit("minDaysApart") * MS_PER_DAY
            val reference = ctx.health.firstOrNull { it.wallMs >= oldest && it.wallMs <= newest } ?: return@rule emptyList()
            val drop = reference.healthPct - latest.healthPct
            if (drop <= limit("points")) return@rule emptyList()
            val values = mapOf("fromPct" to reference.healthPct, "toPct" to latest.healthPct)
            listOf(found(null, Span(reference.wallMs, latest.wallMs).evidence(drop, limit("points"), EvidenceUnit.POINTS, values)))
        }

    /**
     * R15: more than 2 h at 100% while plugged, with battery protection off. Where the protection
     * setting could not be read the rule finds nothing: it cannot claim the setting is off.
     */
    val longAtFull =
        rule(RuleId.LONG_AT_FULL, setOf(RuleInput.CHARGE_SESSIONS), listOf(ActionId.OPEN_BATTERY_PROTECTION)) {
            val hours = limit("hoursAtFull")
            val session =
                ctx.chargeSessions.lastOrNull { it.protectionOn == false && it.minutesAtFull / MINUTES_PER_HOUR > hours }
                    ?: return@rule emptyList()
            val evidence =
                session.span.evidence(
                    session.minutesAtFull / MINUTES_PER_HOUR,
                    hours,
                    EvidenceUnit.HOURS,
                    sessionId = session.id,
                )
            listOf(found(null, evidence))
        }
}
