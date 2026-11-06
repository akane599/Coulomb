// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.parsing.dumpsys

import io.github.akane599.coulomb.core.model.dumpsys.BatterySaverState
import io.github.akane599.coulomb.core.model.dumpsys.HeldWakeLock
import io.github.akane599.coulomb.core.model.dumpsys.PowerDump
import io.github.akane599.coulomb.core.model.dumpsys.WakeLockLevel
import io.github.akane599.coulomb.core.model.parse.Parsed
import io.github.akane599.coulomb.core.parsing.ParseContext
import io.github.akane599.coulomb.core.parsing.io.Line
import io.github.akane599.coulomb.core.parsing.text.Durations
import io.github.akane599.coulomb.core.parsing.text.TextDump
import java.io.InputStream

/**
 * `dumpsys power` (S12) into a [PowerDump]: the `Power Manager State:` lines, `Wake Locks:` and
 * `Battery saver state machine:` (frameworks/base services/core/java/com/android/server/power/
 * PowerManagerService.java `dumpInternal`, android-16.0.0_r4 L4948-5196, android-17.0.0_r1
 * L5168-5419). The many other sections, some of them OEM-only, are skipped. Never throws on bad
 * input.
 *
 * Wakelock tags are app text and are not escaped, so a tag with line breaks can print lines of its
 * own, headings included. The list is kept only when the lines between `Wake Locks: size=N` and
 * `Suspend Blockers:` are exactly N readable entries and the blank line the platform prints, and
 * each of the two headings appears once in the dump; otherwise it is null and the dump incomplete.
 * Battery saver is read the same way: its heading or `Battery Saver: DISABLED` must appear once,
 * since One UI prints released wakelock tags before it. `Power Manager State:` comes straight after
 * the header, before any app text, so a later copy is reported and not read. A tag can end in `'`
 * or hold ` (uid=`, so an entry is read from both ends: the last ` (uid=` starts the platform's
 * own trailer (`WakeLock.toString`, android-16.0.0_r4 L5842-5876 and `getLockFlagsString`
 * L5906-5917, android-17.0.0_r1 L6085-6127 and L6157-6168), and the last quote before it closes
 * the tag.
 */
public object PowerParser {
    public const val SOURCE: String = "dumpsys.power"

    public fun parse(input: InputStream): Parsed<PowerDump> {
        val ctx = ParseContext(SOURCE)
        val dump = TextDump(input, ctx, SOURCE)
        val walk = PowerWalk(ctx)
        val clean = dump.forEachLine(walk::read)
        val complete = walk.finish(dump.bytesRead) && clean && dump.lostLines == 0
        return Parsed(walk.result(), ctx.report(dump.bytesRead, complete))
    }
}

private enum class PowerSection { OTHER, STATE, WAKE_LOCKS, SAVER }

