// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.database.entity.SampleAggregateEntity
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SampleDaoTest : DatabaseTest() {
    private val dao get() = db.sampleDao()

    private suspend fun allAggregates(resolutionS: Int) = dao.aggregatesBetween(resolutionS, Long.MIN_VALUE, Long.MAX_VALUE)

    private suspend fun allSamples() = dao.samplesBetween(Long.MIN_VALUE, Long.MAX_VALUE)

    @Test
    fun samplesFromTwoBootsWithTheSameElapsedTimeBothStay() =
        runTest {
            dao.observeLatest().test {
                assertThat(awaitItem()).isNull()
                dao.insert(sample(bootSeq = 1, elapsedNs = 5 * NS_PER_S, wallMs = 100))
                assertThat(awaitItem()?.bootSeq).isEqualTo(1)
                // After a reboot elapsed time starts again; the later boot sorts last whatever the wall clock says.
                dao.insert(sample(bootSeq = 2, elapsedNs = 5 * NS_PER_S, wallMs = 50))
                assertThat(awaitItem()?.bootSeq).isEqualTo(2)
            }
            assertThat(allSamples().map { it.bootSeq }).containsExactly(1, 2).inOrder()
        }

    @Test
    fun aRepeatedSampleIsIgnoredRatherThanAbortingItsTransaction() =
        runTest {
            assertThat(dao.insert(sample(bootSeq = 1, elapsedNs = 5))).isNotEqualTo(-1L)
            assertThat(dao.insert(sample(bootSeq = 1, elapsedNs = 5, levelPct = 10))).isEqualTo(-1L)
            assertThat(allSamples().single().levelPct).isEqualTo(80)
        }

    @Test
    fun compactionBuildsEachBucketOnceFromAllItsSamples() =
        runTest {
            // Boot 1 ends before the cut. In boot 2 the cut falls inside the minute starting at 120 s.
            dao.insert(sample(1, 10 * NS_PER_S, levelPct = 81, currentUa = -100_000, tempDeciC = 300))
            dao.insert(sample(1, 50 * NS_PER_S, levelPct = 80, currentUa = null, tempDeciC = 320))
            for (s in listOf(0L, 30L, 125L, 150L, 190L)) dao.insert(sample(2, s * NS_PER_S))

            assertThat(dao.compactSamples(cutBootSeq = 2, cutElapsedNs = 140 * NS_PER_S, resolutionS = 60)).isEqualTo(4)
            val boot1 = allAggregates(60).first()
            assertThat(boot1.bootSeq).isEqualTo(1)
            assertThat(boot1.startElapsedNs).isEqualTo(0)
            assertThat(boot1.sampleCount).isEqualTo(2)
            assertThat(listOf(boot1.levelFirstPct, boot1.levelLastPct)).containsExactly(81, 80).inOrder()
            // The sample taken before calibration has no current and does not count towards it.
            assertThat(listOf(boot1.currentMinUa, boot1.currentMeanUa, boot1.currentMaxUa))
                .containsExactly(-100_000L, -100_000L, -100_000L)
            assertThat(listOf(boot1.tempMinDeciC, boot1.tempMeanDeciC, boot1.tempMaxDeciC)).containsExactly(300, 310, 320)
            assertThat(allAggregates(60).map { it.bootSeq to it.startElapsedNs / NS_PER_S })
                .containsExactly(1 to 0L, 2 to 0L)
            // The minute the cut falls in keeps its samples until a later cut passes it whole.
            assertThat(allSamples().map { it.elapsedNs / NS_PER_S }).containsExactly(125L, 150L, 190L).inOrder()

            assertThat(dao.compactSamples(2, 240 * NS_PER_S, 60)).isEqualTo(3)
            assertThat(allAggregates(60).map { it.startElapsedNs / NS_PER_S to it.sampleCount })
                .containsExactly(0L to 2, 0L to 2, 120L to 2, 180L to 1)
                .inOrder()
        }

    @Test
    fun compactionIgnoresTheWallClock() =
        runTest {
            // A clock that was unset after boot makes these samples look decades old; their key says otherwise.
            dao.insert(sample(3, 10 * NS_PER_S, wallMs = 0))
            dao.insert(sample(3, 20 * NS_PER_S, wallMs = 0))
            assertThat(dao.compactSamples(cutBootSeq = 2, cutElapsedNs = Long.MAX_VALUE, resolutionS = 60)).isEqualTo(0)
            assertThat(dao.compactSamples(cutBootSeq = 3, cutElapsedNs = 15 * NS_PER_S, resolutionS = 60)).isEqualTo(0)
            assertThat(allSamples()).hasSize(2)
        }

    @Test
    fun smallChunksGiveTheSameAggregates() =
        runTest {
            val times = (0L until 600L step 7L)
            for (s in times) dao.insert(sample(1, s * NS_PER_S, levelPct = (100 - s / 10).toInt()))
            dao.insert(sample(2, 5 * NS_PER_S))
            assertThat(dao.compactSamples(3, 0, resolutionS = 60, chunkRows = 4)).isEqualTo(times.count() + 1)
            val chunked = allAggregates(60)

            sql("DELETE FROM sample_aggregate")
            for (s in times) dao.insert(sample(1, s * NS_PER_S, levelPct = (100 - s / 10).toInt()))
            dao.insert(sample(2, 5 * NS_PER_S))
            dao.compactSamples(3, 0, resolutionS = 60)
            assertThat(chunked).isEqualTo(allAggregates(60))
            assertThat(chunked).hasSize(11)
        }

    @Test
    fun minutesBecomeHours() =
        runTest {
            for (m in 0L until 120L) dao.insert(sample(1, m * 60 * NS_PER_S, tempDeciC = if (m < 60) 300 else 400))
            dao.compactSamples(2, 0, resolutionS = 60)
            assertThat(dao.compactAggregates(1, 90 * 60 * NS_PER_S, fromResolutionS = 60, toResolutionS = 3_600)).isEqualTo(60)
            val hour = allAggregates(3_600).single()
            assertThat(hour.sampleCount).isEqualTo(60)
            assertThat(hour.tempMeanDeciC).isEqualTo(300)
            assertThat(allAggregates(60)).hasSize(60)

            assertThat(dao.compactAggregates(2, 0, 60, 3_600)).isEqualTo(60)
            assertThat(allAggregates(3_600).map(SampleAggregateEntity::tempMeanDeciC)).containsExactly(300, 400).inOrder()
        }
}
