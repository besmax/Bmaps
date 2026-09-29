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
