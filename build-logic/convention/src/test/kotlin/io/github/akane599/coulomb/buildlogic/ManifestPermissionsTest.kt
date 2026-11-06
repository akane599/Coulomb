// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.buildlogic

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ManifestPermissionsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun manifest(vararg permissions: String) =
        tmp.newFile().apply {
            writeText(
                """
                <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="x">
                ${permissions.joinToString("\n") { """<uses-permission android:name="$it"/>""" }}
                <uses-permission-sdk-23 android:name="android.permission.READ_LOGS"/>
                </manifest>
                """.trimIndent(),
            )
        }

    @Test
    fun `INTERNET in the merged manifest is reported as forbidden`() {
        val declared = ManifestPermissions.usesPermissions(manifest("android.permission.INTERNET", "android.permission.DUMP"))
        val result = ManifestPermissions.evaluate(declared, allowed = setOf("android.permission.DUMP"))

        assertThat(result.ok).isFalse()
        assertThat(result.forbidden).containsExactly("android.permission.INTERNET", "android.permission.READ_LOGS")
        assertThat(result.unexpected).isEmpty()
    }

    @Test
    fun `permission outside the allowlist is reported as unexpected`() {
        val declared = setOf("android.permission.DUMP", "android.permission.CAMERA")
        val result = ManifestPermissions.evaluate(declared, allowed = setOf("android.permission.DUMP"))

        assertThat(result.ok).isFalse()
        assertThat(result.unexpected).containsExactly("android.permission.CAMERA")
    }

    @Test
    fun `allowlisted permissions pass and the applicationId placeholder expands`() {
        val allowed =
            ManifestPermissions.parseAllowlist(
                """
                # comment
                android.permission.DUMP   # inline comment
                ${'$'}{applicationId}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
                """.trimIndent(),
                applicationId = "io.example",
            )
        val declared = setOf("android.permission.DUMP", "io.example.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")

        assertThat(ManifestPermissions.evaluate(declared, allowed).ok).isTrue()
    }

    @Test
    fun `an allowlist entry cannot excuse a forbidden permission`() {
        val result =
            ManifestPermissions.evaluate(
                declared = setOf("android.permission.INTERNET"),
                allowed = setOf("android.permission.INTERNET"),
            )

        assertThat(result.forbidden).containsExactly("android.permission.INTERNET")
    }

    @Test
    fun `a library asking for INTERNET is caught even if the app strips it`() {
        val library = tmp.newFolder("some-network-sdk-1.0")
        val manifest =
            java.io.File(library, "AndroidManifest.xml").apply {
                writeText(
                    """<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="y">
                    <uses-permission android:name="android.permission.INTERNET"/></manifest>""",
                )
            }
        val clean =
            tmp.newFolder("clean-1.0").let { dir ->
                java.io.File(dir, "AndroidManifest.xml").apply { writeText("<manifest package=\"z\"/>") }
            }

        val requests = ManifestPermissions.forbiddenLibraryRequests(listOf(manifest, clean))

        assertThat(requests).containsExactly("some-network-sdk-1.0", setOf("android.permission.INTERNET"))
    }
}
