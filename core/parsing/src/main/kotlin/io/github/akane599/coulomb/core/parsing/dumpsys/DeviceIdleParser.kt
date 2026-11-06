// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.parsing.dumpsys

import io.github.akane599.coulomb.core.model.dumpsys.DeepDozeState
import io.github.akane599.coulomb.core.model.dumpsys.DeviceIdleDump
import io.github.akane599.coulomb.core.model.dumpsys.DozeAllowlist
import io.github.akane599.coulomb.core.model.dumpsys.DozeEvent
import io.github.akane599.coulomb.core.model.dumpsys.DozeEventKind
import io.github.akane599.coulomb.core.model.dumpsys.LightDozeState
import io.github.akane599.coulomb.core.model.parse.Parsed
import io.github.akane599.coulomb.core.parsing.ParseContext
import io.github.akane599.coulomb.core.parsing.io.Line
import io.github.akane599.coulomb.core.parsing.text.Durations
import io.github.akane599.coulomb.core.parsing.text.TextDump
import java.io.InputStream

/**
 * `dumpsys deviceidle` (S11) into a [DeviceIdleDump]. Never throws on bad input.
 *
 * The layout is `DeviceIdleController.dump` (frameworks/base
 * apex/jobscheduler/service/java/com/android/server/DeviceIdleController.java, android-16.0.0_r4
 * L5228-5495, android-17.0.0_r1 L5314-5579): headings and `name=value` lines indented by two
 * spaces, a heading's items by four. Settings keys outside the AOSP list, other OEM headings and
 * other names are counted as unknown by name. Lines that hold places (`mLastGpsLocation`) or other
 * values Coulomb has no use for are skipped without being kept.
 *
 * The dump is complete when the stream ended cleanly, every line was readable, and the settings
 * and the `mState` line, which comes near the end, were present.
 */
public object DeviceIdleParser {
    public const val SOURCE: String = "dumpsys.deviceidle"

    public fun parse(input: InputStream): Parsed<DeviceIdleDump> {
        val ctx = ParseContext(SOURCE)
        val dump = TextDump(input, ctx, SOURCE)
        val walk = DeviceIdleWalk(ctx)
        val clean = dump.forEachLine(walk::read)
        val complete = walk.finish(dump.bytesRead) && clean && dump.lostLines == 0
        return Parsed(walk.result(), ctx.report(dump.bytesRead, complete))
    }
}

private enum class IdleSection {
    NONE,
    IGNORED,
    UNKNOWN,
    SETTINGS,
    HISTORY,
    SYSTEM_EXCEPT_IDLE,
    SYSTEM,
    REMOVED_SYSTEM,
    USER,
    APP_IDS_EXCEPT_IDLE,
    USER_APP_IDS,
    ALL_APP_IDS,
    TEMP_APP_IDS,
}

