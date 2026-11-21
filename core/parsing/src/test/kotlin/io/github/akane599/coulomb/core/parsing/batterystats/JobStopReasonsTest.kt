// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.parsing.batterystats

import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.model.stats.JobStopCount
import org.junit.Test

class JobStopReasonsTest {
    @Test
    fun theAbandonedReasonHasTheNameBothParsersWrite() {
        // Code 13 in `JobParameters.JOB_STOP_REASON_CODES`; the checkin and proto parsers name a stop by this.
        assertThat(JobStopReasons.name(ABANDONED_CODE)).isEqualTo(JobStopCount.TIMEOUT_ABANDONED)
    }

    @Test
    fun anOemCodeKeepsItsNumber() {
        assertThat(JobStopReasons.name(OEM_CODE)).isEqualTo("OEM_100")
    }

    private companion object {
        const val ABANDONED_CODE = 13
        const val OEM_CODE = 100
    }
}
