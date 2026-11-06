// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.parsing.dumpsys

import io.github.akane599.coulomb.core.model.dumpsys.BatteryServiceDump
import io.github.akane599.coulomb.core.model.parse.Parsed
import io.github.akane599.coulomb.core.parsing.ParseContext
import io.github.akane599.coulomb.core.parsing.io.Line
import io.github.akane599.coulomb.core.parsing.text.TextDump
import java.io.InputStream

/**
 * `dumpsys battery` (S21) into a [BatteryServiceDump]. Never throws on bad input.
 *
 * The AOSP lines (`BatteryService.dumpInternal`, frameworks/base
 * services/core/java/com/android/server/BatteryService.java android-16.0.0_r4 L1621-1658,
 * android-17.0.0_r1 L1628-1665) fill the typed fields; every other `key: value` line goes to
 * [BatteryServiceDump.extras] and its key is counted as unknown (SPEC 5.1). OEMs add log buffers,
 * a `[Name]` line followed by lines that start with a `MM-dd HH:mm:ss` time; those lines are
 * skipped, since they hold past events and values such as battery serials, and only the buffer's
 * name is counted.
 *
 * The dump is complete when the stream ended cleanly and every AOSP line was present and readable.
 */
public object BatteryServiceParser {
    public const val SOURCE: String = "dumpsys.battery"

    public fun parse(input: InputStream): Parsed<BatteryServiceDump> {
        val ctx = ParseContext(SOURCE)
        val dump = TextDump(input, ctx, SOURCE)
        val walk = BatteryServiceWalk(ctx)
        val clean = dump.forEachLine(walk::read)
        val complete = walk.finish(dump.bytesRead) && clean
        return Parsed(walk.result(), ctx.report(dump.bytesRead, complete))
    }
}

/** The AOSP keys, as printed after the two-space indent. */
private enum class Key(
    val text: String,
) {
    AC("AC powered"),
    USB("USB powered"),
    WIRELESS("Wireless powered"),
    DOCK("Dock powered"),
    MAX_CURRENT("Max charging current"),
    MAX_VOLTAGE("Max charging voltage"),
    CHARGE_COUNTER("Charge counter"),
    STATUS("status"),
    HEALTH("health"),
    PRESENT("present"),
    LEVEL("level"),
    SCALE("scale"),
    VOLTAGE("voltage"),
    TEMPERATURE("temperature"),
    TECHNOLOGY("technology"),
    CHARGING_STATE("Charging state"),
    CHARGING_POLICY("Charging policy"),
    CAPACITY_LEVEL("Capacity level"),
    ;

    companion object {
        val BY_TEXT: Map<String, Key> = entries.associateBy { it.text }
    }
}

