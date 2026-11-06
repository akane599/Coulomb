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
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.common)

    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.hiddenapibypass)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
