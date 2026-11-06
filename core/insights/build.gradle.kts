// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    alias(libs.plugins.coulomb.android.library)
}

dependencies {
    implementation(projects.core.analytics)
    implementation(projects.core.database)
}
