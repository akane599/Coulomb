// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.ui.insight

import android.content.res.Resources
import io.github.akane599.coulomb.core.model.Provenance
import io.github.akane599.coulomb.core.model.insight.Evidence
import io.github.akane599.coulomb.core.model.insight.EvidenceUnit
import io.github.akane599.coulomb.core.model.insight.Insight
import io.github.akane599.coulomb.core.model.insight.RuleId
import io.github.akane599.coulomb.core.ui.DataTable
import io.github.akane599.coulomb.core.ui.R
import io.github.akane599.coulomb.core.ui.breakable
import io.github.akane599.coulomb.core.ui.format.Formats
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToLong

/** An insight in words: what happened, then what the user can do or why nothing can be done (SPEC 1.1, 9). */
public data class InsightText(
    val title: String,
    val body: String,
)

/**
 * The words of each SPEC 9.2 rule, in the device's language, with the numbers its evidence holds
 * formatted as everywhere else ([Formats], SPEC 6.7). Templates, not generated text (SPEC 9.3).
 */
public class InsightCopy(
    private val resources: Resources,
    private val formats: Formats,
    /** Where a title's or body's time is read: a stretch or a charge is named by its day and clock time. */
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    /** [appName] is the label of the app the insight is about or points at; null when it has none or it is not known. */
    @Suppress("CyclomaticComplexMethod", "LongMethod") // One branch per rule, each two lines.
    public fun text(
        insight: Insight,
        appName: String?,
    ): InsightText {
        val e = insight.evidence
        val app = appName ?: resources.getString(R.string.insight_app_unknown)
        return when (insight.rule) {
            RuleId.EXCESSIVE_WAKELOCK -> {
                text(
                    R.string.insight_r01_title,
                    listOf(app, formats.hours(e.measured)),
                    R.string.insight_r01_body,
                    listOf(formats.hours(e.threshold)),
                )
            }

            RuleId.LONG_WAKELOCK_TAG -> {
                text(
                    R.string.insight_r02_title,
                    listOf(minutes(e.measured)),
                    // The tag is a raw identifier: it may wrap after its separators, and an empty one is not quoted.
                    if (e.labels["tag"].isNullOrEmpty()) R.string.insight_r02_body_unnamed else R.string.insight_r02_body,
                    listOf(app, breakable(e.labels["tag"].orEmpty())),
                )
            }

            RuleId.POOR_DEEP_SLEEP -> {
                text(
                    R.string.insight_r03_title,
                    // Which stretch: the strip above the card has its own screen-off figure, for another span.
                    listOf(share(e.measured), formats.dayClock(e.toWallMs, zone)),
                    R.string.insight_r03_body,
                    listOf(formats.hours(e.values["hours"] ?: 0.0), share(e.threshold)),
                )
            }

            RuleId.HIGH_SCREEN_OFF_DRAIN -> {
                text(
                    R.string.insight_r04_title,
                    listOf(rate(e.measured), formats.dayClock(e.toWallMs, zone)),
                    R.string.insight_r04_body,
                    listOf(rate(e.values["median"] ?: 0.0)),
                )
            }

            RuleId.ALARM_STORM -> {
                text(
                    R.string.insight_r05_title,
                    listOf(app, count(e.measured)),
                    R.string.insight_r05_body,
                    listOf(count(e.values["alarms"] ?: 0.0), formats.hours(e.values["screenOffHours"] ?: 0.0)),
                )
            }

            RuleId.BACKGROUND_LOCATION -> {
                text(R.string.insight_r06_title, listOf(app, minutes(e.measured)), R.string.insight_r06_body)
            }

            RuleId.WEAK_SIGNAL_TAX -> {
                text(
                    R.string.insight_r07_title,
                    emptyList(),
                    R.string.insight_r07_body,
                    listOf(
                        share(e.measured),
                        share(e.values["modemShare"] ?: 0.0),
                    ),
                )
            }

            RuleId.SCAN_HEAVY -> {
                val title = if (e.labels["radio"] == "bluetooth") R.string.insight_r08_title_bluetooth else R.string.insight_r08_title_wifi
                text(title, listOf(minutes(e.measured)), R.string.insight_r08_body)
            }

            RuleId.SENSOR_HOLDER -> {
                text(R.string.insight_r09_title, listOf(app, minutes(e.measured)), R.string.insight_r09_body)
            }

            RuleId.JOB_CHURN -> {
                val title = if (e.labels["reason"] == "abandoned") R.string.insight_r10_title_abandoned else R.string.insight_r10_title
                text(title, listOf(app, count(e.measured)), R.string.insight_r10_body)
            }

            RuleId.HOT_CHARGING -> {
                text(
                    R.string.insight_r11_title,
                    listOf(celsius(e.values["tempC"] ?: 0.0), minutes(e.measured)),
                    R.string.insight_r11_body,
                    listOf(celsius(e.values["peakC"] ?: 0.0), formats.dayClock(e.fromWallMs, zone)),
                )
            }

            RuleId.HOT_BATTERY -> {
                if (appName != null) {
                    text(
                        R.string.insight_r12_title,
                        listOf(celsius(e.measured)),
                        R.string.insight_r12_body_app,
                        listOf(appName, celsius(e.threshold)),
                    )
                } else {
                    text(R.string.insight_r12_title, listOf(celsius(e.measured)), R.string.insight_r12_body, listOf(celsius(e.threshold)))
                }
            }

            RuleId.SLOW_CHARGING -> {
                text(
                    R.string.insight_r13_title,
                    listOf(formats.watts(e.measured).joined()),
                    R.string.insight_r13_body,
                    listOf(formats.watts(e.values["baselineW"] ?: 0.0).joined(), formats.dayClock(e.fromWallMs, zone)),
                )
            }

            RuleId.CAPACITY_DROP -> {
                capacityDrop(e)
            }

            RuleId.LONG_AT_FULL -> {
                text(R.string.insight_r15_title, listOf(formats.hours(e.measured)), R.string.insight_r15_body)
            }

            RuleId.UNUSED_ACTIVE_APP -> {
                text(R.string.insight_r16_title, listOf(app, count(e.measured)), R.string.insight_r16_body)
            }

            RuleId.DOZE_NEVER_DEEP -> {
                text(R.string.insight_r17_title, emptyList(), R.string.insight_r17_body, listOf(count(e.measured)))
            }

            RuleId.UPDATE_REGRESSION -> {
                text(
                    R.string.insight_r18_title,
                    listOf(app),
                    R.string.insight_r18_body,
                    listOf(formats.mah(e.measured).joined(), formats.mah(e.values["median"] ?: 0.0).joined()),
                )
            }

            RuleId.UNATTRIBUTED_GAP -> {
                val measured = e.values["measuredMah"] ?: 0.0
                val assigned = measured - (e.values["unattributedMah"] ?: 0.0)
                text(
                    R.string.insight_r19_title,
                    listOf(share(e.measured)),
                    R.string.insight_r19_body,
                    listOf(formats.mah(assigned).joined(), formats.mah(measured).joined(), span(e)),
                )
            }

            RuleId.SELF_COST -> {
                text(
                    R.string.insight_r20_title,
                    listOf(smallPercent(e.measured)),
                    R.string.insight_r20_body,
                    listOf(smallPercent(e.threshold)),
                )
            }
        }
    }

    /**
     * "Why am I seeing this?" (SPEC 9.4): the rule, its threshold, the measured value and the span
     * it covers, as a table. Times within [zone]'s today read as clock times; older ones name the day.
     */
    public fun evidence(
        insight: Insight,
        zone: ZoneId,
        nowWallMs: Long,
    ): DataTable {
        val e = insight.evidence
        val period =
            if (e.fromWallMs == e.toWallMs) {
                formats.clock(e.toWallMs, zone, nowWallMs)
            } else {
                // Two ends on different days each name their day: "Sat 2:20 PM – Sun 2:20 PM", never a span that reads as nothing.
                val sameDay =
                    Instant.ofEpochMilli(e.fromWallMs).atZone(zone).toLocalDate() ==
                        Instant.ofEpochMilli(e.toWallMs).atZone(zone).toLocalDate()
                val to = if (sameDay) formats.clock(e.toWallMs, zone, nowWallMs) else formats.endClock(e.toWallMs, zone, nowWallMs)
                formats.range(formats.clock(e.fromWallMs, zone, nowWallMs), to)
            }
        // Each row's own source (SPEC 10.1, audit BUG-012): the rule, a configured threshold and the
        // period are no figures of the phone's and carry no mark; a threshold or a usual value worked
        // out from the user's own past is Derived.
        val computed = Provenance.DERIVED.takeIf { insight.rule in COMPUTED_THRESHOLDS }
        val rows =
            listOfNotNull(
                Triple(R.string.insight_row_rule, resources.getString(ruleName(insight.rule)), null),
                Triple(R.string.insight_row_measured, value(e.measured, e.unit), provenance(insight.rule)),
                Triple(R.string.insight_row_threshold, value(e.threshold, e.unit), computed),
                e.values["median"]?.let { Triple(R.string.insight_row_baseline, value(it, e.unit), Provenance.DERIVED) },
                Triple(R.string.insight_row_period, period, null),
            )
        return DataTable(
            title = resources.getString(R.string.insight_why_title),
            columns = listOf(resources.getString(R.string.insight_column_what), resources.getString(R.string.insight_column_value)),
            rows = rows.map { (label, value, _) -> listOf(resources.getString(label), value) },
            provenance = null,
            rowProvenance = rows.map { it.third },
        )
    }

    /** A measured value or a threshold in its unit, as the cards write it. */
    public fun value(
        value: Double,
        unit: EvidenceUnit,
    ): String =
        when (unit) {
            EvidenceUnit.HOURS -> formats.hours(value)
            EvidenceUnit.MINUTES -> minutes(value)
            EvidenceUnit.PERCENT -> if (value < 1) smallPercent(value) else share(value)
            EvidenceUnit.PERCENT_PER_HOUR -> rate(value)
            EvidenceUnit.PER_HOUR -> resources.getString(R.string.insight_per_hour, formats.decimal(value))
            EvidenceUnit.COUNT -> count(value)
            EvidenceUnit.CELSIUS -> celsius(value)
            EvidenceUnit.WATTS -> formats.watts(value).joined()
            EvidenceUnit.POINTS -> resources.getString(R.string.insight_points, formats.decimal(value))
            EvidenceUnit.DAYS -> resources.getString(R.string.insight_days, count(value))
            EvidenceUnit.MAH -> formats.mah(value).joined()
        }

    /** Where the rule's measured value comes from (SPEC 6.7). */
    private fun provenance(rule: RuleId): Provenance =
        when (rule) {
            RuleId.POOR_DEEP_SLEEP,
            RuleId.HIGH_SCREEN_OFF_DRAIN,
            RuleId.ALARM_STORM,
            RuleId.WEAK_SIGNAL_TAX,
            RuleId.UNATTRIBUTED_GAP,
            -> Provenance.DERIVED

            RuleId.CAPACITY_DROP -> Provenance.ESTIMATED

            RuleId.UPDATE_REGRESSION, RuleId.SELF_COST -> Provenance.MODELED

            else -> Provenance.MEASURED
        }

    private fun ruleName(rule: RuleId): Int = RULE_NAMES.getValue(rule)

    private fun capacityDrop(e: Evidence): InsightText {
        val days = ((e.toWallMs - e.fromWallMs) / MS_PER_DAY).roundToLong()
        val body = listOf(share(e.values["fromPct"] ?: 0.0), share(e.values["toPct"] ?: 0.0), formats.integer(days))
        return text(R.string.insight_r14_title, listOf(formats.decimal(e.measured)), R.string.insight_r14_body, body)
    }

    @Suppress("SpreadOperator") // A string resource takes its arguments as varargs; these lists hold three at most.
    private fun text(
        title: Int,
        titleArgs: List<String>,
        body: Int,
        bodyArgs: List<String> = emptyList(),
    ) = InsightText(resources.getString(title, *titleArgs.toTypedArray()), resources.getString(body, *bodyArgs.toTypedArray()))

    private fun minutes(value: Double): String = formats.duration(value.roundToLong())

    /** The evidence's period with its day: "Thu 6:10 AM – 9:40 AM", the second day only where it differs. */
    private fun span(e: Evidence): String {
        val sameDay =
            Instant.ofEpochMilli(e.fromWallMs).atZone(zone).toLocalDate() == Instant.ofEpochMilli(e.toWallMs).atZone(zone).toLocalDate()
        val to = if (sameDay) formats.clock(e.toWallMs, zone) else formats.dayClock(e.toWallMs, zone)
        return formats.range(formats.dayClock(e.fromWallMs, zone), to)
    }

    private fun count(value: Double): String = formats.integer(value.roundToLong())

    /** [percent] is 0 to 100. */
    private fun share(percent: Double): String = formats.share(percent / PERCENT)

    private fun smallPercent(percent: Double): String = resources.getString(R.string.insight_percent_decimal, formats.decimal(percent))

    /** The rate with its unit; a word joiner after the slash keeps "%/h" on one line in a sentence. */
    private fun rate(pctPerHour: Double): String =
        resources.getString(R.string.insight_rate, formats.rate(pctPerHour), formats.rateUnit.replace("/", "/\u2060"))

    private fun celsius(degrees: Double): String = formats.temperature(degrees * DECI).joined()

    private companion object {
        /**
         * Rules whose threshold is a share or a multiple of the user's own usual, not a configured
         * limit alone: R04 and R18 against a median, R13 against the charger's usual power.
         */
        val COMPUTED_THRESHOLDS = setOf(RuleId.HIGH_SCREEN_OFF_DRAIN, RuleId.SLOW_CHARGING, RuleId.UPDATE_REGRESSION)

        val RULE_NAMES: Map<RuleId, Int> =
            RuleId.entries
                .zip(
                    listOf(
                        R.string.insight_rule_r01,
                        R.string.insight_rule_r02,
                        R.string.insight_rule_r03,
                        R.string.insight_rule_r04,
                        R.string.insight_rule_r05,
                        R.string.insight_rule_r06,
                        R.string.insight_rule_r07,
                        R.string.insight_rule_r08,
                        R.string.insight_rule_r09,
                        R.string.insight_rule_r10,
                        R.string.insight_rule_r11,
                        R.string.insight_rule_r12,
                        R.string.insight_rule_r13,
                        R.string.insight_rule_r14,
                        R.string.insight_rule_r15,
                        R.string.insight_rule_r16,
                        R.string.insight_rule_r17,
                        R.string.insight_rule_r18,
                        R.string.insight_rule_r19,
                        R.string.insight_rule_r20,
                    ),
                ).toMap()
        const val MS_PER_DAY = 86_400_000.0
        const val PERCENT = 100.0
        const val DECI = 10.0
    }
}
