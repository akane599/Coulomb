// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    `kotlin-dsl`
}

group = "io.github.akane599.coulomb.buildlogic"

kotlin {
    jvmToolchain(21)
}

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.ksp.gradlePlugin)
    compileOnly(libs.detekt.gradlePlugin)
    compileOnly(libs.spotless.gradlePlugin)
    compileOnly(libs.room.gradlePlugin)
    compileOnly(libs.roborazzi.gradlePlugin)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}

tasks.validatePlugins {
    enableStricterValidation = true
    failOnWarning = true
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "coulomb.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "coulomb.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "coulomb.android.compose"
            implementationClass = "AndroidComposeConventionPlugin"
        }
        register("androidFeature") {
            id = "coulomb.android.feature"
            implementationClass = "AndroidFeatureConventionPlugin"
        }
        register("androidHilt") {
            id = "coulomb.android.hilt"
            implementationClass = "AndroidHiltConventionPlugin"
        }
        register("androidTest") {
            id = "coulomb.android.test"
            implementationClass = "AndroidTestConventionPlugin"
        }
        register("androidScreenshot") {
            id = "coulomb.android.screenshot"
            implementationClass = "AndroidScreenshotConventionPlugin"
        }
        register("jvmLibrary") {
            id = "coulomb.jvm.library"
            implementationClass = "JvmLibraryConventionPlugin"
        }
    }
}
