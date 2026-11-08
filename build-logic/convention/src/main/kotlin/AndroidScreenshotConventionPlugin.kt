// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import com.android.build.api.dsl.CommonExtension
import io.github.akane599.coulomb.buildlogic.lib
import io.github.akane599.coulomb.buildlogic.libs
import io.github.takahirom.roborazzi.RoborazziExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * Screenshot tests on the JVM (SPEC 11.12, 16.1): Roborazzi over Robolectric's native graphics, so
 * the build server renders screens without a device.
 * - `recordRoborazziDebug` writes the images to `src/test/screenshots`, where review reads them.
 * - `verifyRoborazziDebug` compares against them.
 * - A plain `test` run composes every screen without writing images.
 */
class AndroidScreenshotConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("io.github.takahirom.roborazzi")
            extensions.configure<CommonExtension> {
                testOptions.unitTests.isIncludeAndroidResources = true
                testOptions.unitTests.all {
                    // Robolectric reaches FileDescriptor internals on API 36 (as in :core:database).
                    it.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
                }
            }
            extensions.configure<RoborazziExtension> {
                outputDir.set(layout.projectDirectory.dir("src/test/screenshots"))
            }
            dependencies {
                add("testImplementation", platform(libs.lib("androidx-compose-bom")))
                add("testImplementation", libs.lib("androidx-compose-ui-test-junit4"))
                add("testImplementation", libs.lib("robolectric"))
                add("testImplementation", libs.lib("roborazzi"))
                add("testImplementation", libs.lib("roborazzi-compose"))
                add("testImplementation", libs.lib("junit"))
                add("debugImplementation", libs.lib("androidx-compose-ui-test-manifest"))
            }
        }
    }
}
