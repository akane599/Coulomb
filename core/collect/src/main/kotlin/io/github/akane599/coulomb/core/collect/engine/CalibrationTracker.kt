// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.engine

import io.github.akane599.coulomb.core.analytics.Calibration
import io.github.akane599.coulomb.core.analytics.ChargeCounterVerdict
import io.github.akane599.coulomb.core.analytics.CurrentCalibration
import io.github.akane599.coulomb.core.analytics.RawReading
import io.github.akane599.coulomb.core.database.dao.DeviceDao
import io.github.akane599.coulomb.core.database.dao.SampleDao
import io.github.akane599.coulomb.core.database.entity.DeviceProfileEntity

/**
 * Runs SPEC 6.1 calibration over the last hour of samples at each heavy snapshot until this build
 * has a good one, and stores it on the device profile. A new build starts again (the profile is
 * per fingerprint).
 */
internal class CalibrationTracker(
    private val devices: DeviceDao,
    private val samples: SampleDao,
) {
    /** Loads the stored calibration of [profile], or null when it has none yet. */
    fun stored(profile: DeviceProfileEntity): CurrentCalibration? {
        val sign = profile.currentSign ?: return null
        val scale = profile.currentScale ?: return null
        val counter = if (profile.chargeCounterReliable == true) ChargeCounterVerdict.RELIABLE else ChargeCounterVerdict.UNKNOWN
        return CurrentCalibration(sign, scale, counter, profile.calibrationQuality ?: 0.0)
    }

    /**
     * Tries again when the stored calibration is missing or below [GOOD_QUALITY]. Returns the
     * profile, updated when the new result is better.
     */
    suspend fun update(
        profile: DeviceProfileEntity,
        bootSeq: Int,
        nowElapsedNs: Long,
        nowWallMs: Long,
    ): DeviceProfileEntity {
        if ((profile.calibrationQuality ?: 0.0) >= GOOD_QUALITY && profile.chargeCounterReliable != null) return profile
        val readings =
            samples.samples(bootSeq, nowElapsedNs - WINDOW_NS, nowElapsedNs + 1).mapNotNull { s ->
                s.currentRaw?.let { raw -> RawReading(s.elapsedNs, raw, s.chargeUah, s.voltageMv, s.levelPct, s.plugType != 0, s.screenOn) }
            }
        val verdict = Calibration.chargeCounter(readings)
        val reliable =
            if (verdict ==
                ChargeCounterVerdict.UNKNOWN
            ) {
                profile.chargeCounterReliable
            } else {
                verdict == ChargeCounterVerdict.RELIABLE
            }
        val result = (Calibration.sign(readings) ?: profile.currentSign)?.let { Calibration.calibrate(readings, it) }
        val better = result?.takeIf { it.quality > (profile.calibrationQuality ?: -1.0) }
        if (better == null && reliable == profile.chargeCounterReliable) return profile
        val updated = (better?.let { profile.with(it, nowWallMs) } ?: profile).copy(chargeCounterReliable = reliable)
        // Only the calibration columns: the owner may have set the design capacity meanwhile (ADR-0044 §5).
        devices.setCalibration(
            fingerprint = updated.fingerprint,
            sign = updated.currentSign,
            scale = updated.currentScale,
            quality = updated.calibrationQuality,
            calibratedAtWall = updated.calibratedAtWall,
            reliable = updated.chargeCounterReliable,
        )
        return updated
    }

    private fun DeviceProfileEntity.with(
        result: CurrentCalibration,
        wallMs: Long,
    ) = copy(currentSign = result.sign, currentScale = result.scale, calibrationQuality = result.quality, calibratedAtWall = wallMs)

    companion object {
        /** The span of samples each attempt reads. */
        const val WINDOW_NS: Long = 60 * 60 * 1_000_000_000L

        /** A calibration this good stops further attempts on this build. */
        const val GOOD_QUALITY: Double = 0.9
    }
}