private class BatteryServiceWalk(
    private val ctx: ParseContext,
) {
    private var sawHeader = false
    private var updatesStopped = false
    private val values = HashMap<Key, String>()
    private val extras = LinkedHashMap<String, String>()
    private var extrasFull = false

    fun read(line: Line) {
        val text = line.text
        val trimmed = text.trim()
        when {
            trimmed.isEmpty() || LOG_LINE.containsMatchIn(text) -> {}

            text == HEADER -> {
                sawHeader = true
            }

            trimmed == UPDATES_STOPPED -> {
                updatesStopped = true
            }

            trimmed.length > 2 && trimmed.first() == '[' && trimmed.last() == ']' -> {
                ctx.unknown(trimmed.take(MAX_KEY))
            }

            else -> {
                keyValue(line, trimmed)
            }
        }
    }

    private fun keyValue(
        line: Line,
        trimmed: String,
    ) {
        val colon = trimmed.indexOf(':')
        val key = if (colon > 0) WHITESPACE.replace(trimmed.substring(0, colon).trim(), " ") else ""
        if (key in IGNORED) return
        if (key.isEmpty() || key.length > MAX_KEY) {
            ctx.unknown(NO_KEY)
            return
        }
        val value = trimmed.substring(colon + 1).trim()
        val aosp = Key.BY_TEXT[key]
        when {
            value.length > MAX_VALUE -> ctx.issue(line.offset, BatteryServiceParser.SOURCE, "value of $key longer than $MAX_VALUE")
            aosp != null && aosp !in values -> values[aosp] = value
            key in extras || aosp != null -> ctx.issue(line.offset, BatteryServiceParser.SOURCE, "repeated key $key")
            extras.size < MAX_EXTRAS -> extra(key, value)
            !extrasFull -> tooManyExtras(line)
        }
    }

    private fun extra(
        key: String,
        value: String,
    ) {
        extras[key] = value
        ctx.unknown(key)
    }

    private fun tooManyExtras(line: Line) {
        extrasFull = true
        ctx.issue(line.offset, BatteryServiceParser.SOURCE, "more than $MAX_EXTRAS extra keys")
    }

    /** Records what is missing; true when the header and every AOSP line were read. */
    fun finish(offset: Long): Boolean {
        if (!sawHeader) ctx.issue(offset, BatteryServiceParser.SOURCE, "no battery service header")
        val missing = Key.entries.filter { it !in values }
        missing.forEach { ctx.issue(offset, BatteryServiceParser.SOURCE, "no ${it.text} line") }
        val unreadable = Key.entries.filter { it in values && !readable(it) }
        unreadable.forEach { ctx.issue(offset, BatteryServiceParser.SOURCE, "unreadable ${it.text}") }
        return sawHeader && missing.isEmpty() && unreadable.isEmpty()
    }

    private fun readable(key: Key): Boolean =
        when (key) {
            Key.TECHNOLOGY -> true
            Key.AC, Key.USB, Key.WIRELESS, Key.DOCK, Key.PRESENT -> bool(key) != null
            else -> int(key) != null
        }

    private fun int(key: Key): Int? = values[key]?.toIntOrNull()

    private fun bool(key: Key): Boolean? = values[key]?.toBooleanStrictOrNull()

    fun result(): BatteryServiceDump =
        BatteryServiceDump(
            updatesStopped = updatesStopped,
            acPowered = bool(Key.AC),
            usbPowered = bool(Key.USB),
            wirelessPowered = bool(Key.WIRELESS),
            dockPowered = bool(Key.DOCK),
            maxChargingCurrentUa = int(Key.MAX_CURRENT),
            maxChargingVoltageUv = int(Key.MAX_VOLTAGE),
            chargeCounterUah = int(Key.CHARGE_COUNTER),
            status = int(Key.STATUS),
            health = int(Key.HEALTH),
            present = bool(Key.PRESENT),
            level = int(Key.LEVEL),
            scale = int(Key.SCALE),
            voltageMv = int(Key.VOLTAGE),
            temperatureDeciC = int(Key.TEMPERATURE),
            technology = values[Key.TECHNOLOGY],
            chargingState = int(Key.CHARGING_STATE),
            chargingPolicy = int(Key.CHARGING_POLICY),
            capacityLevel = int(Key.CAPACITY_LEVEL),
            extras = extras.toMap(),
        )

    private companion object {
        const val HEADER = "Current Battery Service state:"
        const val UPDATES_STOPPED = "(UPDATES STOPPED -- use 'reset' to restart)"

        /** AOSP lines with no field: when the last broadcast went out, relative to the dump. */
        val IGNORED =
            setOf(
                "Time when the latest updated value of the Max charging current was sent via battery changed broadcast",
                "Time when the latest updated value of the voltage was sent via battery changed broadcast",
                "The last voltage value sent via the battery changed broadcast",
            )

        /** A log buffer entry: `11-05 19:28:13.897  message`. */
        val LOG_LINE = Regex("^\\s*[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}")
        val WHITESPACE = Regex("\\s+")

        /** Counted in place of a line's text when the line has no usable key. */
        const val NO_KEY = "<line without key>"
        const val MAX_KEY = 80
        const val MAX_VALUE = 256

        /** The S25 Ultra prints about 60. */
        const val MAX_EXTRAS = 256
    }
}
