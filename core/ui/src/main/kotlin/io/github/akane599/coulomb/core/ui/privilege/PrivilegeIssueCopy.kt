// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.ui.privilege

import android.os.Build
import androidx.annotation.StringRes
import io.github.akane599.coulomb.core.model.PrivilegeIssue
import io.github.akane599.coulomb.core.ui.R

/**
 * The SPEC 4.5 copy for one issue: title, body, the primary action and, where ADB commands help,
 * the label of the secondary action that shows them.
 */
public data class PrivilegeIssueCopy(
    @param:StringRes val title: Int,
    @param:StringRes val body: Int,
    @param:StringRes val action: Int?,
    @param:StringRes val adbAction: Int?,
)

private val SHOW_ADB = R.string.privilege_show_adb

/**
 * The Android 17 wording only on Android 17 and later, where the official-Shizuku hang is known
 * (SPEC 3, 4.5); elsewhere a plain "didn't answer".
 */
public fun PrivilegeIssue.copy(sdkInt: Int = Build.VERSION.SDK_INT): PrivilegeIssueCopy =
    when (this) {
        PrivilegeIssue.MANAGER_NOT_INSTALLED -> {
            PrivilegeIssueCopy(
                R.string.privilege_basic_mode_title,
                R.string.privilege_basic_mode_body,
                R.string.privilege_basic_mode_action,
                adbAction = SHOW_ADB,
            )
        }

        PrivilegeIssue.MANAGER_STOPPED -> {
            PrivilegeIssueCopy(
                R.string.privilege_stopped_title,
                R.string.privilege_stopped_body,
                R.string.privilege_stopped_action,
                adbAction = null,
            )
        }

        PrivilegeIssue.PERMISSION_DENIED -> {
            PrivilegeIssueCopy(
                R.string.privilege_denied_title,
                R.string.privilege_denied_body,
                R.string.privilege_denied_action,
                adbAction = SHOW_ADB,
            )
        }

        PrivilegeIssue.AUTHORIZATION_HANG -> {
            PrivilegeIssueCopy(
                if (sdkInt >= Build.VERSION_CODES.CINNAMON_BUN) R.string.privilege_hang_title_android17 else R.string.privilege_hang_title,
                R.string.privilege_hang_body,
                null,
                adbAction = SHOW_ADB,
            )
        }

        PrivilegeIssue.SERVER_TOO_OLD -> {
            PrivilegeIssueCopy(R.string.privilege_too_old_title, R.string.privilege_too_old_body, null, adbAction = SHOW_ADB)
        }

        PrivilegeIssue.GRANTS_REVOKED -> {
            PrivilegeIssueCopy(
                R.string.privilege_revoked_title,
                R.string.privilege_revoked_body,
                R.string.privilege_revoked_action,
                adbAction = SHOW_ADB,
            )
        }
    }
