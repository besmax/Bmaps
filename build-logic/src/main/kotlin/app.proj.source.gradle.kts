/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

import org.gradle.api.artifacts.VersionCatalogsExtension

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
listOf(Triple("Proj", "proj", "tar.gz"), Triple("ProjSqlite", "proj-sqlite", "zip")).forEach { (task, alias, type) ->
    val dependency = libs.findLibrary("$alias-source").get().get()
    val version = dependency.versionConstraint.requiredVersion
    val archive = configurations.create("${alias.replace("-", "")}Source") {
        isCanBeConsumed = false
        isTransitive = false
    }
    dependencies.add(archive.name, "${dependency.module}:$version@$type")
    tasks.register<PrepareNativeSource>("prepare$task") {
        archiveFile.set(layout.file(archive.elements.map { it.single().asFile }))
        archiveType.set(type)
        sha256.set(libs.findVersion("$alias-sha256").get().requiredVersion)
        destination.set(layout.buildDirectory.dir("nativeSources/$alias"))
    }
}
