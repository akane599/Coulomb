// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.analytics

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CalibrationTest {
    /**
     * A discharge at [trueUa] (negative), read every [stepS] seconds. The device reports current as
     * `raw = trueUa / (sign × scale)` and the charge counter in steps of [quantumUah].
     */
    private fun trace(
        sign: Int,
        scale: Int,
        trueUa: Long = -400_000,
        minutes: Int = 15,
        stepS: Int = 30,
        quantumUah: Long = 1,
        plugged: Boolean = false,
        screenOn: Boolean = true,
        startUah: Long = 4_000_000,
    ): List<RawReading> =
        (0..minutes * 60 / stepS).map { i ->
            val seconds = i * stepS
            val charge = startUah + trueUa * seconds / 3_600
            RawReading(
                elapsedNs = seconds * NS_PER_S,
                currentRaw = trueUa / (sign * scale),
                chargeUah = charge / quantumUah * quantumUah,
                voltageMv = 3_900,
                levelPct = 80 - (seconds / 600),
                plugged = plugged,
                screenOn = screenOn,
            )
        }

    @Test
    fun aospStyleMicroampsNeedNoChange() {
        val readings = trace(sign = 1, scale = 1)
        assertThat(Calibration.sign(readings)).isEqualTo(1)
        val calibration = checkNotNull(Calibration.calibrate(readings, sign = 1))
        assertThat(calibration.scale).isEqualTo(1)
        assertThat(calibration.chargeCounter).isEqualTo(ChargeCounterVerdict.RELIABLE)
        assertThat(calibration.quality).isGreaterThan(0.95)
        assertThat(calibration.currentUa(readings.first().currentRaw)).isEqualTo(-400_000)
    }

    @Test
    fun samsungStyleInvertedMilliampsAreTurnedAround() {
        val readings = trace(sign = -1, scale = 1_000, quantumUah = 1_000)
        assertThat(readings.first().currentRaw).isEqualTo(400)
        assertThat(Calibration.sign(readings)).isEqualTo(-1)
        val calibration = checkNotNull(Calibration.calibrate(readings, sign = -1))
        assertThat(calibration.scale).isEqualTo(1_000)
        assertThat(calibration.currentUa(400)).isEqualTo(-400_000)
        assertThat(calibration.chargeCounter).isEqualTo(ChargeCounterVerdict.RELIABLE)
    }

    @Test
    fun theSignWaitsForTwentyAgreeingReadings() {
        val readings = trace(sign = 1, scale = 1)
        assertThat(Calibration.sign(readings.take(19))).isNull()
        assertThat(Calibration.sign(readings.take(20))).isEqualTo(1)
        // A disagreeing reading in the last twenty holds the sign back.
        val flipped = readings.take(25).toMutableList().also { it[22] = it[22].copy(currentRaw = -it[22].currentRaw) }
        assertThat(Calibration.sign(flipped)).isNull()
        // Screen off unplugged readings say nothing about the sign.
        assertThat(Calibration.sign(trace(sign = 1, scale = 1, screenOn = false))).isNull()
    }

    @Test
    fun chargingCountsOnlyOnceTheLevelRises() {
        val charging =
            (0..40).map { i ->
                RawReading(
                    i * 30 * NS_PER_S,
                    currentRaw = -1_500,
                    chargeUah = null,
                    voltageMv = 4_200,
                    levelPct = 50 + i / 10,
                    plugged = true,
                    screenOn = false,
                )
            }
        // Charging is positive: these raw readings are negative, so the sign is -1.
        assertThat(Calibration.sign(charging)).isEqualTo(-1)
        // Plugged in at full, the level never rises: no evidence.
        assertThat(Calibration.sign(charging.map { it.copy(levelPct = 100) })).isNull()
    }

    @Test
    fun drainingWhilePluggedInDoesNotFlipTheSign() {
        // AOSP-style: unplugged with the screen on, then charging with the level rising, then a
        // game on a weak charger that drains while still plugged in.
        val unplugged = trace(sign = 1, scale = 1, minutes = 20)
        val t0 = unplugged.last().elapsedNs
        val rising =
            (1..20).map { i ->
                RawReading(t0 + i * 5 * NS_PER_S, 900_000, null, 4_200, 50 + i / 10, plugged = true, screenOn = true)
            }
        val t1 = rising.last().elapsedNs
        val draining =
            (1..20).map { i ->
                RawReading(t1 + i * 5 * NS_PER_S, -300_000, null, 4_000, if (i < 10) 52 else 51, plugged = true, screenOn = true)
            }
        assertThat(Calibration.sign(unplugged + rising + draining)).isEqualTo(1)
    }

    @Test
    fun aCounterDerivedFromTheLevelIsCoarse() {
        // The counter is the level times a full charge: its steps (48,124 and 48,123 µAh) have no
        // common divisor, but it never moves between level changes.
        val readings =
            (0..240).map { i ->
                val level = 80 - i / 40
                RawReading(i * 5 * NS_PER_S, -400_000, 4_812_345L * level / 100, 3_900, level, plugged = false, screenOn = true)
            }
        assertThat(Calibration.chargeCounter(readings)).isEqualTo(ChargeCounterVerdict.COARSE)
    }

    @Test
    fun theUnsupportedMarkersAreNoCounter() {
        assertThat(
            Calibration.chargeCounter(trace(1, 1).map { it.copy(chargeUah = Long.MIN_VALUE) }),
        ).isEqualTo(ChargeCounterVerdict.UNSUPPORTED)
        assertThat(Calibration.chargeCounter(trace(1, 1).map { it.copy(chargeUah = 0) })).isEqualTo(ChargeCounterVerdict.UNSUPPORTED)
    }

    @Test
    fun aCounterThatIsMissingStuckOrCoarseIsNotTrusted() {
        assertThat(Calibration.chargeCounter(trace(1, 1).map { it.copy(chargeUah = null) })).isEqualTo(ChargeCounterVerdict.UNSUPPORTED)
        assertThat(Calibration.chargeCounter(emptyList())).isEqualTo(ChargeCounterVerdict.UNKNOWN)
        val stuck = trace(1, 1, minutes = 30).map { it.copy(chargeUah = 4_000_000) }
        assertThat(Calibration.chargeCounter(stuck)).isEqualTo(ChargeCounterVerdict.STUCK)
        assertThat(Calibration.chargeCounter(trace(1, 1, quantumUah = 10_000))).isEqualTo(ChargeCounterVerdict.COARSE)
        // Steps of exactly 1 mAh are fine; drain between readings is not mistaken for steps.
        assertThat(Calibration.chargeCounter(trace(1, 1, quantumUah = 1_000))).isEqualTo(ChargeCounterVerdict.RELIABLE)
    }

    @Test
    fun withoutACounterThePowerCheckPicksTheScaleAtLowerQuality() {
        val readings = trace(sign = -1, scale = 1_000).map { it.copy(chargeUah = null) }
        val calibration = checkNotNull(Calibration.calibrate(readings, sign = -1))
        assertThat(calibration.scale).isEqualTo(1_000)
        assertThat(calibration.chargeCounter).isEqualTo(ChargeCounterVerdict.UNSUPPORTED)
        assertThat(calibration.quality).isLessThan(0.5)
    }

    @Test
    fun aScaleThatFailsThePowerCheckIsRefused() {
        // 400 µA at 3.9 V is 1.6 mW: below 0.05 W at either scale's best fit.
        val tiny = trace(sign = 1, scale = 1, trueUa = -400)
        assertThat(Calibration.calibrate(tiny, sign = 1)).isNull()
    }

    @Test
    fun aShortWindowFallsBackToThePowerCheck() {
        val readings = trace(sign = 1, scale = 1, minutes = 5)
        val calibration = checkNotNull(Calibration.calibrate(readings, sign = 1))
        assertThat(calibration.scale).isEqualTo(1)
        assertThat(calibration.quality).isLessThan(0.5)
    }
}
