// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.model.AutoReference
import io.github.akane599.coulomb.core.model.Provenance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class ReferencesTest : DrainDbTest() {
    private val references get() = References(snapshots)

    @Test
    fun aMarkNamesTheLatestHeavySnapshot() =
        runTest {
            heavy(1_000)
            val latest = heavy(2_000)

            val marked = checkNotNull(references.mark("  Before the update  ", createdWall = 5L))

            assertThat(marked.mark).isEqualTo(DrainSpan.Mark(latest, "Before the update", wall(latest)))
            assertThat(references.named.first()).containsExactly(marked)
        }

    @Test
    fun noHeavySnapshotIsNoMark() =
        runTest {
            assertThat(references.mark("Too soon", createdWall = 5L)).isNull()
        }

    /** Audit BUG-001: an older heavy snapshot is not this moment, so it is not marked. */
    @Test
    fun aHeavySnapshotOlderThanTheDebounceIsNoMark() =
        runTest {
            val latest = heavy(1_000)
            assertThat(references.mark("Late", createdWall = wall(latest) + References.MAX_AGE_MS + 1)).isNull()
            assertThat(references.mark("Now", createdWall = wall(latest) + References.MAX_AGE_MS)).isNotNull()
        }

    @Test
    fun theAutomaticReferencesAreNotNamedOnesAndCannotBeDeleted() =
        runTest {
            val a = heavy(1_000)
            reference(AutoReference.TODAY, a)
            val marked = checkNotNull(references.mark("Mine", createdWall = 5L))

            assertThat(references.named.first()).containsExactly(marked)
            references.delete(marked.id)
            assertThat(references.named.first()).isEmpty()
            assertThat(snapshots.autoReference(AutoReference.TODAY)).isNotNull()
        }

    @Test
    fun aLongNameIsCut() =
        runTest {
            heavy(1_000)
            val marked = checkNotNull(references.mark("x".repeat(100), createdWall = 5L))
            assertThat(marked.mark.name).hasLength(References.MAX_NAME)
        }

    @Test
    fun betweenTwoMarksReadsTheChainBetweenTheirSnapshots() =
        runTest {
            val a = heavy(1_000)
            val b = heavy(2_000)
            val c = heavy(3_000)
            store(window(a, b).copy(measuredMah = 40.0))
            store(window(b, c).copy(measuredMah = 60.0))
            val from = DrainSpan.Mark(a, "Before", wall(a))
            val to = DrainSpan.Mark(b, "After", wall(b))

            val between = checkNotNull(reader.read(DrainSpan.Between(from, to), empty()))
            val since = checkNotNull(reader.read(DrainSpan.Between(from, null), empty()))

            assertThat(between.drains.fromWallMs).isEqualTo(wall(a))
            assertThat(between.drains.toWallMs).isEqualTo(wall(b))
            assertThat(between.drains.measuredMah).isEqualTo(40.0)
            assertThat(since.drains.toWallMs).isEqualTo(wall(c))
            assertThat(since.drains.measuredMah).isEqualTo(100.0)
            assertThat(since.drains.measuredProvenance).isEqualTo(Provenance.MEASURED)
        }

    /** Retention took the windows after the mark: the rest of the chain is a shorter span, not this one. */
    @Test
    fun aMarkWhoseFirstWindowsArePrunedReadsNothingRatherThanLess() =
        runTest {
            val a = heavy(1_000)
            val b = heavy(2_000)
            val c = heavy(3_000)
            store(window(b, c).copy(measuredMah = 60.0))
            val from = DrainSpan.Mark(a, "Before", wall(a))

            assertThat(reader.read(DrainSpan.Between(from, null), empty())).isNull()
            assertThat(reader.read(DrainSpan.Between(DrainSpan.Mark(b, "Later", wall(b)), null), empty())).isNotNull()
        }

    @Test
    fun aMarkWhoseSnapshotIsGoneReadsNothing() =
        runTest {
            heavy(1_000)
            assertThat(reader.read(DrainSpan.Between(DrainSpan.Mark(9_999, "Gone", 0), null), empty())).isNull()
        }
}
