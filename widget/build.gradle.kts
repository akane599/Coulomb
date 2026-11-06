// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    alias(libs.plugins.coulomb.android.library)
    alias(libs.plugins.coulomb.android.compose)
}

dependencies {
    implementation(projects.core.model)
    implementation(libs.androidx.glance.appwidget)
    // Glance pulls work-runtime 2.7.1 transitively; pin the newest stable (SPEC 0.3.2).
    implementation(libs.androidx.work.runtime)
}
