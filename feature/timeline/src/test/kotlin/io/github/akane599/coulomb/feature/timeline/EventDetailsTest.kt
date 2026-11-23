// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.timeline

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.github.akane599.coulomb.core.data.TimelineTrack
import io.github.akane599.coulomb.core.data.TrackSpan
import io.github.akane599.coulomb.core.designsystem.theme.CoulombTheme
import io.github.akane599.coulomb.core.ui.format.rememberFormats
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneOffset

/** Audit DATA-003: a tapped stretch says which of its ends were seen and which are only where the read stops. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = "w412dp-h900dp-xhdpi")
class EventDetailsTest {
    @get:Rule
    val compose = createComposeRule()

    private val span = TrackSpan(45 * MINUTE, 90 * MINUTE)

    private fun show(picked: Picked) {
        compose.setContent {
            CoulombTheme { EventDetails(picked, emptyMap(), ZoneOffset.UTC, rememberFormats(), nowMs = 2 * HOUR) {} }
        }
    }

    @Test
    fun aCutStretchSaysBeforeOrLaterAndAtLeast() {
        show(Picked(TimelineTrack.CHARGING, listOf(span), startKnown = false, endKnown = false, whole = false))

        // One line: "before <start> to <end> or later · at least <length>", whatever the clock's format.
        compose.onNodeWithText("before ", substring = true).assertExists()
        compose.onNodeWithText(" or later · at least ", substring = true).assertExists()
    }

    @Test
    fun aWholeStretchIsUnqualified() {
        show(Picked(TimelineTrack.CHARGING, listOf(span)))

        compose.onNodeWithText(" to ", substring = true).assertExists()
        compose.onNodeWithText("before", substring = true).assertDoesNotExist()
        compose.onNodeWithText("or later", substring = true).assertDoesNotExist()
        compose.onNodeWithText("at least", substring = true).assertDoesNotExist()
    }

    /** A run of stretches bounds its first start, its last end and its time in all the same way. */
    @Test
    fun aRunOfStretchesWithACutEndSaysSo() {
        val run = listOf(TrackSpan(45 * MINUTE, 50 * MINUTE), TrackSpan(60 * MINUTE, 90 * MINUTE))
        show(Picked(TimelineTrack.AWAKE, run, startKnown = true, endKnown = false, whole = false))

        compose.onNodeWithText("or later", substring = true).assertExists()
        compose.onNodeWithText("at least", substring = true).assertExists()
        compose.onNodeWithText("before", substring = true).assertDoesNotExist()
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}
