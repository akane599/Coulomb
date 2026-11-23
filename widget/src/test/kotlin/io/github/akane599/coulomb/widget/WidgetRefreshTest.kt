// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.widget

import android.app.Application
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Which widget parts a stored snapshot reads again (ADR-0058 §1). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class WidgetRefreshTest {
    private val all = WidgetKind.entries.toSet()

    /**
     * Audit BUG-020: the last night and the health estimate come from sessions and samples, which a
     * light snapshot changes too. Without platform dumps every snapshot is light, and those two
     * widgets kept what they first read in the process.
     */
    @Test
    fun aLightSnapshotReadsTheNightAndHealthAgainButNotTheDrains() {
        assertThat(reloads(WidgetKind.LEVEL, heavy = false, loaded = all)).isTrue()
        assertThat(reloads(WidgetKind.NIGHT, heavy = false, loaded = all)).isTrue()
        assertThat(reloads(WidgetKind.HEALTH, heavy = false, loaded = all)).isTrue()
        // The top drains are attribution, which only a heavy snapshot adds to.
        assertThat(reloads(WidgetKind.DRAINS, heavy = false, loaded = all)).isFalse()
    }

    @Test
    fun aHeavySnapshotOrAFirstReadLoadsEveryPart() {
        for (kind in WidgetKind.entries) {
            assertThat(reloads(kind, heavy = true, loaded = all)).isTrue()
            assertThat(reloads(kind, heavy = false, loaded = all - kind)).isTrue()
        }
    }
}
