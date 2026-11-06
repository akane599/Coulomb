// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.buildlogic

import com.android.build.api.dsl.CommonExtension
import com.android.build.api.variant.HasHostTestsBuilder
import com.android.build.api.variant.HostTestBuilder
import com.android.build.api.variant.VariantBuilder
import org.gradle.api.JavaVersion
import org.gradle.api.Project

/** Settings shared by every Android module (SPEC 3.1, 15.1, 15.5). */
internal fun Project.configureAndroidCommon(extension: CommonExtension) {
    extension.apply {
        compileSdk = libs.versionOf("compileSdk").toInt()
        defaultConfig.minSdk = libs.versionOf("minSdk").toInt()
        if (namespace == null) namespace = derivedNamespace()

        compileOptions.sourceCompatibility = JavaVersion.toVersion(JDK_VERSION)
        compileOptions.targetCompatibility = JavaVersion.toVersion(JDK_VERSION)

        lint.apply {
            // SPEC 15.5: Android Lint with warnings as errors.
            warningsAsErrors = true
            abortOnError = true
            checkReleaseBuilds = true
            lintConfig = rootProject.file("config/lint/lint.xml")
        }
    }
    configureKotlin()
    configureStaticAnalysis()
    configureModuleBoundaries()
}

/** Host (JVM) unit tests run for one build type only, so `./gradlew test` runs each test once. */
internal const val UNIT_TEST_BUILD_TYPE = "debug"

internal fun VariantBuilder.limitHostTestsTo(buildType: String) {
    (this as? HasHostTestsBuilder)?.hostTests?.get(HostTestBuilder.UNIT_TEST_TYPE)?.enable = this.buildType == buildType
}
