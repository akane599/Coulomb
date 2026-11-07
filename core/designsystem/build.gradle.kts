// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    alias(libs.plugins.coulomb.android.library)
    alias(libs.plugins.coulomb.android.compose)
}

dependencies {
    api(libs.androidx.compose.foundation)
    api(libs.androidx.compose.material3)
}

// The token parity test reads DESIGN.md from the repository root (SPEC 11.12 step 5, ADR-0024).
tasks.withType<Test>().configureEach {
    val design = rootProject.layout.projectDirectory.file("DESIGN.md")
    inputs.file(design).withPathSensitivity(PathSensitivity.NONE)
    systemProperty("coulomb.designMd", design.asFile.absolutePath)
}

// The theme's own CompositionLocals are the one sanctioned place for them (SPEC 11.7).
spotless {
    kotlin {
        suppressLintsFor {
            step = "ktlint"
            shortCode = "compose:compositionlocal-allowlist"
            path = "src/main/kotlin/io/github/akane599/coulomb/core/designsystem/theme/Theme.kt"
        }
    }
}
