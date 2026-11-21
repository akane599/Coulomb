// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.insights

import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.insights.AppStandby
import io.github.akane599.coulomb.core.model.cmd.StandbyBucket
import org.junit.Test

class StandbyFactsTest {
    private val uids = mapOf("com.example.one" to 10_001, "com.example.two" to 10_002, "com.example.shared" to 10_001)

    @Test
    fun anAppIsActiveByItsBucketAndOpenedByItsRecord() {
        val facts =
            StandbyFacts.of(
                buckets = mapOf("com.example.one" to StandbyBucket.ACTIVE, "com.example.two" to StandbyBucket.RARE),
                lastUsed = mapOf("com.example.one" to 5_000L),
                uidOf = uids::get,
            )
        // No record of a use is not a use at time zero.
        assertThat(facts).containsExactly(AppStandby(10_001, true, 5_000L), AppStandby(10_002, false, null)).inOrder()
    }

    @Test
    fun aRecordOfZeroIsNoRecord() {
        val facts = StandbyFacts.of(mapOf("com.example.two" to StandbyBucket.ACTIVE), mapOf("com.example.two" to 0L), uids::get)
        assertThat(facts).containsExactly(AppStandby(10_002, true, null))
    }

    @Test
    fun packagesOfOneUidAreOneApp() {
        val facts =
            StandbyFacts.of(
                buckets = mapOf("com.example.one" to StandbyBucket.RARE, "com.example.shared" to StandbyBucket.ACTIVE),
                lastUsed = mapOf("com.example.one" to 9_000L, "com.example.shared" to 2_000L),
                uidOf = uids::get,
            )
        assertThat(facts).containsExactly(AppStandby(10_001, true, 9_000L))
    }

    @Test
    fun anAppThatIsGoneIsLeftOut() {
        assertThat(StandbyFacts.of(mapOf("com.example.gone" to StandbyBucket.ACTIVE), emptyMap(), uids::get)).isEmpty()
    }
}
