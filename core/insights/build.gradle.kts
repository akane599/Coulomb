// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    alias(libs.plugins.coulomb.android.library)
}

dependencies {
    // The model comes with analytics; SPEC 15.2 lets this module depend on analytics and database.
    api(projects.core.analytics)
    implementation(projects.core.database)
    // Evidence and thresholds are small JSON trees, read and written without generated serializers.
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.kotlinx.coroutines.test)
}
