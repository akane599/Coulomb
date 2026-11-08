// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.ui.format

import android.content.res.Configuration
import android.content.res.Resources
import android.os.Process
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import io.github.akane599.coulomb.core.ui.R
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** A formatted number split around its digits, so the parts can be set in different styles. */
@Immutable
public data class Affixed(
    val before: String,
    val number: String,
    val after: String,
)

/** A figure and its unit, set apart (DESIGN.md: units in Label beside the figure). */
@Immutable
public data class Figure(
    val number: String,
    val unit: String,
) {
    /** The figure as one unbreakable string: "410 mA". */
    public fun joined(): String = "$number\u00A0$unit"
}

/**
 * Locale-aware figures with SPEC 6.7 rounding. Turkish takes a decimal comma and a leading percent
 * sign ("%74", "0,8 %/sa"). Not thread-safe: use it in composition only.
 */
@Stable
@Suppress("TooManyFunctions") // One formatter per SPEC 6.7 unit, sharing one locale setup.
public class Formats(
    private val resources: Resources,
    public val locale: Locale,
    clockPattern: String,
    secondsPattern: String,
    hourPattern: String = clockPattern,
    dayClockPattern: String = "EEE $clockPattern",
    weekdayPattern: String = "EEE",
    dayPattern: String = "MMM d",
) {
    private val integer = NumberFormat.getIntegerInstance(locale)
    private val oneDecimal =
        NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }
    private val twoDecimals =
        NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }
    private val axisAmps = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 2 }
    private val percent = NumberFormat.getPercentInstance(locale).apply { maximumFractionDigits = 0 }
    private val clock = DateTimeFormatter.ofPattern(clockPattern, locale)
    private val clockSeconds = DateTimeFormatter.ofPattern(secondsPattern, locale)
    private val hour = DateTimeFormatter.ofPattern(hourPattern, locale)
    private val dayClock = DateTimeFormatter.ofPattern(dayClockPattern, locale)
    private val weekday = DateTimeFormatter.ofPattern(weekdayPattern, locale)
    private val day = DateTimeFormatter.ofPattern(dayPattern, locale)

    /** A whole number with the locale's grouping ("1,240"; Turkish "1.240"). */
    public fun integer(value: Long): String = integer.format(value)

    /** A percentage of whole points, such as the level: "74%"; Turkish "%74". */
    public fun percent(points: Int): String = percent.format(points / PERCENT)

    /** [percent] split around its digits, for the hero numeral. */
    public fun percentParts(points: Int): Affixed = split(percent(points))

    /** A share from 0 to 1 as a whole percentage; a share above zero never shows as 0%. */
    public fun share(fraction: Double): String {
        val points = (fraction * PERCENT).roundToLong()
        return if (fraction > 0 && points == 0L) {
            isolated(resources.getString(R.string.format_less_than, percent(1)))
        } else {
            percent(points.toInt())
        }
    }

    /** A rate to 0.1 %/h, without its unit. */
    public fun rate(pctPerHour: Double): String = oneDecimal.format(Rounding.rate(pctPerHour))

    public val rateUnit: String get() = resources.getString(R.string.unit_pct_per_hour)

    /** An amount of charge: whole mAh, or one decimal under 10 mAh. */
    public fun mah(value: Double): Figure =
        Figure(
            number = if (abs(value) < SMALL_MAH) oneDecimal.format(value) else integer.format(value.roundToLong()),
            unit = resources.getString(R.string.unit_mah),
        )

    /** An amount without its unit, such as a rate: whole, or one decimal under 10 ("36", "4.5"). */
    public fun amount(value: Double): String = if (abs(value) < SMALL_MAH) oneDecimal.format(value) else integer.format(value.roundToLong())

    /** [amount] of a change, with a true sign: "+24", "−3.5". A change that rounds to zero takes none. */
    public fun signedAmount(value: Double): String {
        val size = amount(abs(value))
        return when {
            size == amount(0.0) -> size
            value > 0 -> isolated("+$size")
            else -> isolated("$MINUS$size")
        }
    }

    /** A change as a signed whole percentage: "+39%", "−12%"; Turkish "+%39". Zero takes no sign. */
    public fun signedShare(fraction: Double): String {
        val points = (abs(fraction) * PERCENT).roundToLong().toInt()
        return when {
            points == 0 -> percent(0)
            fraction > 0 -> isolated("+${percent(points)}")
            else -> isolated("$MINUS${percent(points)}")
        }
    }

    /** A current's size (SPEC 6.7): "410 mA" below 1 A, "1.24 A" above. The state names its direction. */
    public fun current(ma: Double): Figure {
        val rounded = abs(Rounding.currentMa(ma))
        return if (rounded < MA_PER_A) {
            Figure(integer.format(rounded.roundToLong()), resources.getString(R.string.unit_ma))
        } else {
            Figure(twoDecimals.format(rounded / MA_PER_A), resources.getString(R.string.unit_a))
        }
    }

    /** A signed current (SPEC 6.7) with a true minus sign: "−410 mA", "−1.24 A". */
    public fun signedCurrent(ma: Double): Figure {
        val size = current(ma)
        return if (Rounding.currentMa(ma) < 0) size.copy(number = isolated("$MINUS${size.number}")) else size
    }

    /**
     * A signed axis tick, number only, with a true minus sign: in A when [inAmps] ("−1.5"), else in mA
     * ("−600"). One axis keeps one unit, named on its zero tick.
     */
    public fun signedAxis(
        ma: Double,
        inAmps: Boolean,
    ): String {
        val magnitude = if (inAmps) axisAmps.format(abs(ma) / MA_PER_A) else integer.format(abs(ma).roundToLong())
        return if (ma < 0 && magnitude != integer.format(0)) isolated("$MINUS$magnitude") else magnitude
    }

    /** A duration of whole minutes: "5 h 50 min", "50 min"; Turkish "5 sa 50 dk". */
    public fun duration(minutes: Long): String {
        val hours = minutes / MINUTES_PER_HOUR
        val rest = minutes % MINUTES_PER_HOUR
        return when {
            hours == 0L -> resources.getString(R.string.format_minutes, integer.format(rest))
            rest == 0L -> resources.getString(R.string.format_hours, integer.format(hours))
            else -> resources.getString(R.string.format_hours_minutes, integer.format(hours), integer.format(rest))
        }
    }

    /**
     * A held time, such as a wakelock's (SPEC 6.7: times as h and min): "<1 min" under a minute,
     * else [duration] to the nearest minute.
     */
    public fun held(ms: Long): String =
        if (ms < MS_PER_MINUTE) {
            resources.getString(R.string.format_less_than, duration(1))
        } else {
            duration((ms + MS_PER_MINUTE / 2) / MS_PER_MINUTE)
        }

    /** A day, or whole days, without the year: "Nov 1", "Nov 1 – Nov 7". */
    public fun days(
        first: LocalDate,
        last: LocalDate,
    ): String = if (first == last) day.format(first) else range(day.format(first), day.format(last))

    /** [duration] for a span in hours, to the minute. */
    public fun hours(hours: Double): String = duration(Rounding.minutes(hours))

    /** The ends of a time-left range, rounded outward ([Rounding.timeLeftMinutes]). */
    public fun timeLeft(
        shortestHours: Double,
        longestHours: Double,
    ): Pair<String, String> {
        val (shortest, longest) = Rounding.timeLeftMinutes(shortestHours, longestHours)
        return duration(shortest) to duration(longest)
    }

    /** Two ends as a range: "5 h 50 min – 7 h 20 min". */
    public fun range(
        from: String,
        to: String,
    ): String = resources.getString(R.string.format_range, from, to)

    /** A clock time in the user's 12- or 24-hour setting. */
    public fun clock(
        wallMs: Long,
        zone: ZoneId,
    ): String = clock.format(Instant.ofEpochMilli(wallMs).atZone(zone))

    /**
     * [clock] that names the weekday when [wallMs] falls on another day than [nowMs]: "9:30 PM" today,
     * "Thu 1:20 AM" otherwise, so a time a day or more away never reads as today's.
     */
    public fun clock(
        wallMs: Long,
        zone: ZoneId,
        nowMs: Long,
    ): String {
        val time = Instant.ofEpochMilli(wallMs).atZone(zone)
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        return (if (time.toLocalDate() == today) clock else dayClock).format(time)
    }

    /**
     * An axis tick on the hour: "12 PM" in the 12-hour setting, "12:00" in the 24-hour one. Midnight
     * takes the weekday instead ("Thu"), so an axis longer than a day says where each day starts.
     */
    public fun hourTick(
        wallMs: Long,
        zone: ZoneId,
    ): String {
        val time = Instant.ofEpochMilli(wallMs).atZone(zone)
        return if (time.hour == 0 && time.minute == 0) weekday.format(time) else hour.format(time)
    }

    /** [clock] with seconds, for readings a few seconds apart. */
    public fun clockWithSeconds(
        wallMs: Long,
        zone: ZoneId,
    ): String = clockSeconds.format(Instant.ofEpochMilli(wallMs).atZone(zone))

    /** An app's name, or a name for its UID while it has none (SPEC 6.5). */
    public fun appName(
        label: String?,
        uid: Int,
    ): String =
        label ?: when (uid) {
            Process.ROOT_UID -> resources.getString(R.string.uid_kernel)
            Process.SYSTEM_UID -> resources.getString(R.string.uid_android_system)
            else -> resources.getString(R.string.uid_unknown, uid)
        }

    internal companion object {
        private const val PERCENT = 100.0
        private const val SMALL_MAH = 10.0
        private const val MA_PER_A = 1_000.0
        private const val MINUTES_PER_HOUR = 60L
        private const val MS_PER_MINUTE = 60_000L
        private const val MINUS = '−'

        /**
         * [text] as a left-to-right isolate (U+2066 … U+2069), so a sign or "<" stays before its
         * number in a right-to-left paragraph instead of moving to its far side.
         */
        internal fun isolated(text: String): String = "\u2066$text\u2069"

        /** Splits [text] around its run of digits and separators. */
        internal fun split(text: String): Affixed {
            val first = text.indexOfFirst { it.isDigit() }
            val last = text.indexOfLast { it.isDigit() }
            if (first < 0) return Affixed("", text, "")
            return Affixed(text.substring(0, first), text.substring(first, last + 1), text.substring(last + 1))
        }
    }
}

