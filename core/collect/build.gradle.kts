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
}
