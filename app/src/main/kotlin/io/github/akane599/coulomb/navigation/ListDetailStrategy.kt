// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme

/**
 * SPEC 10.10's list-detail panes: where [split] holds (expanded widths with room for two panes),
 * a detail entry on top of a list entry shows beside it instead of over it. Entries opt in through
 * their metadata ([LIST] and [DETAIL]); everything else falls through to the next strategy.
 * Navigation3's own strategy contract (`androidx.navigation3.scene.SceneStrategy`, 1.2.1).
 */
internal class ListDetailStrategy<T : Any>(
    private val split: Boolean,
) : SceneStrategy<T> {
    override fun SceneStrategyScope<T>.calculateScene(entries: List<NavEntry<T>>): Scene<T>? {
        if (!split || entries.size < 2) return null
        val detail = entries.last()
        val list = entries[entries.lastIndex - 1]
        if (detail.metadata[DETAIL] != true || list.metadata[LIST] != true) return null
        return ListDetailScene(list, detail, previousEntries = entries.dropLast(1))
    }

    companion object {
        const val LIST: String = "coulomb.pane.list"
        const val DETAIL: String = "coulomb.pane.detail"

        /** Metadata for an entry that lists what a detail opens. */
        val list: Map<String, Any> = mapOf(LIST to true)

        /** Metadata for an entry shown beside its list. */
        val detail: Map<String, Any> = mapOf(DETAIL to true)
    }
}

/**
 * The list at a third of the width (between [ListMin] and [ListMax]) and the detail in the rest,
 * split by a hairline. The scene keeps the list's key, so choosing another row swaps only the
 * detail; back leaves the list on its own.
 */
private data class ListDetailScene<T : Any>(
    val list: NavEntry<T>,
    val detail: NavEntry<T>,
    override val previousEntries: List<NavEntry<T>>,
) : Scene<T> {
    override val key: Any = list.contentKey
    override val entries: List<NavEntry<T>> = listOf(list, detail)
    override val content: @Composable () -> Unit = {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val listWidth = (maxWidth * LIST_SHARE).coerceIn(ListMin, ListMax)
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(listWidth).fillMaxHeight()) { list.Content() }
                VerticalDivider(thickness = Hairline, color = CoulombTheme.colors.hairline)
                Box(Modifier.weight(1f).fillMaxHeight()) { detail.Content() }
            }
        }
    }
}

/** Both panes need this much at 100% text; larger text needs it in proportion. */
internal val PaneMin: Dp = 360.dp
private const val LIST_SHARE = 0.4f
private val ListMin = PaneMin
private val ListMax = 480.dp
private val Hairline = 1.dp
