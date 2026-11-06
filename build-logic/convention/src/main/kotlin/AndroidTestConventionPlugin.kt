// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import com.android.build.api.dsl.TestExtension
import io.github.akane599.coulomb.buildlogic.configureAndroidCommon
import io.github.akane599.coulomb.buildlogic.libs
import io.github.akane599.coulomb.buildlogic.versionOf
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidTestConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.test")
            extensions.configure<TestExtension> {
                configureAndroidCommon(this)
                defaultConfig.targetSdk = libs.versionOf("targetSdk").toInt()
                defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }
        }
    }
}
