// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    alias(libs.plugins.coulomb.android.feature)
    alias(libs.plugins.coulomb.android.screenshot)
}

dependencies {
    implementation(projects.core.data)
    implementation(projects.core.collect)
    implementation(projects.core.privileged)
}
