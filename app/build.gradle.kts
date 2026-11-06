// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    alias(libs.plugins.coulomb.android.application)
    alias(libs.plugins.coulomb.android.compose)
    alias(libs.plugins.coulomb.android.hilt)
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.database)
    implementation(projects.core.datastore)
    implementation(projects.core.privileged)
    implementation(projects.core.parsing)
    implementation(projects.core.analytics)
    implementation(projects.core.collect)
    implementation(projects.core.insights)
    implementation(projects.core.designsystem)
    implementation(projects.core.ui)
    implementation(projects.feature.now)
    implementation(projects.feature.drain)
    implementation(projects.feature.appdetail)
    implementation(projects.feature.timeline)
    implementation(projects.feature.health)
    implementation(projects.feature.charging)
    implementation(projects.feature.actions)
    implementation(projects.feature.onboarding)
    implementation(projects.feature.settings)
    implementation(projects.widget)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.profileinstaller)

    debugImplementation(libs.leakcanary.android)
}
