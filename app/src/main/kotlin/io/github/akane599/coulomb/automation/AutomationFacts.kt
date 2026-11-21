// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.automation

import android.os.BatteryManager
import io.github.akane599.coulomb.core.analytics.TimeLeft
import io.github.akane599.coulomb.core.common.AutomationContract
import io.github.akane599.coulomb.core.data.BatteryNow
import io.github.akane599.coulomb.core.data.BatteryReading
import io.github.akane599.coulomb.core.data.NightSummary
import io.github.akane599.coulomb.core.data.StateRate
import io.github.akane599.coulomb.core.data.WindowDrains
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.ui.format.Rounding
import kotlin.math.roundToLong

/** One row of the provider's answer, in [AutomationContract.COLUMNS]' order. */
internal data class AutomationRow(
    val metric: String,
    val rank: Int = 0,
    val subject: String? = null,
    val subjectLabel: String? = null,
    val value: Double? = null,
    val low: Double? = null,
    val high: Double? = null,
    val text: String? = null,
    val unit: String? = null,
    val provenance: Provenance? = null,
    val quality: Double? = null,
    val state: String = AutomationContract.STATE_OK,
    val fromWallMs: Long? = null,
    val toWallMs: Long? = null,
    val asOfWallMs: Long? = null,
) {
    fun cells(): Array<Any?> =
        arrayOf(
            metric,
            rank,
            subject,
            subjectLabel,
            value,
            low,
            high,
            text,
            unit,
            provenance?.name?.lowercase(),
            quality,
            state,
            fromWallMs,
            toWallMs,
            asOfWallMs,
        )
}

/**
 * The read models cut down to what the Automation API gives out (SPEC 12.7, ADR-0065 §6): the
 * figures Coulomb's own screens show, rounded as they are there (SPEC 6.7), each with its
 * provenance. A figure that is missing is a row that says why, never a guess. Pure.
 */
internal object AutomationFacts {
    fun info(
        version: String,
        lite: Boolean,
    ): List<AutomationRow> =
        listOf(
            AutomationRow("api_version", value = AutomationContract.API_VERSION.toDouble()),
            AutomationRow("app_version", text = version),
            AutomationRow("monitoring_mode", text = if (lite) "lite" else "full"),
        )

    fun level(battery: BatteryNow?): List<AutomationRow> {
        battery ?: return listOf(missing("level"), missing("power_state"))
        return listOf(
            AutomationRow(
                "level",
                value = battery.levelPct.toDouble(),
                unit = AutomationContract.UNIT_PCT,
                provenance = Provenance.MODELED,
                asOfWallMs = battery.wallMs,
            ),
            AutomationRow("power_state", text = powerState(battery), asOfWallMs = battery.wallMs),
        )
    }

    /** The open discharge's rates and the time left; on the charger they have no meaning. */
    fun rates(reading: BatteryReading?): List<AutomationRow> {
        val names = AutomationContract.METRICS.getValue(AutomationContract.PATH_RATES)
        val asOf = reading?.battery?.wallMs
        val discharge = reading?.discharge
        return when {
            reading == null || asOf == null -> {
                names.map { missing(it) }
            }

            reading.battery.plugged -> {
                names.map { AutomationRow(it, state = AutomationContract.STATE_NOT_APPLICABLE, asOfWallMs = asOf) }
            }

            discharge == null -> {
                names.map { missing(it, asOf) }
            }

            else -> {
                listOf(
                    rate("rate_screen_on", discharge.screenOn, discharge.startWallMs, asOf),
                    rate("rate_screen_off", discharge.screenOff, discharge.startWallMs, asOf),
                    timeLeft(discharge.timeLeft, asOf),
                )
            }
        }
    }

    /** SPEC 6.8: a range, never one number, with the ends rounded outward as Now rounds them. */
    private fun timeLeft(
        left: TimeLeft?,
        asOf: Long,
    ): AutomationRow {
        left ?: return AutomationRow("time_left", state = AutomationContract.STATE_COLLECTING, asOfWallMs = asOf)
        val (shortest, longest) = Rounding.timeLeftMinutes(left.shortestHours, left.longestHours)
        return AutomationRow(
            "time_left",
            low = shortest.toDouble(),
            high = longest.toDouble(),
            unit = AutomationContract.UNIT_MIN,
            provenance = left.provenance,
            asOfWallMs = asOf,
        )
    }

    private fun rate(
        metric: String,
        rate: StateRate?,
        fromWallMs: Long,
        asOf: Long,
    ): AutomationRow =
        if (rate == null || !rate.ready) {
            // SPEC 6.2: no rate before 15 minutes of it.
            AutomationRow(metric, state = AutomationContract.STATE_COLLECTING, fromWallMs = fromWallMs, toWallMs = asOf, asOfWallMs = asOf)
        } else {
            AutomationRow(
                metric,
                value = Rounding.rate(rate.pctPerHour),
                unit = AutomationContract.UNIT_PCT_PER_H,
                provenance = rate.provenance,
                quality = rate.quality,
                fromWallMs = fromWallMs,
                toWallMs = asOf,
                asOfWallMs = asOf,
            )
        }

