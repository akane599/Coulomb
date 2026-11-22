// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb

import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.database.CoulombDatabase
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** When settings found at start are taken for a backup's (ADR-0062 §15). */
class FirstStartTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun aNewOrRestoredInstallIsAFirstStartOnce() {
        assertThat(FirstStart.check(folder.root)).isTrue()
        FirstStart.done(folder.root)
        assertThat(FirstStart.check(folder.root)).isFalse()
    }

    /** Audit DATA-008: a reset that failed, even once the database exists, runs at the next start. */
    @Test
    fun aFirstStartWhoseResetFailedStaysPending() {
        assertThat(FirstStart.check(folder.root)).isTrue()
        File(folder.root, CoulombDatabase.FILE_NAME).writeText("")
        assertThat(FirstStart.check(folder.root)).isTrue()
        FirstStart.done(folder.root)
        assertThat(FirstStart.check(folder.root)).isFalse()
    }

    @Test
    fun anInstallFromBeforeTheMarkHasItsDatabase() {
        File(folder.root, CoulombDatabase.FILE_NAME).writeText("")
        assertThat(FirstStart.check(folder.root)).isFalse()
        assertThat(FirstStart.check(folder.root)).isFalse()
    }
}
