// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.akane599.coulomb.core.common.AppDispatchers
import io.github.akane599.coulomb.core.model.SnapshotKind
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

/** SPEC 14.4 "Developer console": every typed query answers on an empty and on a filled database. */
@RunWith(AndroidJUnit4::class)
internal class DeveloperConsoleTest : DrainDbTest() {
    private fun console() = DeveloperConsole(db, AppDispatchers.production())

    @Test
    fun everyQueryAnswersOnAnEmptyDatabase() =
        runTest {
            for (query in ConsoleQuery.entries) {
                val table = console().run(query)
                assertThat(table.columns).isNotEmpty()
                for (row in table.rows) assertThat(row).hasSize(table.columns.size)
            }
            assertThat(console().run(ConsoleQuery.SOURCES).rows).isEmpty()
        }

    @Test
    fun theLatestSnapshotsComeFirstWithTheirStoredCodesNamed() =
        runTest {
            db.deviceDao().enterBoot(1, "fp", BASE_WALL, 0)
            val first = heavy(0)
            val second = heavy(60, kind = SnapshotKind.LIGHT)

            val snapshots = console().run(ConsoleQuery.SNAPSHOTS)
            assertThat(
                snapshots.rows.map { it[0] },
            ).containsExactly(ConsoleValue.Time(wall(second)), ConsoleValue.Time(wall(first))).inOrder()
            assertThat(snapshots.rows.map { it[2] }).containsExactly(ConsoleValue.Text("LIGHT"), ConsoleValue.Text("HEAVY")).inOrder()
            assertThat(snapshots.rows.first()[1]).isEqualTo(ConsoleValue.Text("SCREEN_OFF"))
            assertThat(snapshots.rows.first()[3]).isEqualTo(ConsoleValue.Count(900))

            val storage = console().run(ConsoleQuery.STORAGE).rows.associate { it[0] to it[1] }
            assertThat(storage[ConsoleValue.Text("snapshot")]).isEqualTo(ConsoleValue.Count(2))
            assertThat(storage[ConsoleValue.Text("boot")]).isEqualTo(ConsoleValue.Count(1))
            assertThat((storage[ConsoleValue.Text("(file, bytes)")] as ConsoleValue.Count).count).isGreaterThan(0)
            assertThat(console().run(ConsoleQuery.SOURCES).rows).contains(listOf(ConsoleValue.Text("S5"), ConsoleValue.Text("ok")))
            assertThat(
                console()
                    .run(ConsoleQuery.BOOTS)
                    .rows
                    .single()
                    .first(),
            ).isEqualTo(ConsoleValue.Text("1"))
        }
}