private class DeviceIdleWalk(
    private val ctx: ParseContext,
) {
    private var section = IdleSection.NONE
    private val settings = LinkedHashMap<String, String>()
    private val values = HashMap<String, String>()
    private val history = ArrayList<DozeEvent>()
    private val packages = HashMap<IdleSection, MutableList<String>>()
    private val appIds = HashMap<IdleSection, MutableList<Int>>()
    private var lost = false

    fun read(line: Line) {
        val text = line.text
        when {
            text.isBlank() -> {}

            text.startsWith(ITEM_INDENT) -> {
                item(line, text.trim())
            }

            text.startsWith(TOP_INDENT) -> {
                top(text.substring(TOP_INDENT.length))
            }

            else -> {
                ctx.unknown(NO_KEY)
            }
        }
    }

    private fun top(text: String) {
        val heading = if (text.endsWith(':') && '=' !in text) text.dropLast(1) else null
        when {
            heading != null -> {
                section = HEADINGS[heading] ?: IdleSection.UNKNOWN.also { ctx.unknown(heading.take(MAX_NAME)) }
            }

            text == CONSTRAINTS_OPEN -> {
                section = IdleSection.IGNORED
            }

            '=' in text -> {
                section = IdleSection.NONE
                values(text)
            }

            else -> {
                other(text)
            }
        }
    }

    /** `name=value` pairs; a pair starts the line or follows a space. */
    private fun values(text: String) {
        for (pair in PAIR.findAll(text)) {
            val name = pair.groupValues[1]
            when {
                name in WANTED -> values.putIfAbsent(name, pair.groupValues[2])
                name !in KNOWN_NAMES -> ctx.unknown(name.take(MAX_NAME))
            }
            // These values hold spaces and `=` of their own: a sensor's or a place's description.
            if (name in REST_OF_LINE) break
        }
    }

    /** A line without `=`: a known AOSP line, an OEM `name: value` line, or a line of an OEM section. */
    private fun other(text: String) {
        val colon = text.indexOf(": ")
        when {
            text in KNOWN_LINES || text.endsWith(STATIONARY_LISTENERS) -> section = IdleSection.NONE
            colon > 0 -> ctx.unknown(text.substring(0, colon).trim().take(MAX_NAME))
            section != IdleSection.UNKNOWN -> ctx.unknown(NO_KEY)
        }
    }

    private fun item(
        line: Line,
        text: String,
    ) {
        val ok =
            when (section) {
                IdleSection.SETTINGS -> setting(text)
                IdleSection.HISTORY -> event(text)
                in PACKAGE_SECTIONS -> true.also { packages.getOrPut(section) { ArrayList() } += text.take(MAX_PACKAGE) }
                in APP_ID_SECTIONS -> text.toIntOrNull()?.also { appIds.getOrPut(section) { ArrayList() } += it } != null
                IdleSection.NONE -> true.also { ctx.unknown(UNDER_NO_HEADING) }
                else -> true
            }
        if (!ok) {
            lost = true
            ctx.issue(line.offset, DeviceIdleParser.SOURCE, "unreadable ${section.name.lowercase()} item")
        }
    }

    private fun setting(text: String): Boolean {
        val eq = text.indexOf('=')
        if (eq <= 0) return false
        val key = text.substring(0, eq)
        if (key !in AOSP_SETTINGS) ctx.unknown("settings.${key.take(MAX_NAME)}")
        settings.putIfAbsent(key, text.substring(eq + 1))
        return true
    }

    /** `light-idle: -11h45m51s810ms (unlocked)`. */
    private fun event(text: String): Boolean {
        val m = EVENT.matchEntire(text) ?: return false
        val (label, time, reason) = m.destructured
        val kind = EVENT_KINDS[label] ?: return false.also { ctx.unknown("event.${label.take(MAX_NAME)}") }
        val ms = if (time == NO_TIME) null else Durations.parseMs(time) ?: return false
        history += DozeEvent(kind, ms?.let { -it }, reason.ifEmpty { null }?.take(MAX_REASON))
        return true
    }

    /** Records what is missing; true when nothing required is. */
    fun finish(offset: Long): Boolean {
        val missing =
            buildList {
                if (settings.isEmpty()) add("Settings")
                if (STATE !in values || LIGHT_STATE !in values) add("mState line")
            }
        missing.forEach { ctx.issue(offset, DeviceIdleParser.SOURCE, "no $it") }
        val deep = values[STATE]
        val light = values[LIGHT_STATE]
        if (deep != null && deepState() == null) ctx.unknown("mState.${deep.take(MAX_NAME)}")
        if (light != null && lightState() == null) ctx.unknown("mLightState.${light.take(MAX_NAME)}")
        val unreadable = BOOLEANS.filter { it in values && bool(it) == null }
        unreadable.forEach { ctx.issue(offset, DeviceIdleParser.SOURCE, "unreadable $it") }
        return missing.isEmpty() && unreadable.isEmpty() && !lost
    }

    private fun deepState() = DeepDozeState.entries.firstOrNull { it.name == values[STATE] }

    private fun lightState() = LightDozeState.entries.firstOrNull { it.name == values[LIGHT_STATE] }

    private fun bool(name: String) = values[name]?.toBooleanStrictOrNull()

    fun result(): DeviceIdleDump =
        DeviceIdleDump(
            settings = settings.toMap(),
            deepState = deepState(),
            lightState = lightState(),
            lightEnabled = bool("mLightEnabled"),
            deepEnabled = bool("mDeepEnabled"),
            forceIdle = bool("mForceIdle"),
            screenOn = bool("mScreenOn"),
            screenLocked = bool("mScreenLocked"),
            charging = bool("mCharging"),
            networkConnected = bool("mNetworkConnected"),
            useMotionSensor = bool("mUseMotionSensor"),
            motionActive = bool("mMotionActive"),
            notMoving = bool("mNotMoving"),
            idlingHistory = history.toList(),
            allowlist =
                DozeAllowlist(
                    systemAppsExceptIdle = packages(IdleSection.SYSTEM_EXCEPT_IDLE),
                    systemApps = packages(IdleSection.SYSTEM),
                    removedSystemApps = packages(IdleSection.REMOVED_SYSTEM),
                    userApps = packages(IdleSection.USER),
                    appIdsExceptIdle = appIds(IdleSection.APP_IDS_EXCEPT_IDLE),
                    userAppIds = appIds(IdleSection.USER_APP_IDS),
                    allAppIds = appIds(IdleSection.ALL_APP_IDS),
                    tempAppIds = appIds(IdleSection.TEMP_APP_IDS),
                ),
        )

    private fun packages(section: IdleSection) = packages[section]?.toList().orEmpty()

    private fun appIds(section: IdleSection) = appIds[section]?.toList().orEmpty()

    private companion object {
        const val TOP_INDENT = "  "
        const val ITEM_INDENT = "    "
        const val CONSTRAINTS_OPEN = "mConstraints={"
        const val STATIONARY_LISTENERS = " stationary listeners registered"
        const val MOTION_SENSOR = "mMotionSensor"
        val REST_OF_LINE = setOf(MOTION_SENSOR, "mLastGenericLocation", "mLastGpsLocation")
        const val STATE = "mState"
        const val LIGHT_STATE = "mLightState"
        const val NO_TIME = "--"
        const val NO_KEY = "<line without key>"
        const val UNDER_NO_HEADING = "<item under no heading>"
        const val MAX_NAME = 80
        const val MAX_PACKAGE = 255
        const val MAX_REASON = 80

        val HEADINGS: Map<String, IdleSection> =
            mapOf(
                "Flags" to IdleSection.IGNORED,
                "Settings" to IdleSection.SETTINGS,
                "Idling history" to IdleSection.HISTORY,
                "Whitelist (except idle) system apps" to IdleSection.SYSTEM_EXCEPT_IDLE,
                "Whitelist system apps" to IdleSection.SYSTEM,
                "Removed from whitelist system apps" to IdleSection.REMOVED_SYSTEM,
                "Whitelist user apps" to IdleSection.USER,
                "Whitelist (except idle) all app ids" to IdleSection.APP_IDS_EXCEPT_IDLE,
                "Whitelist user app ids" to IdleSection.USER_APP_IDS,
                "Whitelist all app ids" to IdleSection.ALL_APP_IDS,
                "Temp whitelist schedule" to IdleSection.IGNORED,
                "Temp whitelist app ids" to IdleSection.TEMP_APP_IDS,
            )

        val PACKAGE_SECTIONS = setOf(IdleSection.SYSTEM_EXCEPT_IDLE, IdleSection.SYSTEM, IdleSection.REMOVED_SYSTEM, IdleSection.USER)
        val APP_ID_SECTIONS =
            setOf(IdleSection.APP_IDS_EXCEPT_IDLE, IdleSection.USER_APP_IDS, IdleSection.ALL_APP_IDS, IdleSection.TEMP_APP_IDS)

        val BOOLEANS =
            listOf(
                "mLightEnabled",
                "mDeepEnabled",
                "mForceIdle",
                "mScreenOn",
                "mScreenLocked",
                "mCharging",
                "mNetworkConnected",
                "mUseMotionSensor",
                "mMotionActive",
                "mNotMoving",
            )
        val WANTED = BOOLEANS.toSet() + setOf(STATE, LIGHT_STATE)

        /** The other names the AOSP dump prints at the top level. */
        val KNOWN_NAMES =
            setOf(
                "activeEmergencyCall",
                MOTION_SENSOR,
                "mMotionListener.activatedTimeElapsed",
                "mLastMotionEventElapsed",
                "mLocating",
                "mHasGps",
                "mHasFused",
                "mLocated",
                "mLastGenericLocation",
                "mLastGpsLocation",
                "mInactiveTimeout",
                "mActiveIdleOpCount",
                "mNextAlarmTime",
                "mNextIdlePendingDelay",
                "mNextIdleDelay",
                "mNextLightIdleDelay",
                "mNextLightAlarmTime",
                "mCurLightIdleBudget",
                "mMaintenanceStartTime",
                "mJobsActive",
                "mAlarmsActive",
                "mModeManagerRequestedQuickDoze",
                "mIsOffBody",
                "mIsDesktopClosedState",
            )
        val KNOWN_LINES = setOf("Location prefetching disabled", "}")

        val PAIR = Regex("(?:^|\\s)([A-Za-z][A-Za-z0-9_.]*)=(\\S*)")
        val EVENT = Regex("([a-z?-]+): (\\S+)(?: \\((.*)\\))?")
        val EVENT_KINDS =
            mapOf(
                "normal" to DozeEventKind.NORMAL,
                "light-idle" to DozeEventKind.LIGHT_IDLE,
                "light-maint" to DozeEventKind.LIGHT_MAINTENANCE,
                "deep-idle" to DozeEventKind.DEEP_IDLE,
                "deep-maint" to DozeEventKind.DEEP_MAINTENANCE,
            )

        /** `Constants.dump` keys, the same in android-16.0.0_r4 and android-17.0.0_r1. */
        val AOSP_SETTINGS =
            setOf(
                "flex_time_short",
                "light_after_inactive_to",
                "light_idle_to",
                "light_idle_to_initial_flex",
                "light_max_idle_to_flex",
                "light_idle_factor",
                "light_idle_increase_linearly",
                "light_idle_linear_increase_factor_ms",
                "light_idle_flex_linear_increase_factor_ms",
                "light_max_idle_to",
                "light_idle_maintenance_min_budget",
                "light_idle_maintenance_max_budget",
                "min_light_maintenance_time",
                "min_deep_maintenance_time",
                "inactive_to",
                "sensing_to",
                "locating_to",
                "location_accuracy",
                "motion_inactive_to",
                "motion_inactive_to_flex",
                "idle_after_inactive_to",
                "idle_pending_to",
                "max_idle_pending_to",
                "idle_pending_factor",
                "quick_doze_delay_to",
                "idle_to",
                "max_idle_to",
                "idle_factor",
                "min_time_to_alarm",
                "max_temp_app_allowlist_duration_ms",
                "mms_temp_app_allowlist_duration_ms",
                "sms_temp_app_allowlist_duration_ms",
                "notification_allowlist_duration_ms",
                "wait_for_unlock",
                "use_window_alarms",
                "use_mode_manager",
            )
    }
}
