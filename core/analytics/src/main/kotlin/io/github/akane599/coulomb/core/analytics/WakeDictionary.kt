// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.analytics

import io.github.akane599.coulomb.core.model.WakeKind

/**
 * The part of the phone a kernel wake record belongs to, read from its name (SPEC 10.3: kernel
 * wakelocks grouped by subsystem). Names are the drivers' own, so only words that name one part
 * unambiguously place a record; every other record is [OTHER].
 */
public enum class WakeSubsystem {
    MOBILE_NETWORK,
    WIFI,
    BLUETOOTH,
    GPS,
    NFC,

    /** Touch panels, pens, keys and every `/dev/input/eventN` device. */
    INPUT,
    FINGERPRINT,
    SENSORS,
    BATTERY,
    AUDIO,
    CAMERA,
    TIMERS,
    ANDROID,
    OTHER,
}

/**
 * What holds a wake record, for the names Coulomb knows (SPEC 10.3, 10.5: the plain-language
 * dictionary). Each value's source is cited where [WakeDictionary] matches it; a name that no source
 * pins down has no meaning rather than a guess.
 */
public enum class WakeMeaning {
    /** Android holds it while it honours a wakelock, an app's or its own. */
    ANDROID_WAKELOCKS,

    /** Android holds it while the screen is on or changing state. */
    ANDROID_DISPLAY,

    /** Android holds it while it tells apps the screen turned on or off, and while it plays the charging feedback. */
    ANDROID_BROADCASTS,

    /** Android holds it until the phone has finished starting. */
    ANDROID_BOOTING,

    /** Android's sensor service, delivering readings from a sensor that wakes the phone. */
    SENSOR_SERVICE,

    /** The kernel's alarm timer, staying awake for an alarm due within two seconds. */
    ALARM_TIMER,

    /** A timer a process watches, held from when it fires until that process picks it up. */
    WAKEUP_TIMER,

    /** An input device, held from its input until Android picks it up. */
    INPUT_DEVICE,

    /** Android's alarm manager, delivering the app's alarms. */
    ALARM_DELIVERY,

    /** Android's job scheduler, running the app's background work. */
    JOB,

    /** Android's sync manager, syncing the app's account data. */
    SYNC,

    /** A suspend that stopped because wakeup sources were still active. */
    SLEEP_BLOCKED,

    /** A suspend that stopped because a wakeup event arrived; the source named was the last active. */
    SLEEP_INTERRUPTED,

    /** A suspend that stopped for another reason the kernel names. */
    SLEEP_ABORTED,

    /** A hardware interrupt that woke the phone, as batterystats records it. */
    INTERRUPT,
}

/** Reads wake records' names: their subsystem and, where a source pins it down, their meaning. */
public object WakeDictionary {
    /**
     * The subsystem of a kernel wakelock, wakeup source, wakeup reason or resume cause, from the
     * words in its [name]: the first rule that matches wins.
     */
    public fun subsystem(name: String): WakeSubsystem {
        val lower = name.lowercase()
        val words = lower.split(NON_WORD).filter { it.isNotEmpty() }
        return SUBSYSTEM_RULES.firstOrNull { (_, matches) -> matches(lower, words) }?.first ?: WakeSubsystem.OTHER
    }

    /** What holds the record [name] of [kind], or null when no source pins it down. */
    public fun meaning(
        kind: WakeKind,
        name: String,
    ): WakeMeaning? =
        when (kind) {
            WakeKind.PARTIAL -> partial(name)

            WakeKind.KERNEL, WakeKind.WAKEUP_SOURCE -> kernel(name)

            WakeKind.WAKEUP_REASON -> reason(name)

            // KernelWindow.logRows stores a resume cause under a bare interrupt name or another
            // reason, which no format tells apart, so it has no line.
            WakeKind.RESUME_CAUSE -> null

            WakeKind.SUSPEND_ABORT -> WakeMeaning.SLEEP_ABORTED
        }

    private fun partial(tag: String): WakeMeaning? =
        when {
            // AlarmManagerService.java holds "*alarm*" for the app it delivers to (android-16.0.0_r4
            // L4413, L4650-4660; android-17.0.0_r1 L4545, L4783-4793: setWakelockWorkSource). The
            // alarm's own tag goes only to the history (setHistoryTag), not to the per-app records.
            tag == "*alarm*" -> WakeMeaning.ALARM_DELIVERY

            // JobStatus.getWakelockTag: "*job*" then the job type and "/" (android-16.0.0_r4
            // L1503-1512, android-17.0.0_r1 L1735-1744); JobServiceContext takes it (16: L449, 17: L478).
            tag.startsWith("*job*") -> WakeMeaning.JOB

            // SyncManager.SYNC_WAKE_LOCK_PREFIX (android-16.0.0_r4 L211, used at L3341; android-17.0.0_r1
            // L212, used at L3356).
            tag.startsWith("*sync*/") -> WakeMeaning.SYNC

            else -> null
        }

