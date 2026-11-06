// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.parsing.dumpsys

import io.github.akane599.coulomb.core.model.dumpsys.CoolingReading
import io.github.akane599.coulomb.core.model.dumpsys.ThermalDump
import io.github.akane599.coulomb.core.model.dumpsys.ThermalReading
import io.github.akane599.coulomb.core.model.dumpsys.ThermalThreshold
import io.github.akane599.coulomb.core.model.parse.Parsed
import io.github.akane599.coulomb.core.parsing.ParseContext
import io.github.akane599.coulomb.core.parsing.io.Line
import io.github.akane599.coulomb.core.parsing.text.TextDump
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * `dumpsys thermalservice` (S19) into a [ThermalDump]. Never throws on bad input.
 *
 * The layout is `ThermalManagerService.dumpInternal` (frameworks/base
 * services/core/java/com/android/server/power/thermal/ThermalManagerService.java,
 * android-16.0.0_r4 L873-933, android-17.0.0_r1 L957-1026): headings at the start of a line, items
 * under them indented with a tab, each item an object's `toString`. Android 17 adds per-device
 * status lines for virtual devices, which are skipped.
 *
 * The dump is complete when the stream ended cleanly, every item was readable, the override, status
 * and HAL lines were present, and with the HAL ready its three sections were too.
 */
public object ThermalServiceParser {
    public const val SOURCE: String = "dumpsys.thermalservice"

    public fun parse(input: InputStream): Parsed<ThermalDump> {
        val ctx = ParseContext(SOURCE)
        val dump = TextDump(input, ctx, SOURCE)
        val walk = ThermalWalk(ctx)
        val clean = dump.forEachLine(walk::read)
        val complete = walk.finish(dump.bytesRead) && clean && dump.lostLines == 0
        return Parsed(walk.result(), ctx.report(dump.bytesRead, complete))
    }
}

private enum class Section { NONE, IGNORED, CACHED, CURRENT, COOLING, THRESHOLDS, HEADROOM }

