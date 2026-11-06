// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import io.github.akane599.coulomb.core.privileged.PrivilegedAccess
import javax.inject.Inject

@HiltAndroidApp
class CoulombApplication : Application() {
    @Inject lateinit var privileged: PrivilegedAccess

    override fun onCreate() {
        super.onCreate()
        // SPEC 4.1: T1 grants are re-verified at every start; Shizuku listeners attach here.
        privileged.start()
    }
}
