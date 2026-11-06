// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.parsing.batterystats

import io.github.akane599.coulomb.core.model.history.HistoryKind

/**
 * The checkin names `BatteryStats.HistoryPrinter` prints (frameworks/base
 * core/java/android/os/BatteryStats.java; android-17.0.0_r1 L2810-2906 and L6684-7206,
 * android-16.0.0_r4 L2809-2905 and L6652-7174, the same text in both).
 */
internal object HistoryNames {
    /** One-bit states of `HISTORY_STATE_DESCRIPTIONS` and `HISTORY_STATE2_DESCRIPTIONS`, printed `+x` / `-x`. */
    val FLAGS: Map<String, HistoryKind> =
        mapOf(
            "r" to HistoryKind.CPU_RUNNING,
            "w" to HistoryKind.WAKE_LOCK,
            "s" to HistoryKind.SENSOR,
            "g" to HistoryKind.GPS,
            "Wl" to HistoryKind.WIFI_FULL_LOCK,
            "Ws" to HistoryKind.WIFI_SCAN,
            "Wm" to HistoryKind.WIFI_MULTICAST,
            "Wr" to HistoryKind.WIFI_RADIO,
            "Pr" to HistoryKind.MOBILE_RADIO,
            "Psc" to HistoryKind.PHONE_SCANNING,
            "a" to HistoryKind.AUDIO,
            "S" to HistoryKind.SCREEN,
            "BP" to HistoryKind.PLUGGED,
            "Sd" to HistoryKind.SCREEN_DOZE,
            "ps" to HistoryKind.POWER_SAVE,
            "v" to HistoryKind.VIDEO,
            "Ww" to HistoryKind.WIFI_RUNNING,
            "W" to HistoryKind.WIFI,
            "fl" to HistoryKind.FLASHLIGHT,
            "ch" to HistoryKind.CHARGING,
            "Ud" to HistoryKind.USB_DATA,
            "Pcl" to HistoryKind.PHONE_IN_CALL,
            "b" to HistoryKind.BLUETOOTH,
            "ca" to HistoryKind.CAMERA,
            "bles" to HistoryKind.BLE_SCAN,
            "Chtp" to HistoryKind.CELLULAR_HIGH_TX_POWER,
        )

    /** A multi-bit state and its short value names; the index of a name is the platform's value. */
    class Values(
        val kind: HistoryKind,
        val names: List<String>,
    )

    private val DIGITS_0_TO_4 = listOf("0", "1", "2", "3", "4")

    /** Multi-bit states, printed `x=<short value>`, or the number when it has no name. */
    val VALUES: Map<String, Values> =
        mapOf(
            // DATA_CONNECTION_NAMES (android-17.0.0_r1 L2634-2639; the name tables are one line earlier in 16).
            "Pcn" to
                Values(
                    HistoryKind.DATA_CONNECTION,
                    listOf(
                        "oos",
                        "gprs",
                        "edge",
                        "umts",
                        "cdma",
                        "evdo_0",
                        "evdo_A",
                        "1xrtt",
                        "hsdpa",
                        "hsupa",
                        "hspa",
                        "iden",
                        "evdo_b",
                        "lte",
                        "ehrpd",
                        "hspap",
                        "gsm",
                        "td_scdma",
                        "iwlan",
                        "lte_ca",
                        "nr",
                        "emngcy",
                        "other",
                    ),
                ),
            "Pst" to Values(HistoryKind.PHONE_STATE, listOf("in", "out", "em", "off")),
            "Pss" to Values(HistoryKind.PHONE_SIGNAL_STRENGTH, DIGITS_0_TO_4),
            // SCREEN_BRIGHTNESS_SHORT_NAMES (L2389-2391).
            "Sb" to Values(HistoryKind.SCREEN_BRIGHTNESS, DIGITS_0_TO_4),
            "di" to Values(HistoryKind.DEVICE_IDLE, listOf("off", "light", "full", "???")),
            "Wss" to Values(HistoryKind.WIFI_SIGNAL_STRENGTH, DIGITS_0_TO_4),
            // WIFI_SUPPL_STATE_SHORT_NAMES (L2781-2785).
            "Wsp" to
                Values(
                    HistoryKind.WIFI_SUPPLICANT,
                    listOf("inv", "dsc", "dis", "inact", "scan", "auth", "ascing", "asced", "4-way", "group", "compl", "dorm", "uninit"),
                ),
            "Gss" to Values(HistoryKind.GPS_SIGNAL_QUALITY, listOf("poor", "good", "none")),
            "nrs" to Values(HistoryKind.NR_STATE, listOf("0", "1", "2", "3")),
        )

