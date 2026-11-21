// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.buildlogic

import org.gradle.api.GradleException

/** The name of the signing config a release gets when its key is given. */
internal const val RELEASE_SIGNING = "release"

/**
 * The owner's release key, as the environment names it (SPEC 15.6, ADR-0067). Nothing of it is in
 * the repository: a build without these variables leaves the release unsigned, and a build with
 * only some of them fails, so a half-set environment never passes for either.
 */
internal data class ReleaseKey(
    val store: String,
    val storePassword: String,
    val alias: String,
    val keyPassword: String,
) {
    // A data class would print the passwords.
    override fun toString(): String = "ReleaseKey($alias)"

    internal companion object {
        const val STORE = "COULOMB_KEYSTORE"
        const val STORE_PASSWORD = "COULOMB_KEYSTORE_PASSWORD"
        const val ALIAS = "COULOMB_KEY_ALIAS"
        const val KEY_PASSWORD = "COULOMB_KEY_PASSWORD"
        private val NAMES = listOf(STORE, STORE_PASSWORD, ALIAS, KEY_PASSWORD)

        /** The key [env] holds, or null when it names none of it. */
        fun from(env: (String) -> String?): ReleaseKey? {
            val given = NAMES.associateWith { env(it)?.takeIf(String::isNotEmpty) }
            val missing = given.filterValues { it == null }.keys
            return when {
                missing.size == NAMES.size -> {
                    null
                }

                missing.isNotEmpty() -> {
                    throw GradleException("Release signing needs ${missing.joinToString()} as well.")
                }

                else -> {
                    ReleaseKey(
                        given.getValue(STORE)!!,
                        given.getValue(STORE_PASSWORD)!!,
                        given.getValue(ALIAS)!!,
                        given.getValue(KEY_PASSWORD)!!,
                    )
                }
            }
        }
    }
}
