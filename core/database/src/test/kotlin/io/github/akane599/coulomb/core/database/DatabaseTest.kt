// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before

/** An in-memory [CoulombDatabase] per test, with raw SQL helpers for checks the DAOs do not offer. */
abstract class DatabaseTest {
    protected lateinit var db: CoulombDatabase

    @Before
    fun open() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, CoulombDatabase::class.java).build()
    }

    @After
    fun close() = db.close()

    protected fun sql(statement: String) = db.openHelper.writableDatabase.execSQL(statement)

    protected suspend fun failure(block: suspend () -> Unit): Throwable? = runCatching { block() }.exceptionOrNull()

    protected fun string(query: String): String? =
        db.openHelper.readableDatabase
            .query(query)
            .use { if (it.moveToFirst()) it.getString(0) else null }

    protected fun long(query: String): Long =
        db.openHelper.readableDatabase
            .query(query)
            .use {
                check(it.moveToFirst()) { "no row: $query" }
                it.getLong(0)
            }
}
