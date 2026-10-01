/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

import org.gradle.api.artifacts.VersionCatalogsExtension

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
val dependency = libs.findLibrary("libtiff-source").get().get()
val tiffVersion = dependency.versionConstraint.requiredVersion
val expectedHash = libs.findVersion("libtiff-sha256").get().requiredVersion

val archive = configurations.create("libtiffSource") {
    isCanBeConsumed = false
    isTransitive = false
}
dependencies.add(archive.name, "${dependency.module}:$tiffVersion@tar.gz")

tasks.register<PrepareLibtiff>("prepareLibtiff") {
    archiveFile.set(layout.file(archive.elements.map { it.single().asFile }))
    sha256.set(expectedHash)
    destination.set(layout.buildDirectory.dir("libtiff"))
}
