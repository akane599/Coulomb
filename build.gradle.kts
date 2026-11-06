// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.android.lint) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.wire) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.spotless)
    alias(libs.plugins.roborazzi) apply false
}

// The build-logic unit tests (manifest and module-graph checks) run with every root `check`.
tasks.named("check") {
    dependsOn(gradle.includedBuild("build-logic").task(":convention:check"))
}

// Root-level formatting for code outside the Gradle modules (build-logic and root scripts).
spotless {
    val ktlintVersion = libs.versions.ktlint.get()
    // SPDX header (ADR-0012); the module convention uses the same file and delimiters.
    val licenseHeader = file("config/spotless/license-header.txt")
    kotlin {
        target("build-logic/**/*.kt")
        targetExclude("**/build/**")
        ktlint(ktlintVersion)
        licenseHeaderFile(licenseHeader, "(package |import |@file:)")
    }
    kotlinGradle {
        target("*.gradle.kts", "build-logic/**/*.gradle.kts")
        targetExclude("**/build/**")
        ktlint(ktlintVersion)
        licenseHeaderFile(licenseHeader, "(plugins|pluginManagement|dependencyResolutionManagement|import |rootProject)")
    }
}
