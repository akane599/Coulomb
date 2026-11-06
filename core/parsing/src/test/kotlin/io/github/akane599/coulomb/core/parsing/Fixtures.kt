// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.parsing

import java.io.File

/** The committed SPEC 16.2 fixture sets; the build passes their root as `coulomb.fixtures`. */
internal object Fixtures {
    private val root = File(requireNotNull(System.getProperty("coulomb.fixtures")) { "coulomb.fixtures not set" })

    const val PHONE = "sm-s938b/S938BXXSDDZJ1"
    const val AVD = "avd-pixel-api37/16571990"
    val ALL = listOf(PHONE, AVD)

    fun file(
        device: String,
        name: String,
    ): File = File(root, "$device/$name")

    fun bytes(
        device: String,
        name: String,
    ): ByteArray = file(device, name).readBytes()
}