    /**
     * `HISTORY_EVENT_CHECKIN_NAMES` (android-17.0.0_r1 L2882-2887), indexed by `HistoryItem.EVENT_*`
     * code (L1912-1958; android-16.0.0_r4 L1911-1957).
     * Code 0 (`Enl`, no event) has no kind.
     */
    val EVENTS: Map<String, HistoryKind> =
        listOf(
            "Epr" to HistoryKind.PROCESS,
            "Efg" to HistoryKind.FOREGROUND,
            "Etp" to HistoryKind.TOP_APP,
            "Esy" to HistoryKind.SYNC,
            "Ewl" to HistoryKind.WAKE_LOCK_EVENT,
            "Ejb" to HistoryKind.JOB,
            "Eur" to HistoryKind.USER_RUNNING,
            "Euf" to HistoryKind.USER_FOREGROUND,
            "Ecn" to HistoryKind.CONNECTIVITY,
            "Eac" to HistoryKind.ACTIVE,
            "Epi" to HistoryKind.PACKAGE_INSTALLED,
            "Epu" to HistoryKind.PACKAGE_UNINSTALLED,
            "Eal" to HistoryKind.ALARM,
            "Est" to HistoryKind.COLLECT_EXTERNAL_STATS,
            "Eai" to HistoryKind.PACKAGE_INACTIVE,
            "Eaa" to HistoryKind.PACKAGE_ACTIVE,
            "Etw" to HistoryKind.TEMP_ALLOWLIST,
            "Esw" to HistoryKind.SCREEN_WAKE_UP,
            "Ewa" to HistoryKind.WAKEUP_AP,
            "Elw" to HistoryKind.LONG_WAKE_LOCK,
            "Esc" to HistoryKind.STATE_CHANGE,
            "Eds" to HistoryKind.DISPLAY_STATE,
        ).toMap()

    /**
     * Events whose tag number is not a UID: the connectivity type
     * (`BatteryStatsImpl.noteConnectivityChangedLocked`) and the version code
     * (`notePackageInstalledLocked`), services/core/java/com/android/server/power/stats/
     * BatteryStatsImpl.java android-16.0.0_r4 L5892-5895 and L6086-6090, android-17.0.0_r1
     * L5452-5455 and L5646-5650.
     */
    val VALUE_NOT_UID: Set<HistoryKind> = setOf(HistoryKind.CONNECTIVITY, HistoryKind.PACKAGE_INSTALLED)

    /** Battery fields, printed only when they change. */
    val BATTERY: Map<String, HistoryKind> =
        mapOf(
            "Bl" to HistoryKind.BATTERY_LEVEL,
            "Bs" to HistoryKind.BATTERY_STATUS,
            "Bh" to HistoryKind.BATTERY_HEALTH,
            "Bp" to HistoryKind.BATTERY_PLUG,
            "Bt" to HistoryKind.BATTERY_TEMPERATURE,
            "Bv" to HistoryKind.BATTERY_VOLTAGE,
            "Bcc" to HistoryKind.BATTERY_CHARGE,
        )

    /**
     * Letters for status, health and plug (android-17.0.0_r1 L6924-7000), as `BatteryManager` values
     * (frameworks/proto_logging stats/enums/os/enums.proto, both tags: BatteryHealthEnum L49-59,
     * BatteryPluggedStateEnum L65-73, BatteryStatusEnum L79-84). Any other value prints as its number.
     */
    val BATTERY_LETTERS: Map<HistoryKind, Map<String, Long>> =
        mapOf(
            HistoryKind.BATTERY_STATUS to mapOf("?" to 1L, "c" to 2L, "d" to 3L, "n" to 4L, "f" to 5L),
            HistoryKind.BATTERY_HEALTH to mapOf("?" to 1L, "g" to 2L, "h" to 3L, "d" to 4L, "v" to 5L, "f" to 6L, "c" to 7L),
            HistoryKind.BATTERY_PLUG to mapOf("n" to 0L, "a" to 1L, "u" to 2L, "w" to 4L, "d" to 8L),
        )

    /** Rail charges in mAh, printed with `DecimalFormat("#.##")` in the system locale. Always 0 on both test devices; unused. */
    val RAIL_CHARGES: Set<String> = setOf("Mrc", "Wrc")

    /** Wake reason and a wakelock tag on a record whose wakelock bit did not change. */
    const val WAKE_REASON = "wr"
    const val WAKE_LOCK_TAG = "w"
}

/** `UserHandle.PER_USER_RANGE` (core/java/android/os/UserHandle.java android-16.0.0_r4 L49, android-17.0.0_r1 L51). */
private const val PER_USER_RANGE = 100_000

/** `Process.FIRST_APPLICATION_UID` (core/java/android/os/Process.java android-16.0.0_r4 L293, android-17.0.0_r1 L294). */
private const val FIRST_APPLICATION_UID = 10_000

/**
 * The UID behind `UserHandle.formatUid` text (android-16.0.0_r4 L471-494, android-17.0.0_r1
 * L505-531): a plain number below 10000, `u<user>a<n>` for an app, `u<user>s<n>` for a system UID.
 * Isolated (`i`, `ai`) and private compute (`p`) forms give null.
 */
internal fun parseFormattedUid(text: String): Int? {
    val plain = text.toIntOrNull()
    val match = if (plain == null) FORMATTED_UID.matchEntire(text) else null
    return when {
        plain != null -> {
            plain
        }

        match == null -> {
            null
        }

        else -> {
            val (user, kind, n) = match.destructured
            user.toInt() * PER_USER_RANGE + (if (kind == "a") FIRST_APPLICATION_UID else 0) + n.toInt()
        }
    }
}

/** User ids up to 4 digits keep the result inside an Int; real ones are 0 to a few hundred. */
private val FORMATTED_UID = Regex("u([0-9]{1,4})([as])([0-9]{1,5})")
