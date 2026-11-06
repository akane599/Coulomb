// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType

/**
 * The module dependency rules of SPEC 15.2. A module may declare a project dependency only
 * on the modules listed for it; `null` targets are matched by prefix.
 */
public object ModuleGraph {
    private const val ANY_CORE = ":core:*"
    private const val ANY = "*"

    private val rules: Map<String, Set<String>> =
        mapOf(
            ":app" to setOf(ANY),
            ":core:model" to emptySet(),
            ":core:common" to setOf(":core:model"),
            ":core:database" to setOf(":core:model"),
            ":core:datastore" to setOf(":core:model"),
            ":core:privileged" to setOf(":core:model", ":core:common"),
            ":core:parsing" to setOf(":core:model"),
            ":core:analytics" to setOf(":core:model"),
            ":core:collect" to setOf(":core:privileged", ":core:parsing", ":core:analytics", ":core:database"),
            ":core:insights" to setOf(":core:analytics", ":core:database"),
            ":core:designsystem" to emptySet(),
            ":core:ui" to setOf(":core:designsystem", ":core:model"),
            ":feature:*" to setOf(ANY_CORE),
            ":widget" to setOf(ANY_CORE),
            ":wear" to setOf(":core:model"),
            ":benchmark" to setOf(":app"),
        )

    /** Returns the dependencies of [module] that SPEC 15.2 does not allow. */
    public fun violations(
        module: String,
        dependencies: Set<String>,
    ): List<String> {
        val allowed =
            rules[module]
                ?: rules.entries.firstOrNull { (key, _) -> key.endsWith(":*") && module.startsWith(key.dropLast(1)) }?.value
                ?: return listOf("$module has no entry in SPEC 15.2; add one to ModuleGraph before using it")
        return dependencies
            .filter { it != module }
            .filterNot { dep -> allowed.any { rule -> matches(rule, dep, module) } }
            .sorted()
    }

    private fun matches(
        rule: String,
        dependency: String,
        module: String,
    ): Boolean =
        when {
            rule == ANY -> {
                dependency != ":benchmark"
            }

            rule.endsWith(":*") -> {
                dependency.startsWith(rule.dropLast(1)) &&
                    !(module.startsWith(":feature:") && dependency.startsWith(":feature:"))
            }

            else -> {
                rule == dependency
            }
        }
}

@CacheableTask
public abstract class CheckModuleBoundariesTask : DefaultTask() {
    @get:Input
    public abstract val modulePath: Property<String>

    @get:Input
    public abstract val projectDependencies: SetProperty<String>

    @get:OutputFile
    public abstract val report: RegularFileProperty

    @TaskAction
    public fun check() {
        val module = modulePath.get()
        val deps = projectDependencies.get()
        val violations = ModuleGraph.violations(module, deps)
        report.get().asFile.writeText(
            buildString {
                appendLine("module: $module")
                appendLine("dependencies: ${deps.sorted().joinToString()}")
                appendLine("violations: ${violations.joinToString().ifEmpty { "none" }}")
            },
        )
        if (violations.isNotEmpty()) {
            throw GradleException("$module breaks the SPEC 15.2 module rules by depending on: ${violations.joinToString()}")
        }
    }
}

internal fun Project.configureModuleBoundaries() {
    val projectPath = path
    val deps =
        provider {
            configurations
                .flatMap { configuration -> configuration.dependencies.withType<ProjectDependency>().map { it.path } }
                .toSet()
        }
    val check =
        tasks.register<CheckModuleBoundariesTask>("checkModuleBoundaries") {
            group = "verification"
            description = "Verifies project dependencies against SPEC 15.2."
            modulePath.set(projectPath)
            projectDependencies.set(deps)
            report.set(layout.buildDirectory.file("reports/module-boundaries.txt"))
        }
    tasks.matching { it.name == "check" }.configureEach { dependsOn(check) }
}
