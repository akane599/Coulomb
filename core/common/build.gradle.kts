// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    alias(libs.plugins.coulomb.jvm.library)
}

dependencies {
    api(projects.core.model)
    api(libs.kotlinx.coroutines.core)
}

// AutomationContractTest reads the pinned contract, so a change to the file runs the test again.
tasks.withType<Test>().configureEach {
    inputs.file(rootProject.layout.projectDirectory.file("docs/automation-api-v1.json")).withPropertyName("automationContract")
}