/**
 * The user's first locale in the language the app's strings resolved to, keeping its region
 * ("en-GB", "tr-TR"), or that language alone when the user lists no such locale: an Arabic system
 * with no Arabic strings shows English words, so it gets "74%", not "٪٧٤". Android under-measures a
 * line that mixes Arabic-Indic digits with Latin words in a right-to-left layout and breaks it
 * mid-word, and a figure in another script than its sentence reads poorly anyway.
 */
internal fun stringsLocale(
    configuration: Configuration,
    resources: Resources,
): Locale {
    val language = Locale.forLanguageTag(resources.getString(R.string.format_language))
    val locales = configuration.locales
    return (0 until locales.size()).map { locales[it] }.firstOrNull { it.language == language.language } ?: language
}

/** [Formats] for the current configuration, remembered across recompositions. */
@Composable
public fun rememberFormats(): Formats {
    val configuration = LocalConfiguration.current
    val resources = LocalResources.current
    val context = LocalContext.current
    val locale = stringsLocale(configuration, resources)
    val is24Hour = DateFormat.is24HourFormat(context)
    return remember(configuration, resources, locale, is24Hour) {
        val hours = if (is24Hour) "H" else "h"
        Formats(
            resources = resources,
            locale = locale,
            clockPattern = DateFormat.getBestDateTimePattern(locale, "${hours}m"),
            secondsPattern = DateFormat.getBestDateTimePattern(locale, "${hours}ms"),
            // A tick needs no minutes in the 12-hour setting ("12 PM"); 24-hour keeps "12:00".
            hourPattern = DateFormat.getBestDateTimePattern(locale, if (is24Hour) "Hm" else "h"),
            dayClockPattern = DateFormat.getBestDateTimePattern(locale, "EEE${hours}m"),
            weekdayPattern = DateFormat.getBestDateTimePattern(locale, "EEE"),
            dayPattern = DateFormat.getBestDateTimePattern(locale, "MMMd"),
        )
    }
}
