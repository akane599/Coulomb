// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.privileged

/** Build facts the app module supplies (SPEC 4.3 `.version(BuildConfig.VERSION_CODE)`, `.debuggable(BuildConfig.DEBUG)`). */
public data class PrivilegeConfig(
    val packageName: String,
    val uid: Int,
    /** The APK versionCode; the worker reports the same value from PackageInfo (handshake). */
    val workerVersion: Long,
    val debuggable: Boolean,
    /** `Build.FINGERPRINT`, to detect OS updates (SPEC 4.1). */
    val fingerprint: String,
)
