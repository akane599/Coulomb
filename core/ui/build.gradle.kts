// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    alias(libs.plugins.coulomb.android.library)
    alias(libs.plugins.coulomb.android.compose)
}

android {
    // InsightCopyTest reads the module's string resources.
    testOptions.unitTests.isIncludeAndroidResources = true
    testOptions.unitTests.all {
        // Robolectric reaches FileDescriptor internals on API 36 (as in :core:database).
        it.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
    }
}

dependencies {
    api(projects.core.designsystem)
    api(projects.core.model)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
}
