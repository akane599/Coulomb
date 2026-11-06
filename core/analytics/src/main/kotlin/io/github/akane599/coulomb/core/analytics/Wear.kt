// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.analytics

import java.util.Properties
import kotlin.math.abs

/** SPEC 6.4 stress levels. Everything about wear is Estimated. */
public enum class StressLevel { LOW, MODERATE, HIGH }

/** One factor's two thresholds: [moderate] and up is Moderate, [high] and up is High. */
public data class Threshold(
    val moderate: Double,
    val high: Double,
) {
    public fun level(value: Double): StressLevel =
        when {
            value >= high -> StressLevel.HIGH
            value >= moderate -> StressLevel.MODERATE
            else -> StressLevel.LOW
        }
}

/** The thresholds of `stress-thresholds.properties` (SPEC 6.4: in a config file, not in code). */
public data class StressThresholds(
    val minutesAtOrAbove80: Threshold,
    val peakTempDeciC: Threshold,
    val cRate: Threshold,
) {
    public companion object {
        private const val RESOURCE = "stress-thresholds.properties"

        /** The thresholds bundled with the app. */
        public fun bundled(): StressThresholds {
            val properties = Properties()
            checkNotNull(StressThresholds::class.java.getResourceAsStream(RESOURCE)) { "$RESOURCE is missing" }
                .use(properties::load)

            fun threshold(name: String) =
                Threshold(
                    moderate = properties.getProperty("$name.moderate").toDouble(),
                    high = properties.getProperty("$name.high").toDouble(),
                )
            return StressThresholds(threshold("minutesAtOrAbove80"), threshold("peakTempDeciC"), threshold("cRate"))
        }
    }
}

/** What a session's wear is computed from. */
public data class WearInput(
    val deltaChargeUah: Long,
    val minutesAtOrAbove80: Int,
    val peakTempDeciC: Int,
    /** The largest current magnitude in the session, in mA. */
    val peakCurrentMa: Double,
)

public data class Wear(
    /** |ΔQ| / C_est (SPEC 6.4). */
    val cycleEquivalents: Double,
    val stress: StressLevel,
    /** The factor that set [stress]. */
    val cause: StressFactor?,
)

public enum class StressFactor { TIME_AT_HIGH_CHARGE, TEMPERATURE, C_RATE }

public object WearModel {
    /** A session's cycle equivalents and stress against [capacityMah], the current estimate. */
    public fun wear(
        input: WearInput,
        capacityMah: Double,
        thresholds: StressThresholds,
    ): Wear {
        require(capacityMah > 0) { "capacity must be positive" }
        val levels =
            mapOf(
                StressFactor.TIME_AT_HIGH_CHARGE to thresholds.minutesAtOrAbove80.level(input.minutesAtOrAbove80.toDouble()),
                StressFactor.TEMPERATURE to thresholds.peakTempDeciC.level(input.peakTempDeciC.toDouble()),
                StressFactor.C_RATE to thresholds.cRate.level(abs(input.peakCurrentMa) / capacityMah),
            )
        val worst = levels.maxBy { it.value.ordinal }
        return Wear(
            cycleEquivalents = abs(input.deltaChargeUah / UAH_PER_MAH) / capacityMah,
            stress = worst.value,
            cause = worst.key.takeIf { worst.value != StressLevel.LOW },
        )
    }
}
