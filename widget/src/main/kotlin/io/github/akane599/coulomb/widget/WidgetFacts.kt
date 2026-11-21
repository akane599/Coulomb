// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.widget

import android.os.BatteryManager
import io.github.akane599.coulomb.core.data.BatteryNow
import io.github.akane599.coulomb.core.data.BatteryReading
import io.github.akane599.coulomb.core.data.HealthReport
import io.github.akane599.coulomb.core.data.NightSummary
import io.github.akane599.coulomb.core.data.WindowDrains
import io.github.akane599.coulomb.core.insights.Contributor
import io.github.akane599.coulomb.core.insights.DrainStory
import io.github.akane599.coulomb.core.insights.StoryInput
import io.github.akane599.coulomb.core.model.story.Story
import io.github.akane599.coulomb.core.model.story.StorySpan
import io.github.akane599.coulomb.core.model.story.StorySubject

/** Whether the phone is on its battery, as the level widget and the tile name it. */
internal enum class PowerState {
    ON_BATTERY,
    CHARGING,
    FULL,

    /** On a charger that is not charging: a charge limit, or too weak a source. */
    PLUGGED,
    ;

    companion object {
        fun of(battery: BatteryNow): PowerState =
            when {
                !battery.plugged -> ON_BATTERY
                battery.status == BatteryManager.BATTERY_STATUS_FULL -> FULL
                battery.status == BatteryManager.BATTERY_STATUS_CHARGING -> CHARGING
                else -> PLUGGED
            }
    }
}

/** Level and verdict (SPEC 12.4). */
internal data class LevelFacts(
    val levelPct: Int,
    val state: PowerState,
    /** The Drain Story since unplugging, whose one line is the verdict; null on the charger. */
    val story: Story?,
    /**
     * The reading's time when it may not be now: in Lite mode, where the widget is drawn only at a
     * snapshot hours apart, or when the reading was already old (ADR-0069). Null otherwise.
     */
    val asOfWallMs: Long? = null,
)

internal data class DrainRow(
    val uid: Int,
    val label: String?,
    /** Of the window's total, 0 to 1; Derived. */
    val share: Double,
)

/** Top drains since unplugging (SPEC 12.4). */
internal data class DrainFacts(
    val rows: List<DrainRow>,
)

/** Health (SPEC 12.4). */
internal data class HealthFacts(
    /** SPEC 6.3's estimate; Estimated. */
    val capacityMah: Double,
    /** Its 95% interval: an estimate is shown with its range (PRODUCT.md). */
    val lowMah: Double,
    val highMah: Double,
    /** Against the design capacity; null without one. Estimated. */
    val healthPct: Double?,
    /** The platform's count where it has one (Measured), else Coulomb's own (Derived); null with neither. */
    val cycles: Double?,
    val cyclesMeasured: Boolean,
)

/** What the four widgets show. A null part has nothing to show yet. */
internal data class WidgetData(
    val level: LevelFacts? = null,
    val drains: DrainFacts? = null,
    val night: NightSummary? = null,
    val health: HealthFacts? = null,
)

/** The read models cut down to what a widget shows. Pure. */
internal object WidgetFacts {
    const val TOP_DRAINS = 3

    fun level(
        reading: BatteryReading,
        window: WindowDrains?,
        lite: Boolean = false,
        nowMs: Long = reading.battery.wallMs,
    ): LevelFacts {
        val battery = reading.battery
        val discharge = reading.discharge.takeUnless { battery.plugged }
        // The same input as Now's verdict (`NowSections`): both show the story's one line.
        val story =
            discharge?.let {
                DrainStory.compose(
                    StoryInput(
                        subject = StorySubject.SinceUnplugged,
                        span =
                            StorySpan(
                                fromWallMs = it.startWallMs,
                                toWallMs = battery.wallMs,
                                durationMs = battery.wallMs - it.startWallMs,
                                levelDropPct = it.startLevel - battery.levelPct,
                                ratePctPerHour = null,
                            ),
                        totalMah = window?.total ?: 0.0,
                        apps = window?.apps.orEmpty().map { app -> Contributor(app.uid, app.label, app.mah) },
                        deviceMah = window?.deviceMah ?: 0.0,
                        unattributedMah = window?.unattributedMah ?: 0.0,
                    ),
                )
            }
        val asOf = battery.wallMs.takeIf { lite || battery.isStale(nowMs) }
        return LevelFacts(battery.levelPct, PowerState.of(battery), story, asOf)
    }

    /** The three largest apps with their share of the window; null without a window or a drain in it. */
    fun drains(window: WindowDrains?): DrainFacts? {
        if (window == null || window.total <= 0) return null
        val rows =
            window.apps
                .sortedByDescending { it.mah }
                .take(TOP_DRAINS)
                .map { DrainRow(it.uid, it.label, window.share(it.mah)) }
        return DrainFacts(rows).takeIf { rows.isNotEmpty() }
    }

    fun health(report: HealthReport): HealthFacts? {
        val capacity = report.capacity ?: return null
        val platform = report.cycles.platformCount
        return HealthFacts(
            capacityMah = capacity.estimateMah,
            lowMah = capacity.ciLowMah,
            highMah = capacity.ciHighMah,
            healthPct = capacity.healthPct,
            cycles = platform?.toDouble() ?: report.cycles.coulombCount.takeIf { it > 0 },
            cyclesMeasured = platform != null,
        )
    }
}

/**
 * Whether a sentence fits a widget, since a launcher cuts what does not, and a cut may fall inside
 * a number (SPEC 12.4). The widths are estimates on the wide side: a sentence that might not fit
 * is left out rather than cut.
 */
internal object WidgetFit {
    /** A wide average for a 12 sp letter of the system face, in dp. */
    private const val CHAR_DP = 6.6f
    private const val LINE_DP = 16f

    /** The lines [text] may take in [widthDp] by [heightDp] at [fontScale], or null when it would not fit. */
    fun lines(
        text: String,
        widthDp: Float,
        heightDp: Float,
        fontScale: Float,
    ): Int? {
        val perLine = (widthDp / (CHAR_DP * fontScale)).toInt()
        val room = (heightDp / (LINE_DP * fontScale)).toInt()
        if (perLine <= 0 || room <= 0) return null
        // Lines break at words, so each loses up to a word's length: count a quarter more.
        val needed = (text.length * WRAP_LOSS / perLine).toInt() + 1
        return needed.takeIf { it <= room }
    }

    /** How many rows of [rowDp] fit in [heightDp], at most [max]. */
    fun rows(
        heightDp: Float,
        rowDp: Float,
        fontScale: Float,
        max: Int,
    ): Int = (heightDp / (rowDp * fontScale)).toInt().coerceIn(0, max)

    /** The width [text] takes on one line at [sp], on the wide side, in dp. */
    fun width(
        text: String,
        sp: Float,
        fontScale: Float,
    ): Float = text.length * sp * CHAR_EM * fontScale

    private const val WRAP_LOSS = 1.25f

    /** A wide average for a letter or digit of the system face, in em. */
    private const val CHAR_EM = 0.55f
}
