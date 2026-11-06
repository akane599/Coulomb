// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    alias(libs.plugins.coulomb.android.test)
}

android {
    targetProjectPath = ":app"
    // SPEC 0.4: the application id lives only in gradle.properties.
    defaultConfig.testInstrumentationRunnerArguments["targetPackage"] =
        providers.gradleProperty("coulomb.applicationId").get()
    // Macrobenchmark runs against the app's `benchmark` build type (SPEC 15.6, 16.5).
    buildTypes {
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

androidComponents {
    beforeVariants { it.enable = it.buildType == "benchmark" }
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
}