private class PowerWalk(
    private val ctx: ParseContext,
) {
    private var section = PowerSection.OTHER
    private var sawHeader = false
    private val state = HashMap<String, String>()
    private val saver = HashMap<String, String>()
    private var saverUnsupported = false
    private var stateHeadings = 0
    private var saverHeadings = 0
    private var wakeLockHeadings = 0
    private var suspendBlockerHeadings = 0
    private var declaredWakeLocks: Int? = null
    private val wakeLockLines = ArrayList<String>()
    private var wakeLockOverflow = false
    private var wakeLocks: List<HeldWakeLock>? = null

    fun read(line: Line) {
        val text = line.text
        val top = text.isNotEmpty() && !text[0].isWhitespace()
        when {
            text.startsWith(SUSPEND_BLOCKERS) -> {
                suspendBlockerHeadings++
                section = PowerSection.OTHER
            }

            // Until Suspend Blockers, every line counts against the declared size.
            section == PowerSection.WAKE_LOCKS -> {
                wakeLockLine(text)
            }

            top -> {
                heading(text)
            }

            section == PowerSection.STATE -> {
                stateLine(text)
            }

            section == PowerSection.SAVER -> {
                saverLine(text)
            }
        }
    }

    private fun heading(text: String) {
        section = PowerSection.OTHER
        when {
            text == HEADER -> sawHeader = true
            text == STATE_HEADING -> if (++stateHeadings == 1) section = PowerSection.STATE
            text == SAVER_HEADING -> if (++saverHeadings == 1) section = PowerSection.SAVER
            text == SAVER_UNSUPPORTED -> if (++saverHeadings == 1) saverUnsupported = true
            text.startsWith(WAKE_LOCKS) -> if (++wakeLockHeadings == 1) wakeLocksHeading(text)
        }
    }

    /** Keeps no more than the declared entries and the blank line, so a missing end cannot grow it. */
    private fun wakeLockLine(text: String) {
        val size = declaredWakeLocks
        if (size != null && wakeLockLines.size <= size) wakeLockLines += text else wakeLockOverflow = true
    }

    private fun wakeLocksHeading(text: String) {
        declaredWakeLocks = text.substring(WAKE_LOCKS.length).toIntOrNull()
        section = PowerSection.WAKE_LOCKS
    }

    /** `  mName=value`, two spaces in; deeper lines belong to a sub-block. */
    private fun stateLine(text: String) {
        if (!text.startsWith("  ") || text.startsWith("   ")) return
        val eq = text.indexOf('=')
        if (eq < 0) return
        val name = text.substring(2, eq)
        if (!NAME.matches(name)) return ctx.unknown(NO_NAME)
        if (name !in STATE_NAMES) ctx.unknown(name)
        state.putIfAbsent(name, text.substring(eq + 1))
    }

    /** `name=value`, at any depth; `adaptive=true (advertise=true)` keeps its first word. */
    private fun saverLine(text: String) {
        val trimmed = text.trim()
        val eq = trimmed.indexOf('=')
        if (eq < 0) return
        val name = trimmed.substring(0, eq)
        if (!NAME.matches(name)) return ctx.unknown(NO_NAME)
        if (name !in SAVER_NAMES) ctx.unknown("saver.$name")
        saver.putIfAbsent(name, trimmed.substring(eq + 1).substringBefore(' '))
    }

    /** Records what is missing; true when nothing required is. */
    fun finish(offset: Long): Boolean {
        val problems = ArrayList<String>()
        if (!sawHeader) problems += "no power manager header"
        if (stateHeadings > 1) problems += "Power Manager State printed more than once"
        REQUIRED_STATE.filter { it !in state }.forEach { problems += "no $it line" }
        UNREADABLE_CHECKS.filter { (name, read) -> name in state && read(state.getValue(name)) == null }.forEach {
            problems += "unreadable ${it.first}"
        }
        when {
            saverHeadings > 1 -> problems += "battery saver printed more than once"
            !saverUnsupported && SAVER_ENABLED !in saver -> problems += "no battery saver state"
        }
        wakeLocks = wakeLocksIfTrusted()
        when {
            wakeLockHeadings == 0 -> problems += "no Wake Locks heading"
            wakeLockHeadings > 1 || suspendBlockerHeadings != 1 -> problems += "wake lock headings not printed once"
            wakeLocks == null -> problems += "wake lock lines do not match their count"
        }
        problems.forEach { ctx.issue(offset, PowerParser.SOURCE, it) }
        return problems.isEmpty()
    }

    private fun wakeLocksIfTrusted(): List<HeldWakeLock>? {
        val size = declaredWakeLocks
        val shaped =
            size != null &&
                !wakeLockOverflow &&
                wakeLockHeadings == 1 &&
                suspendBlockerHeadings == 1 &&
                wakeLockLines.size == size + 1 &&
                wakeLockLines.last().isEmpty()
        if (!shaped) return null
        val entries = wakeLockLines.dropLast(1).map(::wakeLock)
        return if (entries.all { it != null }) entries.filterNotNull() else null
    }

    private fun wakeLock(text: String): HeldWakeLock? {
        val trailerAt = text.lastIndexOf(TRAILER)
        if (!text.startsWith("  ") || !text.endsWith(")") || trailerAt < 0) return null
        val head = text.substring(2, trailerAt)
        val open = head.indexOf(" '")
        val close = head.lastIndexOf('\'')
        if (open < 0 || close < open + 2) return null
        val trailer = text.substring(trailerAt + 2, text.length - 1)
        return entry(head.substring(0, open).trimEnd(), head.substring(open + 2, close), head.substring(close + 1), trailer)
    }

    private fun entry(
        levelName: String,
        tag: String,
        flagText: String,
        trailer: String,
    ): HeldWakeLock? {
        val flags = FLAGS.matchEntire(flagText)?.groups
        val uid = UID.number(trailer)
        val acquired = flags?.get("acq")?.value
        val held = acquired?.let { Durations.parseMs(it) }
        val heldUnreadable = acquired != null && held == null
        if (flags == null || uid == null || heldUnreadable) return null
        val level = WakeLockLevel.entries.firstOrNull { it.platformName == levelName }
        if (level == null && levelName != UNKNOWN_LEVEL) ctx.unknown(if (NAME.matches(levelName)) "level.$levelName" else NO_NAME)
        return HeldWakeLock(
            level = level,
            tag = tag,
            uid = uid,
            pid = PID.number(trailer),
            heldUptimeMs = held?.let { -it },
            long = flags["long"] != null,
            disabled = flags["disabled"] != null,
            acquireCausesWakeup = flags["wakeup"] != null,
            onAfterRelease = flags["afterRelease"] != null,
            systemWakelock = flags["system"] != null,
            workSourceUid = WORK_SOURCE.number(trailer),
        )
    }

    /** The first number group that matched, as an Int. */
    private fun Regex.number(text: String): Int? =
        find(text)
            ?.groups
            ?.drop(1)
            ?.firstOrNull { it != null }
            ?.value
            ?.toIntOrNull()

    private fun bool(
        map: Map<String, String>,
        name: String,
    ) = map[name]?.toBooleanStrictOrNull()

    fun result(): PowerDump {
        val saverRead = saverHeadings == 1 && (saverUnsupported || SAVER_ENABLED in saver)
        return PowerDump(
            wakefulness = state[WAKEFULNESS]?.takeIf { it.isNotEmpty() },
            powered = bool(state, "mIsPowered"),
            plugType = state["mPlugType"]?.toIntOrNull(),
            batteryLevel = state["mBatteryLevel"]?.toIntOrNull(),
            deviceIdle = bool(state, "mDeviceIdleMode"),
            lightDeviceIdle = bool(state, "mLightDeviceIdleMode"),
            wakeLocks = wakeLocks,
            batterySaverSupported = if (saverRead) !saverUnsupported else null,
            batterySaver =
                if (!saverRead || saverUnsupported) {
                    null
                } else {
                    BatterySaverState(
                        enabled = bool(saver, SAVER_ENABLED),
                        fullEnabled = bool(saver, "full"),
                        adaptiveEnabled = bool(saver, "adaptive"),
                        settingEnabled = bool(saver, "mSettingBatterySaverEnabled"),
                        automaticTrigger = saver["mSettingAutomaticBatterySaver"]?.toIntOrNull(),
                        triggerLevel = saver["mSettingBatterySaverTriggerThreshold"]?.toIntOrNull(),
                    )
                },
        )
    }

    private companion object {
        const val HEADER = "POWER MANAGER (dumpsys power)"
        const val STATE_HEADING = "Power Manager State:"
        const val SAVER_HEADING = "Battery saver state machine:"
        const val SAVER_UNSUPPORTED = "Battery Saver: DISABLED"
        const val WAKE_LOCKS = "Wake Locks: size="
        const val SUSPEND_BLOCKERS = "Suspend Blockers: size="
        const val TRAILER = " (uid="
        const val UNKNOWN_LEVEL = "???"
        const val WAKEFULNESS = "mWakefulness"
        const val SAVER_ENABLED = "Enabled"
        const val NO_NAME = "<line without key>"

        val NAME = Regex("[A-Za-z][A-Za-z0-9_]{0,79}")
        val FLAGS =
            Regex(
                "(?<wakeup> ACQUIRE_CAUSES_WAKEUP)?(?<afterRelease> ON_AFTER_RELEASE)?(?<system> SYSTEM_WAKELOCK)?" +
                    "(?<disabled> DISABLED)?(?: ACQ=(?<acq>\\S+))?(?<long> LONG)?",
            )
        val UID = Regex("^uid=(-?[0-9]{1,10})(?: |$)")
        val PID = Regex("(?:^| )pid=([0-9]{1,10})(?: |$)")
        val WORK_SOURCE = Regex(" ws=WorkSource\\{(?:([0-9]{1,10})| chains=WorkChain\\{\\(([0-9]{1,10}))")

        val REQUIRED_STATE = listOf(WAKEFULNESS, "mIsPowered", "mPlugType", "mBatteryLevel", "mDeviceIdleMode", "mLightDeviceIdleMode")
        val UNREADABLE_CHECKS: List<Pair<String, (String) -> Any?>> =
            listOf(
                "mIsPowered" to String::toBooleanStrictOrNull,
                "mPlugType" to String::toIntOrNull,
                "mBatteryLevel" to String::toIntOrNull,
                "mDeviceIdleMode" to String::toBooleanStrictOrNull,
                "mLightDeviceIdleMode" to String::toBooleanStrictOrNull,
            )

        /** The `Power Manager State:` names, the same in android-16.0.0_r4 and android-17.0.0_r1. */
        val STATE_NAMES =
            setOf(
                "mBatteryLevel",
                "mBatteryLevelLow",
                "mBootCompleted",
                "mDeviceIdleMode",
                "mDeviceIdleTempWhitelist",
                "mDeviceIdleWhitelist",
                "mDirty",
                "mDockState",
                "mDreamsBatteryLevelDrain",
                "mEnhancedDischargePredictionIsPersonalized",
                "mEnhancedDischargeTimeElapsed",
                "mHalAutoSuspendModeEnabled",
                "mHalInteractiveModeEnabled",
                "mHoldingDisplaySuspendBlocker",
                "mHoldingWakeLockSuspendBlocker",
                "mInterceptedPowerKeyForProximity",
                "mIsFaceDown",
                "mIsPowered",
                "mLastEnhancedDischargeTimeUpdatedElapsed",
                "mLastFlipTime",
                "mLastGlobalSleepTimeRealtime",
                "mLastGlobalWakeTimeRealtime",
                "mLastInteractivePowerHintTime",
                "mLastScreenBrightnessBoostTime",
                "mLastSleepReason",
                "mLastSleepTime",
                "mLastWakeTime",
                "mLightDeviceIdleMode",
                "mLowPowerStandbyActive",
                "mNotifyLongDispatched",
                "mNotifyLongNextCheck",
                "mNotifyLongScheduled",
                "mPlugType",
                "mProximityPositive",
                "mRequestWaitForNegativeProximity",
                "mSandmanScheduled",
                "mScreenBrightnessBoostInProgress",
                "mStayOn",
                "mSystemReady",
                "mUseAutoSuspend",
                WAKEFULNESS,
                "mWakefulnessChanging",
                "mWakeLockSummary",
            )

        /** `BatterySaverStateMachine.dump` names, L1014-1085 in both tags. */
        val SAVER_NAMES =
            setOf(
                SAVER_ENABLED,
                "full",
                "adaptive",
                "mState",
                "mLastChangedIntReason",
                "mLastChangedStrReason",
                "mBootCompleted",
                "mSettingsLoaded",
                "mBatteryStatusSet",
                "mIsPowered",
                "mBatteryLevel",
                "mIsBatteryLevelLow",
                "mSettingAutomaticBatterySaver",
                "mSettingBatterySaverEnabled",
                "mSettingBatterySaverEnabledSticky",
                "mSettingBatterySaverStickyAutoDisableEnabled",
                "mSettingBatterySaverStickyAutoDisableThreshold",
                "mSettingBatterySaverTriggerThreshold",
                "mBatterySaverStickyBehaviourDisabled",
                "mBatterySaverTurnedOffNotificationEnabled",
                "mDynamicPowerSavingsDefaultDisableThreshold",
                "mDynamicPowerSavingsDisableThreshold",
                "mDynamicPowerSavingsEnableBatterySaver",
                "mLastAdaptiveBatterySaverChangedExternallyElapsed",
            )
    }
}
