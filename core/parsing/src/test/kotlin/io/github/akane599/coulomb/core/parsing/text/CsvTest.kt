// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.parsing.text

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CsvTest {
    @Test
    fun plainFieldsSplitAtEveryComma() {
        assertThat(Csv.split("9,1000,l,cpu,10,20,0")).containsExactly("9", "1000", "l", "cpu", "10", "20", "0").inOrder()
    }

    @Test
    fun aQuotedFieldKeepsItsCommasAndLosesItsQuotes() {
        assertThat(Csv.split("9,0,l,kwl,\"a,b\",233,652")).containsExactly("9", "0", "l", "kwl", "a,b", "233", "652").inOrder()
        assertThat(Csv.split("\"last,one\"")).containsExactly("last,one")
        assertThat(Csv.split("x,\"\",1")).containsExactly("x", "", "1").inOrder()
    }

    @Test
    fun aQuoteInsideAQuotedFieldEndsItOnlyBeforeACommaOrTheEnd() {
        assertThat(Csv.split("\"say \"hi\" now\",1")).containsExactly("say \"hi\" now", "1").inOrder()
    }

    @Test
    fun emptyFieldsAreKept() {
        assertThat(Csv.split("")).containsExactly("")
        assertThat(Csv.split("a,,b,")).containsExactly("a", "", "b", "").inOrder()
    }

    @Test
    fun anUnclosedQuoteRunsToTheEnd() {
        assertThat(Csv.split("a,\"b,c")).containsExactly("a", "b,c").inOrder()
    }

    @Test
    fun anOpenQuoteIsFoundOnlyAtTheStartOfAField() {
        assertThat(Csv.hasOpenQuote("9,1,l,jb,\"name")).isTrue()
        // A quote closes a field only before a comma or the end of the line.
        assertThat(Csv.hasOpenQuote("9,1,l,jb,\"na\"me")).isTrue()
        assertThat(Csv.hasOpenQuote("9,1,l,jb,\"na\"me\",5")).isFalse()
        assertThat(Csv.hasOpenQuote("9,1,l,jb,\"name\",5,1")).isFalse()
        assertThat(Csv.hasOpenQuote("a\"b,c")).isFalse()
        assertThat(Csv.hasOpenQuote("")).isFalse()
    }

    @Test
    fun aQuoteInsideAnUnquotedFieldIsJustACharacter() {
        assertThat(Csv.split("a\"b,c")).containsExactly("a\"b", "c").inOrder()
    }
}
