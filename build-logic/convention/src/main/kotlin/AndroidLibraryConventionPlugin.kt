// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import io.github.akane599.coulomb.buildlogic.UNIT_TEST_BUILD_TYPE
import io.github.akane599.coulomb.buildlogic.configureAndroidCommon
import io.github.akane599.coulomb.buildlogic.lib
import io.github.akane599.coulomb.buildlogic.libs
import io.github.akane599.coulomb.buildlogic.limitHostTestsTo
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.library")
            extensions.configure<LibraryExtension> {
                configureAndroidCommon(this)
                defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }
            extensions.configure<LibraryAndroidComponentsExtension> {
                beforeVariants { it.limitHostTestsTo(UNIT_TEST_BUILD_TYPE) }
            }
            dependencies {
                add("testImplementation", libs.lib("junit"))
                add("testImplementation", libs.lib("truth"))
            }
        }
    }
}
