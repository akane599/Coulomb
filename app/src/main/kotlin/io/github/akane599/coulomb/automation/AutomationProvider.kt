// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.automation

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.akane599.coulomb.core.common.AutomationContract
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** Android creates the provider and the receiver, not Hilt, so they ask the application. */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface AutomationEntryPoint {
    fun automationStore(): AutomationStore

    fun automationEngine(): AutomationEngine
}

/**
 * SPEC 12.7's read-only provider: `content://<authority>/v1/{info,level,rates,night,drains}`,
 * each a list of figures in [AutomationContract.COLUMNS]. The manifest guards it with the
 * automation permission and keeps it disabled while the setting is off; a query while it is off
 * gets no cursor, as does a path that is not one of the five. The projection, selection and sort order are not used: every answer has every
 * column in the contract's order. Nothing can be written.
 */
class AutomationProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val context = context ?: return null
        val path = uri.path?.trim('/')?.takeIf { it in AutomationContract.PATHS } ?: return null
        val store = EntryPointAccessors.fromApplication(context.applicationContext, AutomationEntryPoint::class.java).automationStore()
        // On the caller's binder thread: the first query after a snapshot reads stored rows, the rest are served from memory.
        val rows = runBlocking { withTimeoutOrNull(BUDGET_MS) { store.rows(path) } } ?: return null
        return MatrixCursor(AutomationContract.COLUMNS.toTypedArray(), rows.size).apply { rows.forEach { addRow(it.cells()) } }
    }

    override fun getType(uri: Uri): String? =
        if (uri.path?.trim('/') in AutomationContract.PATHS) "vnd.android.cursor.dir/vnd.${uri.authority}.figure" else null

    /**
     * Android asks this for a caller that does not hold the permission (getTypeAnonymous,
     * ContentProvider android-17.0.0_r1 L1814, android-16.0.0_r4 L1806, whose default answers with
     * [getType]); such a caller learns nothing, not even which paths there are.
     */
    override fun getTypeAnonymous(uri: Uri): String? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = throw UnsupportedOperationException(READ_ONLY)

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException(READ_ONLY)

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException(READ_ONLY)

    private companion object {
        const val BUDGET_MS = 5_000L
        const val READ_ONLY = "The automation provider is read-only"
    }
}
