// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.data

import android.database.SQLException
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Runs a write a screen started. One that fails for storage (a full disk, an I/O error: Room's
 * [SQLException], DataStore's [IOException]) is logged and told to [failed] instead of ending the
 * process: a view model's scope has no handler, so the exception would reach the thread's (audit
 * BUG-024, as BUG-049 and BUG-059 before it). The screen shows the stored value and so stays as it
 * was. A cancel is not a failure, and any other exception is a bug and is not caught.
 */
public fun CoroutineScope.launchWrite(
    tag: String,
    failed: () -> Unit = {},
    block: suspend () -> Unit,
): Job =
    launch {
        try {
            block()
        } catch (e: SQLException) {
            Log.w(tag, "write failed", e)
            failed()
        } catch (e: IOException) {
            Log.w(tag, "write failed", e)
            failed()
        }
    }
