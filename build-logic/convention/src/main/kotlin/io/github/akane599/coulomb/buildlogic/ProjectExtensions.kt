// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.buildlogic

import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.getByType

/** Kotlin package and Android namespace root. The installable application id lives in gradle.properties. */
internal const val BASE_NAMESPACE = "io.github.akane599.coulomb"

/** SPEC 15.1: JDK 21 toolchain. */
internal const val JDK_VERSION = 21

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.versionOf(alias: String): String = findVersion(alias).get().requiredVersion

internal fun VersionCatalog.lib(alias: String): Provider<MinimalExternalModuleDependency> = findLibrary(alias).get()

/** `:core:model` becomes `io.github.akane599.coulomb.core.model`. */
internal fun Project.derivedNamespace(): String = BASE_NAMESPACE + path.replace(':', '.').replace('-', '_')

internal val Project.isCoreModule: Boolean
    get() = path.startsWith(":core:")
