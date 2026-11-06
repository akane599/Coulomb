// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // libsu (root tier, SPEC 15.3) is only published on JitPack.
        maven("https://jitpack.io") {
            content { includeGroup("com.github.topjohnwu.libsu") }
        }
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "Coulomb"

include(":app")
include(":core:model")
include(":core:common")
include(":core:database")
include(":core:datastore")
include(":core:privileged")
include(":core:parsing")
include(":core:analytics")
include(":core:collect")
include(":core:insights")
include(":core:designsystem")
include(":core:ui")
include(":feature:now")
include(":feature:drain")
include(":feature:appdetail")
include(":feature:timeline")
include(":feature:health")
include(":feature:charging")
include(":feature:actions")
include(":feature:onboarding")
include(":feature:settings")
include(":widget")
include(":benchmark")