    /** The latest night of the Night Report (SPEC 10.6): its figures only, no app's name. */
    fun night(night: NightSummary?): List<AutomationRow> {
        val names = AutomationContract.METRICS.getValue(AutomationContract.PATH_NIGHT)
        night ?: return names.map { missing(it) }

        fun row(
            metric: String,
            value: Double?,
            unit: String,
            provenance: Provenance,
            state: String = AutomationContract.STATE_UNAVAILABLE,
        ) = AutomationRow(
            metric,
            value = value,
            unit = unit,
            provenance = provenance.takeIf { value != null },
            state = if (value != null) AutomationContract.STATE_OK else state,
            fromWallMs = night.startWallMs,
            toWallMs = night.endWallMs,
            asOfWallMs = night.endWallMs,
        )
        val reached = night.deepDozeReached
        return listOf(
            row("duration", (night.durationMs / MS_PER_MINUTE).toDouble(), AutomationContract.UNIT_MIN, Provenance.MEASURED),
            row("level_start", night.startLevel.toDouble(), AutomationContract.UNIT_PCT, Provenance.MODELED),
            row("level_end", night.endLevel.toDouble(), AutomationContract.UNIT_PCT, Provenance.MODELED),
            row(
                "rate",
                night.ratePctPerHour?.let(Rounding::rate),
                AutomationContract.UNIT_PCT_PER_H,
                Provenance.DERIVED,
                // A night on the charger has no drain rate; a drop too small for one is not a rate either.
                state = if (night.charged) AutomationContract.STATE_NOT_APPLICABLE else AutomationContract.STATE_UNAVAILABLE,
            ),
            row(
                "deep_sleep",
                night.deepSleepShare?.let { (it * PERCENT).roundToLong().toDouble() },
                AutomationContract.UNIT_PCT,
                Provenance.MEASURED,
            ),
            row("deep_doze_reached", null, "", Provenance.MEASURED).copy(
                unit = null,
                text = reached?.let { if (it) "yes" else "no" },
                provenance = Provenance.MEASURED.takeIf { reached != null },
                state = if (reached != null) AutomationContract.STATE_OK else AutomationContract.STATE_UNAVAILABLE,
            ),
            row(
                "deep_doze_after",
                night.deepDozeAfterMs?.let { (it / MS_PER_MINUTE).toDouble() },
                AutomationContract.UNIT_MIN,
                Provenance.MEASURED,
                state = if (reached == false) AutomationContract.STATE_NOT_APPLICABLE else AutomationContract.STATE_UNAVAILABLE,
            ),
        )
    }

    /**
     * Always three rows: the largest apps since the phone was unplugged with their share of the
     * window's total, as the Drains widget shows them, by name only: no UID and no package. An app
     * of another user or profile is counted and not named ([ownUser] is the user Coulomb runs as).
     */
    fun drains(
        window: WindowDrains?,
        ownUser: Int,
    ): List<AutomationRow> {
        val apps =
            window
                ?.takeIf { it.total > 0 }
                ?.apps
                .orEmpty()
                .sortedByDescending { it.mah }
        return (0 until TOP_DRAINS).map { index ->
            val app = apps.getOrNull(index)
            if (window == null || app == null) {
                missing("drain_share").copy(rank = index + 1)
            } else {
                val named = app.uid / PER_USER_RANGE == ownUser
                AutomationRow(
                    "drain_share",
                    rank = index + 1,
                    subjectLabel = app.label.takeIf { named },
                    value = (window.share(app.mah) * PER_MILLE).roundToLong() / TENTHS,
                    unit = AutomationContract.UNIT_PCT_OF_WINDOW,
                    provenance = Provenance.DERIVED,
                    fromWallMs = window.fromWallMs,
                    toWallMs = window.toWallMs,
                    asOfWallMs = window.toWallMs,
                )
            }
        }
    }

    private fun missing(
        metric: String,
        asOf: Long? = null,
    ) = AutomationRow(metric, state = AutomationContract.STATE_UNAVAILABLE, asOfWallMs = asOf)

    private fun powerState(battery: BatteryNow): String =
        when {
            !battery.plugged -> "on_battery"

            battery.status == BatteryManager.BATTERY_STATUS_FULL -> "full"

            battery.status == BatteryManager.BATTERY_STATUS_CHARGING -> "charging"

            // On a charger that is not charging: a charge limit, or too weak a source.
            else -> "plugged"
        }

    const val TOP_DRAINS = 3
    private const val MS_PER_MINUTE = 60_000L
    private const val PERCENT = 100.0
    private const val PER_MILLE = 1_000.0
    private const val TENTHS = 10.0

    /** `UserHandle.PER_USER_RANGE`: a UID's user is its hundred-thousands. */
    private const val PER_USER_RANGE = 100_000
}
