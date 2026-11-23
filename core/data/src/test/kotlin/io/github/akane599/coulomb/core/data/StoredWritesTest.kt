// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * Audit BUG-024: a write a screen starts must not end the process when storage fails. The scope
 * here is a view model's in all but its dispatcher, with a recorder where the thread's own handler
 * would be.
 */
@RunWith(AndroidJUnit4::class)
internal class StoredWritesTest : DrainDbTest() {
    private var uncaught: Throwable? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + CoroutineExceptionHandler { _, e -> uncaught = e })

    /** The database on a disk with no room left: SQLite's page limit is where it stands. */
    private fun fillTheDatabase() {
        runBlocking { heavy(1_000, wallMs = System.currentTimeMillis()) }
        val sql = db.openHelper.writableDatabase
        val pages =
            sql.query("PRAGMA page_count").use {
                it.moveToFirst()
                it.getLong(0)
            }
        sql.query("PRAGMA max_page_count = $pages").use { it.moveToFirst() }
    }

    @Test
    fun aFullDatabaseFailsTheWriteWithoutEndingTheProcess() {
        fillTheDatabase()
        val references = References(snapshots)
        var failed = false

        runBlocking {
            scope
                .launchWrite("test", failed = { failed = true }) {
                    repeat(MARKS) { references.mark("x".repeat(NAME_LENGTH) + it, System.currentTimeMillis()) }
                }.join()
        }

        assertThat(uncaught).isNull()
        assertThat(failed).isTrue()
    }

    @Test
    fun aDataStoreFailureIsCaughtToo() {
        var failed = false
        runBlocking { scope.launchWrite("test", failed = { failed = true }) { throw IOException("no space left") }.join() }

        assertThat(uncaught).isNull()
        assertThat(failed).isTrue()
    }

    @Test
    fun aCancelIsNotAFailureAndABugIsNotHidden() {
        var failed = false
        val waiting = scope.launchWrite("test", failed = { failed = true }) { awaitCancellation() }
        waiting.cancel()
        runBlocking { waiting.join() }
        assertThat(waiting.isCancelled).isTrue()
        assertThat(failed).isFalse()
        assertThat(uncaught).isNull()

        runBlocking { scope.launchWrite("test", failed = { failed = true }) { error("a bug") }.join() }
        assertThat(uncaught).isInstanceOf(IllegalStateException::class.java)
        assertThat(failed).isFalse()
    }

    private companion object {
        const val MARKS = 5_000
        const val NAME_LENGTH = 40
    }
}
