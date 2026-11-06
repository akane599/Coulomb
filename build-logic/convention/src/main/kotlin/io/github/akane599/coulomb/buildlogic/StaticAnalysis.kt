// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.buildlogic

import com.diffplug.gradle.spotless.SpotlessExtension
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/** detekt and ktlint (through Spotless), both with the Compose rule sets (SPEC 15.5). */
internal fun Project.configureStaticAnalysis() {
    pluginManager.apply("io.gitlab.arturbosch.detekt")
    pluginManager.apply("com.diffplug.spotless")

    extensions.configure<DetektExtension> {
        buildUponDefaultConfig = true
        parallel = true
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    }
    dependencies {
        add("detektPlugins", libs.lib("composeRules-detekt"))
    }
    // detekt 1.23 embeds its own Kotlin compiler; keep Gradle from aligning it to the build's Kotlin.
    val detektKotlin = DETEKT_KOTLIN_VERSION
    configurations.matching { it.name == "detekt" }.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlin") useVersion(detektKotlin)
        }
    }

    val ktlintVersion = libs.versionOf("ktlint")
    val composeRules = "io.nlopez.compose.rules:ktlint:${libs.versionOf("composeRules")}"
    val licenseHeader = rootProject.file(LICENSE_HEADER)
    extensions.configure<SpotlessExtension> {
        kotlin {
            target("src/**/*.kt")
            targetExclude("**/build/**")
            ktlint(ktlintVersion).customRuleSets(listOf(composeRules))
            licenseHeaderFile(licenseHeader, KOTLIN_HEADER_DELIMITER)
        }
        kotlinGradle {
            target("*.gradle.kts")
            ktlint(ktlintVersion)
            licenseHeaderFile(licenseHeader, GRADLE_HEADER_DELIMITER)
        }
    }
}

/** SPDX header for every Kotlin source and build script (ADR-0012). */
internal const val LICENSE_HEADER = "config/spotless/license-header.txt"

/** The header goes above the first line that starts real code. */
internal const val KOTLIN_HEADER_DELIMITER = "(package |import |@file:)"
internal const val GRADLE_HEADER_DELIMITER = "(plugins|pluginManagement|dependencyResolutionManagement|import |rootProject)"

/** The Kotlin version detekt 1.23.8 was compiled against. */
private const val DETEKT_KOTLIN_VERSION = "2.0.21"
