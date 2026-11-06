// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    alias(libs.plugins.coulomb.jvm.library)
    alias(libs.plugins.wire)
}

// Kotlin classes for `dumpsys batterystats --proto` (S5) from the vendored AOSP protos (SPEC 5.1,
// src/main/proto/README.md). Only what the root message reaches is generated.
wire {
    root("android.service.batterystats.BatteryStatsServiceDumpProto")
    kotlin {
        emitDeclaredOptions = false
        emitAppliedOptions = false
    }
}

dependencies {
    api(projects.core.model)
    implementation(libs.wire.runtime)
    implementation(libs.okio)
}

// Golden tests read the committed fixtures (SPEC 16.2) from the repository root.
tasks.withType<Test>().configureEach {
    val fixtures = rootProject.layout.projectDirectory.dir("fixtures")
    inputs.dir(fixtures).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("coulomb.fixtures", fixtures.asFile.absolutePath)
}