    private fun kernel(name: String): WakeMeaning? =
        when {
            // PowerManagerService.java creates the four suspend blockers (android-16.0.0_r4 L1306-1310,
            // L1421; android-17.0.0_r1 L1347-1351, L1463). WakeLocks is held while the wakelock
            // summary has WAKE_LOCK_CPU, Display while needSuspendBlockerLocked() (17: L4230-4262,
            // L4315-4343). Broadcasts goes to Notifier, which holds it while it sends the screen on
            // and off broadcasts (Notifier.java 16: L998-1013; 17: L1113-1128) and from charging
            // start until its sound and animation have played (16: L936, L952, L1190, L1195;
            // 17: L1051, L1067, L1305, L1310).
            name == "PowerManagerService.WakeLocks" -> WakeMeaning.ANDROID_WAKELOCKS

            name == "PowerManagerService.Display" -> WakeMeaning.ANDROID_DISPLAY

            name == "PowerManagerService.Broadcasts" -> WakeMeaning.ANDROID_BROADCASTS

            name == "PowerManagerService.Booting" -> WakeMeaning.ANDROID_BOOTING

            // SensorService.cpp WAKE_LOCK_NAME (android-16.0.0_r4 L88, android-17.0.0_r1 L90), taken
            // when a wake-up sensor's events are pending (17: L1217-1219, L2299-2300).
            name == "SensorService_wakelock" -> WakeMeaning.SENSOR_SERVICE

            // kernel/time/alarmtimer.c (android16-6.12): the "alarmtimer" platform device is a wakeup
            // source (L99-102) that alarmtimer_suspend holds for 2 s when an alarm is nearer
            // (L281-284), and for 1 s when it cannot set the RTC alarm (L307-309).
            name == "alarmtimer" || name.startsWith("alarmtimer.") -> WakeMeaning.ALARM_TIMER

            // fs/eventpoll.c ep_create_wakeup_source names an EPOLLWAKEUP source after the file
            // (android16-6.12 L1482-1499) and releases it when epoll_wait hands the event over
            // (L1803-1806); fs/timerfd.c names its files "[timerfd]" (L460).
            name == "[timerfd]" -> WakeMeaning.WAKEUP_TIMER

            // EventHub.registerFdForEpoll polls each /dev/input/eventN with EPOLLWAKEUP
            // (android-16.0.0_r4 L2254, android-17.0.0_r1 L2365), so its source is named "eventN".
            INPUT_EVENT.matches(name) -> WakeMeaning.INPUT_DEVICE

            else -> null
        }

    private fun reason(name: String): WakeMeaning? =
        when {
            // kernel/power/wakeup_reason.c reports an abandoned suspend as "Abort: " and its reason
            // (android16-6.12 L315, L337). drivers/base/power/wakeup.c names the sources still
            // active, or, when none is, the one active last (pm_get_active_wakeup_sources, L849-876).
            name.startsWith(ABORT_PENDING) -> WakeMeaning.SLEEP_BLOCKED

            name.startsWith(ABORT_LAST_ACTIVE) -> WakeMeaning.SLEEP_INTERRUPTED

            name.startsWith(ABORT) -> WakeMeaning.SLEEP_ABORTED

            // wakeup_reason.c last_resume_reason_show: one "<irq> <name>" per interrupt (L345-347);
            // batterystats joins a resume's interrupts with ":".
            IRQ.matches(name) -> WakeMeaning.INTERRUPT

            else -> null
        }

    private val NON_WORD = Regex("[^a-z0-9]+")
    private val INPUT_EVENT = Regex("event[0-9]+")
    private val IRQ = Regex("[0-9]+ \\S.*")
    private const val ABORT = "Abort:"
    private const val ABORT_PENDING = "Abort: Pending Wakeup Sources:"
    private const val ABORT_LAST_ACTIVE = "Abort: Last active Wakeup Source:"

    private fun hasWord(vararg any: String): (String, List<String>) -> Boolean = { _, words -> words.any { it in any } }

    private fun hasText(vararg any: String): (String, List<String>) -> Boolean = { lower, _ -> any.any { it in lower } }

    /**
     * In order; the first match wins. IPA is Qualcomm's IP Accelerator and rmnet its mobile data
     * interface; TSP is a touch screen panel and WPC wireless charging (the Wireless Power
     * Consortium's Qi). Android's own blockers, timers and input devices come first, as their names
     * are exact.
     */
    private val SUBSYSTEM_RULES: List<Pair<WakeSubsystem, (String, List<String>) -> Boolean>> =
        listOf(
            WakeSubsystem.ANDROID to { lower, _ -> lower.startsWith("powermanagerservice.") },
            WakeSubsystem.TIMERS to { lower, _ -> lower == "[timerfd]" || lower == "alarmtimer" || lower.startsWith("alarmtimer.") },
            WakeSubsystem.INPUT to { lower, _ -> INPUT_EVENT.matches(lower) },
            WakeSubsystem.FINGERPRINT to hasText("finger"),
            WakeSubsystem.SENSORS to hasText("sensor"),
            WakeSubsystem.INPUT to { lower, words -> hasText("touch", "synaptics", "wacom", "gpio_keys")(lower, words) || "tsp" in words },
            WakeSubsystem.MOBILE_NETWORK to { lower, words ->
                words.any { it == "ipa" || it.startsWith("rmnet") } || hasText("modem", "radio-interface")(lower, words)
            },
            WakeSubsystem.WIFI to hasText("wlan", "wifi"),
            WakeSubsystem.BLUETOOTH to { lower, words -> "bluetooth" in lower || "bt" in words },
            WakeSubsystem.GPS to hasText("gnss", "gps"),
            WakeSubsystem.NFC to hasWord("nfc"),
            WakeSubsystem.BATTERY to { lower, words -> hasText("battery", "charger", "charging")(lower, words) || "wpc" in words },
            WakeSubsystem.AUDIO to hasText("audio"),
            WakeSubsystem.CAMERA to hasText("camera"),
        )
}