private class ThermalWalk(
    private val ctx: ParseContext,
) {
    private var section = Section.NONE
    private var overridden: Boolean? = null
    private var status: Int? = null
    private var halReady: Boolean? = null
    private val cached = ArrayList<ThermalReading>()
    private val current = ArrayList<ThermalReading>()
    private val cooling = ArrayList<CoolingReading>()
    private val thresholds = ArrayList<ThermalThreshold>()
    private val seen = HashSet<Section>()

    /** An item could not be read, so a list is short. */
    private var lost = false

    fun read(line: Line) {
        val text = line.text
        when {
            text.isEmpty() -> {}

            text.startsWith('\t') -> {
                item(line)
            }

            section == Section.HEADROOM && text.startsWith('[') -> {}

            else -> {
                heading(line)
            }
        }
    }

    private fun heading(line: Line) {
        val text = line.text
        val colon = text.indexOf(": ")
        val name =
            when {
                colon > 0 -> text.substring(0, colon)
                text.endsWith(':') -> text.removeSuffix(":")
                else -> NO_KEY
            }
        val value = if (colon > 0) text.substring(colon + 2) else null
        section = HEADINGS[text] ?: if (text.startsWith(PER_DEVICE_LISTENERS)) Section.IGNORED else Section.NONE
        seen += section
        when {
            section != Section.NONE || text.startsWith(PER_DEVICE_STATUS) -> {}

            name == OVERRIDE -> {
                overridden = value?.toBooleanStrictOrNull() ?: unreadable(line, name)
            }

            name == STATUS -> {
                status = value?.toIntOrNull() ?: unreadable(line, name)
            }

            name == HAL_READY -> {
                halReady = value?.toBooleanStrictOrNull() ?: unreadable(line, name)
            }

            else -> {
                ctx.unknown(name.take(MAX_NAME))
            }
        }
    }

    private fun <T> unreadable(
        line: Line,
        name: String,
    ): T? {
        ctx.issue(line.offset, ThermalServiceParser.SOURCE, "unreadable $name")
        return null
    }

    private fun item(line: Line) {
        val text = line.text
        val read =
            when (section) {
                Section.CACHED -> temperature(text)?.also { cached += it } != null
                Section.CURRENT -> temperature(text)?.also { current += it } != null
                Section.COOLING -> coolingDevice(text)?.also { cooling += it } != null
                Section.THRESHOLDS -> threshold(text)?.also { thresholds += it } != null
                Section.NONE -> true.also { ctx.unknown(UNDER_UNKNOWN_HEADING) }
                Section.IGNORED, Section.HEADROOM -> true
            }
        if (!read) {
            lost = true
            ctx.issue(line.offset, ThermalServiceParser.SOURCE, "unreadable ${section.name.lowercase()} item")
        }
    }

    private fun temperature(text: String): ThermalReading? {
        val m = TEMPERATURE.matchEntire(text) ?: return null
        val (value, type, name) = m.destructured
        val celsius = value.toDoubleOrNull() ?: return null
        return ThermalReading(name, type.toInt(), deciC(celsius), status = m.groupValues.last().toInt())
    }

    private fun coolingDevice(text: String): CoolingReading? {
        val m = COOLING.matchEntire(text) ?: return null
        val (value, type, name) = m.destructured
        return CoolingReading(name, type.toInt(), value.toLongOrNull() ?: return null)
    }

    private fun threshold(text: String): ThermalThreshold? {
        val m = THRESHOLD.matchEntire(text) ?: return null
        val (type, name, hotText) = m.destructured
        val hot = floats(hotText) ?: return null
        val cold = floats(m.groupValues.last()) ?: return null
        return ThermalThreshold(name, type.toInt(), hot, cold)
    }

    /** `Arrays.toString(float[])` in deci-degrees, NaN as null; null when an entry is not a number. */
    private fun floats(text: String): List<Int?>? {
        if (text == NULL_ARRAY) return emptyList()
        val inner = text.substring(1, text.length - 1)
        if (inner.isEmpty()) return emptyList()
        val parts = inner.split(", ")
        val numbers = parts.mapNotNull { it.toDoubleOrNull() }
        return if (numbers.size == parts.size) numbers.map(::deciC) else null
    }

    /** Tenths of a degree; NaN and anything no sensor reads is null. */
    private fun deciC(celsius: Double): Int? = if (celsius.isNaN() || abs(celsius) > MAX_CELSIUS) null else (celsius * DECI).roundToInt()

    /** Records what is missing; true when nothing required is. */
    fun finish(offset: Long): Boolean {
        val missing =
            buildList {
                if (overridden == null) add(OVERRIDE)
                if (status == null) add(STATUS)
                if (halReady == null) add(HAL_READY)
                if (halReady == true) HAL_SECTIONS.filter { it.value !in seen }.forEach { add(it.key) }
            }
        missing.forEach { ctx.issue(offset, ThermalServiceParser.SOURCE, "no $it") }
        return missing.isEmpty() && !lost
    }

    fun result(): ThermalDump =
        ThermalDump(
            statusOverridden = overridden,
            status = status,
            halReady = halReady,
            cachedTemperatures = cached.toList(),
            currentTemperatures = current.toList(),
            coolingDevices = cooling.toList(),
            thresholds = thresholds.toList(),
        )

    private companion object {
        const val OVERRIDE = "IsStatusOverride"
        const val STATUS = "Thermal Status"
        const val HAL_READY = "HAL Ready"
        const val PER_DEVICE_LISTENERS = "ThermalStatusListenersPerDevice (DeviceId="
        const val PER_DEVICE_STATUS = "Thermal Status (DeviceId="
        const val UNDER_UNKNOWN_HEADING = "<item under an unknown heading>"
        const val NO_KEY = "<line without key>"
        const val NULL_ARRAY = "null"
        const val MAX_NAME = 80
        const val DECI = 10.0

        /** Far outside any sensor's range, and small enough that tenths fit in an Int. */
        const val MAX_CELSIUS = 10_000.0

        val HAL_SECTIONS =
            mapOf(
                "Current temperatures from HAL" to Section.CURRENT,
                "Current cooling devices from HAL" to Section.COOLING,
                "Temperature static thresholds from HAL" to Section.THRESHOLDS,
            )

        val HEADINGS: Map<String, Section> =
            HAL_SECTIONS.mapKeys { "${it.key}:" } +
                mapOf(
                    "ThermalEventListeners:" to Section.IGNORED,
                    "ThermalStatusListeners:" to Section.IGNORED,
                    "Cached temperatures:" to Section.CACHED,
                    "HAL connection:" to Section.IGNORED,
                    "Temperature headroom thresholds:" to Section.HEADROOM,
                )

        // The name is the only free text; it comes before the last field, so a greedy match keeps
        // a name with ", " in it whole.
        val TEMPERATURE = Regex("\tTemperature\\{mValue=([^,]*), mType=(-?[0-9]{1,9}), mName=(.*), mStatus=(-?[0-9]{1,9})\\}")
        val COOLING = Regex("\tCoolingDevice\\{mValue=(-?[0-9]{1,19}), mType=(-?[0-9]{1,9}), mName=(.*)\\}")
        val THRESHOLD =
            Regex(
                "\tTemperatureThreshold\\{mType=(-?[0-9]{1,9}), mName=(.*), " +
                    "mHotThrottlingThresholds=(null|\\[[^\\]]*\\]), mColdThrottlingThresholds=(null|\\[[^\\]]*\\])\\}",
            )
    }
}
