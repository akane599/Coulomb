// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity

/**
 * A raw identifier ("PowerManagerService.WakeLocks", "app.halcyon/.sync.SyncJob") that may
 * wrap after its separators, or inside a long camel-case name between its words, rather than
 * mid-word: a zero-width space marks each, but only inside a run without spaces long enough to
 * need it, so a name that can break between words still does and leaves no fragment alone on a
 * line. TalkBack does not read it.
 */
public fun breakable(raw: String): String =
    raw.replace(LONG_RUN) { run ->
        run.value
            .replace(LONG_WORD) { word -> word.value.replace(CAMEL_HUMP, "$1$ZERO_WIDTH_SPACE$2") }
            .replace(SEPARATORS) { it.value + ZERO_WIDTH_SPACE }
    }

private val SEPARATORS = Regex("[./_:]")

/** A name between separators too long for a narrow column, which alone breaks at its humps. */
private val LONG_WORD = Regex("[^./_:\\s]{16,}")

/** A lower-case letter followed by an upper-case one: "Notification|Wake|Lock". */
private val CAMEL_HUMP = Regex("(\\p{Ll})(\\p{Lu})")

/** A run this long may not fit a narrow column at large text; shorter ones break between words. */
private val LONG_RUN = Regex("\\S{16,}")
private const val ZERO_WIDTH_SPACE = "\u200B"

/**
 * A wakelock tag in a list is cut at two lines; at large text it wraps in full, since the cut
 * would drop the end that tells two tags apart.
 */
@Composable
public fun tagLines(): Int = if (LocalDensity.current.fontScale > LARGE_TEXT) Int.MAX_VALUE else TAG_LINES

private const val LARGE_TEXT = 1.3f
private const val TAG_LINES = 2
