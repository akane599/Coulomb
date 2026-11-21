// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    alias(libs.plugins.coulomb.android.feature)
    alias(libs.plugins.coulomb.android.screenshot)
}

dependencies {
    implementation(projects.core.collect)
    implementation(projects.core.data)
    implementation(projects.core.datastore)
    implementation(projects.core.privileged)
    implementation(libs.androidx.activity.compose)

    testImplementation(libs.truth)
    // DataViewModelTest runs the real transfer over an in-memory database.
    testImplementation(projects.core.common)
    testImplementation(projects.core.database)
    testImplementation(libs.kotlinx.coroutines.test)
}
