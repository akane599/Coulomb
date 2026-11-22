// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.actions

import android.database.sqlite.SQLiteFullException
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.collect.actions.FakePhone.Companion.APP
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

/** Audit BUG-059: a failed log write is a result the screen can say, never a crash or a second command. */
@RunWith(AndroidJUnit4::class)
class ActionLogFailureTest {
    private val phone = FakePhone()
    private val log = FakeLog()
    private val center = ActionCenter(phone, log, { true }, { 1_000L })
    private val restrict = ActionRequest.RestrictBackground(APP)

    @Test
    fun aRowThatCannotBeWrittenSendsNoCommand() =
        runTest {
            log.failing = SQLiteFullException("full")
            assertThat(center.run(restrict)).isEqualTo(ActionResult.Blocked(ActionBlock.NO_LOG))
            assertThat(phone.acts).isEmpty()
            assertThat(log.all).isEmpty()
        }

    @Test
    fun aRevertWhoseRowCannotBeUpdatedIsUnverifiedAndKeepsTheRecord() =
        runTest {
            val done = center.run(restrict) as ActionResult.Done
            val sent = phone.acts.size
            log.failing = SQLiteFullException("full")

            assertThat(center.revert(done.id)).isEqualTo(ActionResult.Unverified(done.id))
            // The undo went once and is not sent again; the row still says what Coulomb changed.
            assertThat(phone.acts).hasSize(sent + 1)
            assertThat(log.all.single().revertedWall).isNull()
            // Again: the phone holds the baseline, so no command goes, and the log still cannot be written.
            assertThat(center.revertAll()).containsExactly(ActionResult.Blocked(ActionBlock.NO_LOG))
            assertThat(phone.acts).hasSize(sent + 1)
        }
}
