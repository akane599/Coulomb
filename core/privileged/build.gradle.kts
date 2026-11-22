// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    alias(libs.plugins.coulomb.android.library)
    alias(libs.plugins.coulomb.android.hilt)
}

android {
    buildFeatures.aidl = true
    defaultConfig.consumerProguardFiles("consumer-rules.pro")
    // JVM tests exercise the state machine with fakes; android.* stubs return defaults.
    testOptions.unitTests.isReturnDefaultValues = true
    testOptions.unitTests.all {
        // The worker's own tests run under Robolectric, which reaches FileDescriptor internals on
        // API 36 (as in :core:database).
        it.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
    }
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.common)

    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.hiddenapibypass)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
