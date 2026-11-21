// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.ui.story

/**
 * Turkish suffixes that follow vowel harmony, for the two places a Drain Story needs them (SPEC
 * 9.3's own examples: "Instagram'ın", "%91'inde"). Each returns null where the rule cannot be
 * applied safely, and the copy then uses a wording that needs no suffix.
 */
internal object TurkishSuffix {
    private const val BACK_UNROUNDED = "aı"
    private const val FRONT_UNROUNDED = "ei"
    private const val BACK_ROUNDED = "ou"
    private const val FRONT_ROUNDED = "öü"
    private const val VOWELS = BACK_UNROUNDED + FRONT_UNROUNDED + BACK_ROUNDED + FRONT_ROUNDED

    /**
     * [name] in the genitive, as a proper name takes it: "Instagram'ın", "Halcyon'un", "Kamera'nın".
     * Null when the name does not end in a letter or has no vowel to harmonise with, as "X" or
     * "Maps 2" would be.
     */
    fun genitive(name: String): String? {
        val lower = name.lowercase(TURKISH)
        val last = lower.lastOrNull()?.takeIf { it.isLetter() } ?: return null
        val vowel = lower.lastOrNull { it in VOWELS } ?: return null
        val harmony =
            when (vowel) {
                in BACK_UNROUNDED -> 'ı'
                in FRONT_UNROUNDED -> 'i'
                in BACK_ROUNDED -> 'u'
                else -> 'ü'
            }
        val buffer = if (last in VOWELS) "n" else ""
        return "$name’$buffer${harmony}n"
    }

    /**
     * The suffix of "in N percent of": "%91’inde", "%60’ında", "%6’sında". It follows how the number
     * is said, so only whole numbers from 0 to 100 have one.
     */
    fun percentIn(percent: Int): String? {
        if (percent !in 0..HUNDRED) return null
        val ones = percent % TEN
        return when {
            percent == 0 -> "ında"
            percent == HUNDRED -> "ünde"
            ones != 0 -> ONES[ones - 1]
            else -> TENS[percent / TEN - 1]
        }
    }

    private const val TEN = 10
    private const val HUNDRED = 100
    private val TURKISH = java.util.Locale.forLanguageTag("tr")

    /** bir, iki, üç, dört, beş, altı, yedi, sekiz, dokuz. */
    private val ONES = listOf("inde", "sinde", "ünde", "ünde", "inde", "sında", "sinde", "inde", "unda")

    /** on, yirmi, otuz, kırk, elli, altmış, yetmiş, seksen, doksan. */
    private val TENS = listOf("unda", "sinde", "unda", "ında", "sinde", "ında", "inde", "inde", "ında")
}
