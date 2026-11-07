// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    alias(libs.plugins.coulomb.android.library)
    alias(libs.plugins.coulomb.android.hilt)
}

android {
    testOptions.unitTests.all {
        // Robolectric reaches FileDescriptor internals on API 36 (as in :core:database).
        it.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
    }
}

// Mapping tests read the committed fixtures (SPEC 16.2) from the repository root.
tasks.withType<Test>().configureEach {
    val fixtures = rootProject.layout.projectDirectory.dir("fixtures")
    inputs.dir(fixtures).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("coulomb.fixtures", fixtures.asFile.absolutePath)
}

/** Copies the stress test's two fixture dumps into an asset folder, `stress/` (SPEC 16.3, ADR-0019). */
abstract class StressFixtures : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val dumps: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun copy() {
        val folder = output.get().asFile.resolve("stress")
        folder.deleteRecursively()
        folder.mkdirs()
        dumps.forEach { it.copyTo(folder.resolve(it.name)) }
    }
}

val stressFixtures =
    tasks.register<StressFixtures>("stressFixtures") {
        val phone = rootProject.layout.projectDirectory.dir("fixtures/sm-s938b/S938BXXSDDZJ1")
        dumps.from(phone.file("batterystats.proto.bin"), phone.file("batterystats.checkin.txt"))
    }

androidComponents {
    onVariants { variant ->
        variant.deviceTests.values.forEach { test ->
            test.sources.assets?.addGeneratedSourceDirectory(stressFixtures, StressFixtures::output)
        }
    }
}

dependencies {
    implementation(projects.core.privileged)
    implementation(projects.core.parsing)
    implementation(projects.core.analytics)
    implementation(projects.core.database)

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.truth)
}
