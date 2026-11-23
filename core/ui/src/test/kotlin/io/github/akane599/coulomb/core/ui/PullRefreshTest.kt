// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.ui

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Audit BUG-017: the spinner follows the request it made, nothing else. */
@OptIn(ExperimentalCoroutinesApi::class)
class PullRefreshTest {
    @Test
    fun theSpinnerLastsAsLongAsTheRequest() =
        runTest {
            val pull = PullRefresh(backgroundScope, WAIT_MS) { delay(2_000) }
            pull.pull()
            runCurrent()
            assertThat(pull.isRefreshing.value).isTrue()
            advanceTimeBy(1_999)
            assertThat(pull.isRefreshing.value).isTrue()
            advanceTimeBy(2)
            assertThat(pull.isRefreshing.value).isFalse()
        }

    @Test
    fun aDebouncedPullEndsAtOnce() =
        runTest {
            val pull = PullRefresh(backgroundScope, WAIT_MS) { }
            pull.pull()
            runCurrent()
            assertThat(pull.isRefreshing.value).isFalse()
        }

    @Test
    fun aRequestThatNeverReturnsEndsAtTheTimeout() =
        runTest {
            val pull = PullRefresh(backgroundScope, WAIT_MS) { awaitCancellation() }
            pull.pull()
            advanceTimeBy(WAIT_MS - 1)
            assertThat(pull.isRefreshing.value).isTrue()
            advanceTimeBy(2)
            assertThat(pull.isRefreshing.value).isFalse()
        }

    @Test
    fun aSecondPullWhileOneRunsIsIgnoredAndALaterOneRunsAgain() =
        runTest {
            var requests = 0
            val pull =
                PullRefresh(backgroundScope, WAIT_MS) {
                    requests++
                    delay(1_000)
                }
            pull.pull()
            pull.pull()
            runCurrent()
            assertThat(requests).isEqualTo(1)
            advanceTimeBy(1_001)
            pull.pull()
            runCurrent()
            assertThat(requests).isEqualTo(2)
        }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
