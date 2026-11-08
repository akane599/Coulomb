// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.core.collect.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppNamingTest {
    private fun pkg(
        name: String,
        label: String? = null,
        icon: Boolean = false,
        shared: String? = null,
    ) = PackageFacts(name, label, icon, shared)

    @Test
    fun onePackageGivesItsLabelOrItsName() {
        assertThat(AppNaming.name(false, listOf(pkg("com.example.maps", "Maps", icon = true))))
            .isEqualTo(AppLabel("Maps", isSystem = false, iconPackage = "com.example.maps"))
        assertThat(AppNaming.name(false, listOf(pkg("com.example.bare")))?.label).isEqualTo("com.example.bare")
    }

    @Test
    fun sharedPackagesTakeTheSharedUserLabelAndTheFirstIcon() {
        val name =
            AppNaming.name(
                belowAppRange = false,
                packages =
                    listOf(
                        pkg("com.example.phone.service", "Phone services"),
                        pkg("com.example.dialer", "Phone", icon = true, shared = "Phone and messages"),
                        pkg("com.example.sms", "Messages", icon = true, shared = "Ignored, not first"),
                    ),
            )

        assertThat(name).isEqualTo(AppLabel("Phone and messages", isSystem = false, iconPackage = "com.example.dialer"))
    }

    @Test
    fun withoutASharedLabelTheIconPackageNamesTheUid() {
        val packages = listOf(pkg("com.example.a", "Helper"), pkg("com.example.b", "Camera", icon = true))
        assertThat(AppNaming.name(false, packages)?.label).isEqualTo("Camera")
        assertThat(AppNaming.name(false, listOf(pkg("com.example.a"), pkg("com.example.b", "Camera")))?.label).isEqualTo("Camera")
        assertThat(AppNaming.name(false, listOf(pkg("com.example.a"), pkg("com.example.b")))?.label).isEqualTo("com.example.a")
    }

    @Test
    fun systemIsSettingsRuleNotThePreinstalledFlag() {
        assertThat(AppNaming.name(true, listOf(pkg("android", "Android System", icon = true)))?.isSystem).isTrue()
        assertThat(AppNaming.name(false, listOf(pkg("com.android.systemui", "System UI")))?.isSystem).isTrue()
        // A preinstalled browser is an app like any other, as in Settings' list.
        assertThat(AppNaming.name(false, listOf(pkg("com.example.browser", "Browser", icon = true)))?.isSystem).isFalse()
        assertThat(AppNaming.name(false, emptyList())).isNull()
    }
}
