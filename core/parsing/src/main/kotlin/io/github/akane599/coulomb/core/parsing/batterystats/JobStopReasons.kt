// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.parsing.batterystats

import io.github.akane599.coulomb.core.parsing.aosp.job.InternalStopReasonEnum

/**
 * `JobParameters.JOB_STOP_REASON_CODES`: the internal stop reasons both batterystats writers list
 * for each job, in this order (the `jbc` columns, the proto `ReasonCount`s).
 *
 * apex/jobscheduler/framework/java/android/app/job/JobParameters.java: android-16.0.0_r4 L146-162
 * lists INTERNAL_STOP_REASON_UNKNOWN (-1) to TIMEOUT_ABANDONED (13); android-17.0.0_r1 L153-170
 * adds DEVICE_STATE_BATTERY_SAVER (14). The values are `JobProtoEnums` values (L60-142). An OEM
 * may append its own codes (One UI 9: 100); checkin does not print the code, so such a column
 * stays unnamed.
 */
internal object JobStopReasons {
    private const val SDK_17 = 37
    private const val LAST_CODE_16 = 13
    private const val LAST_CODE_17 = 14
    private const val OEM_PREFIX = "OEM_"

    /** The codes in column order for [sdkInt]; a newer SDK uses the newest known list. */
    fun codes(sdkInt: Int): IntArray = (-1..(if (sdkInt >= SDK_17) LAST_CODE_17 else LAST_CODE_16)).toList().toIntArray()

    /** The `InternalStopReasonEnum` name of [code], or `OEM_<code>` for one AOSP does not define. */
    fun name(code: Int): String = InternalStopReasonEnum.fromValue(code)?.name ?: "$OEM_PREFIX$code"
}
