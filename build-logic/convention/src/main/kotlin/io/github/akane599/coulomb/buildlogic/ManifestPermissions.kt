// SPDX-FileCopyrightText: The Coulomb contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.akane599.coulomb.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.w3c.dom.Element
import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * SPEC 1.2 and 15.3/15.4: the merged manifest must never contain INTERNET (or the other
 * permissions 15.4 lists as not declared), and any permission outside the committed
 * allowlist fails the build so that no library can add one silently.
 */
public object ManifestPermissions {
    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    private val PERMISSION_TAGS = listOf("uses-permission", "uses-permission-sdk-23")

    /** SPEC 15.4 "Not declared". */
    public val FORBIDDEN: Set<String> =
        setOf(
            "android.permission.INTERNET",
            "android.permission.READ_LOGS",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_BACKGROUND_LOCATION",
            "android.permission.READ_PHONE_STATE",
            "android.permission.READ_BASIC_PHONE_STATE",
        )

    public data class Result(
        val declared: Set<String>,
        val forbidden: Set<String>,
        val unexpected: Set<String>,
    ) {
        val ok: Boolean get() = forbidden.isEmpty() && unexpected.isEmpty()
    }

    public fun usesPermissions(manifest: File): Set<String> {
        val factory =
            DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
        val document = factory.newDocumentBuilder().parse(manifest)
        return PERMISSION_TAGS
            .flatMap { tag ->
                val nodes = document.getElementsByTagName(tag)
                (0 until nodes.length).map { (nodes.item(it) as Element).getAttributeNS(ANDROID_NS, "name") }
            }.filter { it.isNotBlank() }
            .toSortedSet()
    }

    /** Allowlist lines may use `${applicationId}`; `#` starts a comment. */
    public fun parseAllowlist(
        text: String,
        applicationId: String,
    ): Set<String> =
        text
            .lineSequence()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .map { it.replace("\${applicationId}", applicationId) }
            .toSortedSet()

    /**
     * Libraries whose own manifest requests a forbidden permission. This catches a library that
     * wants INTERNET even when an app manifest strips it with `tools:node="remove"` (SPEC 15.3).
     * The key is the manifest's parent directory, which names the transformed library.
     */
    public fun forbiddenLibraryRequests(
        manifests: Iterable<File>,
        forbidden: Set<String> = FORBIDDEN,
    ): Map<String, Set<String>> =
        manifests
            .filter { it.isFile }
            .associate { it.parentFile.name to usesPermissions(it).intersect(forbidden) }
            .filterValues { it.isNotEmpty() }
            .toSortedMap()

    public fun evaluate(
        declared: Set<String>,
        allowed: Set<String>,
        forbidden: Set<String> = FORBIDDEN,
    ): Result =
        Result(
            declared = declared,
            forbidden = declared.intersect(forbidden).toSortedSet(),
            unexpected = (declared - allowed - forbidden).toSortedSet(),
        )
}

@CacheableTask
public abstract class VerifyManifestPermissionsTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    public abstract val mergedManifest: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    public abstract val allowlist: RegularFileProperty

    /** The AndroidManifest.xml of every library on the variant's runtime classpath. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    public abstract val libraryManifests: ConfigurableFileCollection

    @get:Input
    public abstract val applicationId: Property<String>

    @get:Input
    public abstract val forbidden: SetProperty<String>

    @get:OutputFile
    public abstract val report: RegularFileProperty

    @TaskAction
    public fun verify() {
        val declared = ManifestPermissions.usesPermissions(mergedManifest.get().asFile)
        val allowed = ManifestPermissions.parseAllowlist(allowlist.get().asFile.readText(), applicationId.get())
        val result = ManifestPermissions.evaluate(declared, allowed, forbidden.get())
        val libraryRequests = ManifestPermissions.forbiddenLibraryRequests(libraryManifests.files, forbidden.get())
        report.get().asFile.writeText(
            buildString {
                appendLine("# Merged manifest permissions (${applicationId.get()})")
                declared.forEach { appendLine(it) }
                appendLine()
                appendLine("forbidden present: ${result.forbidden.joinToString().ifEmpty { "none" }}")
                appendLine("not in allowlist: ${result.unexpected.joinToString().ifEmpty { "none" }}")
                appendLine("library manifests scanned: ${libraryManifests.files.size}")
                appendLine("libraries requesting forbidden permissions: ${libraryRequests.ifEmpty { "none" }}")
            },
        )
        if (libraryRequests.isNotEmpty()) {
            throw GradleException("Libraries request forbidden permissions (SPEC 1.2, 15.3): $libraryRequests")
        }
        if (result.forbidden.isNotEmpty()) {
            throw GradleException("Merged manifest declares forbidden permissions (SPEC 1.2, 15.4): ${result.forbidden.joinToString()}")
        }
        if (result.unexpected.isNotEmpty()) {
            throw GradleException(
                "Merged manifest declares permissions missing from ${allowlist.get().asFile.name}: " +
                    "${result.unexpected.joinToString()}. Find the library that adds them; allowlist only with an ADR.",
            )
        }
    }
}
