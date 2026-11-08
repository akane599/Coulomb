// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    alias(libs.plugins.coulomb.android.library)
    alias(libs.plugins.coulomb.android.hilt)
}

android {
    testOptions.unitTests.all {
        // Robolectric reaches FileDescriptor internals on API 36 (as in :core:database).
        it.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
    }
}

dependencies {
    api(projects.core.model)
    api(projects.core.common)
    // TimeLeft is part of DischargeSummary.
    api(projects.core.analytics)
    implementation(projects.core.database)
    implementation(projects.core.datastore)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
