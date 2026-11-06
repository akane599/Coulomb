// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import com.android.build.api.dsl.Lint
import io.github.akane599.coulomb.buildlogic.JDK_VERSION
import io.github.akane599.coulomb.buildlogic.configureKotlin
import io.github.akane599.coulomb.buildlogic.configureModuleBoundaries
import io.github.akane599.coulomb.buildlogic.configureStaticAnalysis
import io.github.akane599.coulomb.buildlogic.lib
import io.github.akane599.coulomb.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/** Pure Kotlin modules (`:core:model`, `:core:parsing`, `:core:analytics`, ...). */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.jvm")
            pluginManager.apply("com.android.lint")
            extensions.configure<KotlinJvmProjectExtension> { jvmToolchain(JDK_VERSION) }
            extensions.configure<Lint> {
                warningsAsErrors = true
                abortOnError = true
                lintConfig = rootProject.file("config/lint/lint.xml")
            }
            configureKotlin()
            configureStaticAnalysis()
            configureModuleBoundaries()
            dependencies {
                add("testImplementation", libs.lib("junit"))
                add("testImplementation", libs.lib("truth"))
            }
        }
    }
}
