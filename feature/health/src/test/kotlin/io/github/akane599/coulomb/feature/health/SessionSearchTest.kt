// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.feature.health

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class SessionSearchTest {
    private val row = "Yesterday, 1:20 PM Nov 7 81% to 38% in 11 h 10 min\u00A0· 3.8\u00A0%/h"

    @Test
    fun everyWordMustBeInTheRow() {
        assertTrue(matches(row, "nov 7", Locale.US))
        assertTrue(matches(row, "38%  yesterday", Locale.US))
        assertFalse(matches(row, "dec 7", Locale.US))
    }

    @Test
    fun aBlankQueryKeepsEveryRow() {
        assertTrue(matches(row, "", Locale.US))
        assertTrue(matches(row, "   ", Locale.US))
    }

    @Test
    fun caseFollowsTheLanguage() {
        // Turkish lowercases a dotted capital I to a dotted i, and I to a dotless one.
        assertTrue(matches("İyi", "iyi", Locale.forLanguageTag("tr")))
        assertTrue(matches("Iğdır", "ığdır", Locale.forLanguageTag("tr")))
    }
}
