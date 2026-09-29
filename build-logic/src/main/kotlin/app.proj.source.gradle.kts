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
