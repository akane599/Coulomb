// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    alias(libs.plugins.coulomb.android.library)
}

android {
    testOptions.unitTests.all {
        // Robolectric reaches FileDescriptor internals on API 36 (as in :core:database).
        it.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
        // CaptureInsightsTest reads a validation capture only when given one.
        for (name in listOf("coulomb.capture", "coulomb.captureOut", "coulomb.captureUid")) {
            providers.gradleProperty(name).orNull?.let { value -> it.systemProperty(name, value) }
        }
    }
}

dependencies {
    // The model comes with analytics; SPEC 15.2 lets this module depend on analytics and database.
    api(projects.core.analytics)
    implementation(projects.core.database)
    // Evidence and thresholds are small JSON trees, read and written without generated serializers.
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
