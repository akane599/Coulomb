// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    alias(libs.plugins.coulomb.android.library)
    alias(libs.plugins.coulomb.android.compose)
    alias(libs.plugins.coulomb.android.hilt)
    alias(libs.plugins.coulomb.android.screenshot)
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.analytics)
    implementation(projects.core.data)
    implementation(projects.core.insights)
    implementation(projects.core.designsystem)
    implementation(projects.core.ui)
    implementation(libs.androidx.glance.appwidget)
    // Glance pulls work-runtime 2.7.1 transitively; pin the newest stable (SPEC 0.3.2).
    implementation(libs.androidx.work.runtime)

    testImplementation(libs.truth)
}
