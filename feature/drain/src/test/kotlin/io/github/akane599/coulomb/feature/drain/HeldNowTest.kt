// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.drain

import io.github.akane599.coulomb.core.model.dumpsys.BatterySaverState
import io.github.akane599.coulomb.core.model.dumpsys.HeldWakeLock
import io.github.akane599.coulomb.core.model.dumpsys.PowerDump
import io.github.akane599.coulomb.core.model.dumpsys.WakeLockLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HeldNowTest {
    @Test
    fun partialLocksLongestFirstForTheAppTheWorkIsFor() {
        val now = HeldNow.of(labPower())
        assertEquals(
            listOf(
                HeldRow(10_123, "*job*/app.halcyon/.sync.SyncJob", 412_000, long = true, ignored = false),
                HeldRow(10_515, "NorthwindPush", 9_000, long = false, ignored = false),
                HeldRow(1_000, "AlarmManager", null, long = false, ignored = true),
            ),
            now.rows,
        )
        assertEquals(true, now.saverOn)
    }

    @Test
    fun aListThatCouldNotBeTrustedIsNotShownAsEmpty() {
        val now = HeldNow.of(labPower().copy(wakeLocks = null))
        assertFalse(now.listed)
    }
}

/** A power dump with three partial wakelocks, the screen's own lock and battery saver on. */
internal fun labPower() =
    PowerDump(
        wakefulness = "Awake",
        powered = false,
        plugType = 0,
        batteryLevel = 64,
        deviceIdle = false,
        lightDeviceIdle = false,
        wakeLocks =
            listOf(
                lock(WakeLockLevel.SCREEN_BRIGHT, "WindowManager", 1_000, 60_000),
                lock(WakeLockLevel.PARTIAL, "NorthwindPush", 10_515, 9_000),
                lock(WakeLockLevel.PARTIAL, "AlarmManager", 1_000, null, disabled = true),
                lock(WakeLockLevel.PARTIAL, "*job*/app.halcyon/.sync.SyncJob", 1_000, 412_000, long = true, workSource = 10_123),
            ),
        batterySaverSupported = true,
        batterySaver = BatterySaverState(true, false, false, true, 0, 15),
    )

@Suppress("LongParameterList") // One lock's fields.
private fun lock(
    level: WakeLockLevel,
    tag: String,
    uid: Int,
    heldMs: Long?,
    long: Boolean = false,
    disabled: Boolean = false,
    workSource: Int? = null,
) = HeldWakeLock(level, tag, uid, null, heldMs, long, disabled, false, false, false, workSource)
