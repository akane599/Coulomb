// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.buildlogic

import com.google.common.truth.Truth.assertThat
import org.gradle.api.GradleException
import org.junit.Assert.assertThrows
import org.junit.Test

class ReleaseKeyTest {
    private val all =
        mapOf(
            ReleaseKey.STORE to "/keys/release.jks",
            ReleaseKey.STORE_PASSWORD to "one",
            ReleaseKey.ALIAS to "coulomb",
            ReleaseKey.KEY_PASSWORD to "two",
        )

    @Test
    fun `no variable means an unsigned release`() {
        assertThat(ReleaseKey.from { null }).isNull()
        assertThat(ReleaseKey.from { "" }).isNull()
    }

    @Test
    fun `all four make the key`() {
        assertThat(ReleaseKey.from(all::get)).isEqualTo(ReleaseKey("/keys/release.jks", "one", "coulomb", "two"))
    }

    @Test
    fun `some of them fail the build and name what is missing`() {
        val error = assertThrows(GradleException::class.java) { ReleaseKey.from((all - ReleaseKey.KEY_PASSWORD)::get) }
        assertThat(error).hasMessageThat().contains(ReleaseKey.KEY_PASSWORD)
        assertThat(error).hasMessageThat().doesNotContain("one")
    }

    @Test
    fun `the passwords are not printed`() {
        assertThat(ReleaseKey.from(all::get).toString()).isEqualTo("ReleaseKey(coulomb)")
    }
}
