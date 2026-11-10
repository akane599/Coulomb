// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import io.github.akane599.coulomb.buildlogic.BASE_NAMESPACE
import io.github.akane599.coulomb.buildlogic.ManifestPermissions
import io.github.akane599.coulomb.buildlogic.UNIT_TEST_BUILD_TYPE
import io.github.akane599.coulomb.buildlogic.VerifyManifestPermissionsTask
import io.github.akane599.coulomb.buildlogic.configureAndroidCommon
import io.github.akane599.coulomb.buildlogic.libs
import io.github.akane599.coulomb.buildlogic.limitHostTestsTo
import io.github.akane599.coulomb.buildlogic.versionOf
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.attributes.Attribute
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.application")

            // SPEC 0.4: name and application id are placeholders kept in one Gradle property each.
            val appId = providers.gradleProperty("coulomb.applicationId").get()
            val appName = providers.gradleProperty("coulomb.appName").get()

            extensions.configure<ApplicationExtension> {
                namespace = BASE_NAMESPACE
                configureAndroidCommon(this)
                defaultConfig.apply {
                    applicationId = appId
                    targetSdk = libs.versionOf("targetSdk").toInt()
                    versionCode = providers.gradleProperty("coulomb.versionCode").get().toInt()
                    versionName = providers.gradleProperty("coulomb.versionName").get()
                    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                    resValue("string", "app_name", appName)
                }
                buildFeatures.apply {
                    buildConfig = true
                    resValues = true
                }
                buildTypes.apply {
                    getByName("release") {
                        isMinifyEnabled = true
                        isShrinkResources = true
                        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
                    }
                    // SPEC 15.6: debug, release and benchmark variants.
                    create("benchmark") {
                        initWith(getByName("release"))
                        signingConfig = signingConfigs.getByName("debug")
                        matchingFallbacks += "release"
                        // Its own package beside an installed Coulomb, whose data a benchmark run must
                        // never replace; removed on its own after the run (ADR-0050).
                        applicationIdSuffix = BENCHMARK_SUFFIX
                        resValue("string", "app_name", "$appName benchmark")
                    }
                }
                lint.checkDependencies = true
            }

            extensions.configure<ApplicationAndroidComponentsExtension> {
                beforeVariants { it.limitHostTestsTo(UNIT_TEST_BUILD_TYPE) }
                onVariants { variant ->
                    val taskName = "verify${variant.name.replaceFirstChar { it.uppercase() }}ManifestPermissions"
                    val verify =
                        tasks.register<VerifyManifestPermissionsTask>(taskName) {
                            group = "verification"
                            description = "Fails if the merged ${variant.name} manifest has INTERNET or an unlisted permission."
                            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
                            allowlist.set(layout.projectDirectory.file("permissions-allowlist.txt"))
                            libraryManifests.from(
                                variant.runtimeConfiguration.incoming
                                    .artifactView {
                                        lenient(true)
                                        attributes { attribute(ARTIFACT_TYPE, "android-manifest") }
                                    }.files,
                            )
                            applicationId.set(variant.applicationId)
                            forbidden.set(ManifestPermissions.FORBIDDEN)
                            report.set(layout.buildDirectory.file("reports/permissions/${variant.name}.txt"))
                        }
                    tasks.named("check").configure { dependsOn(verify) }
                }
            }
        }
    }
}

/** AGP's artifact-type attribute; "android-manifest" selects each dependency's AndroidManifest.xml. */
private val ARTIFACT_TYPE: Attribute<String> = Attribute.of("artifactType", String::class.java)

/**
 * The benchmark build's package suffix; `:benchmark` targets the same package. Not ".benchmark":
 * that is `:benchmark`'s own test package.
 */
private const val BENCHMARK_SUFFIX = ".bench"
