import java.util.Properties
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    id("app.kmp.library")
    id("app.proj.source")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
val nativeSource = rootProject.layout.projectDirectory.dir("core/proj-native/src/main/cpp")
val projSource = layout.buildDirectory.dir("nativeSources/proj/proj-${libs.findVersion("proj").get().requiredVersion}")
val sqliteSource = layout.buildDirectory.dir("nativeSources/proj-sqlite/sqlite-amalgamation-${libs.findVersion("proj-sqlite").get().requiredVersion}")
val localProperties = Properties().apply {
    val path = rootProject.file("local.properties")
    if (path.exists()) path.inputStream().use(::load)
}
val sdk = providers.environmentVariable("ANDROID_HOME").orElse(localProperties.getProperty("sdk.dir", ""))
val cmake = providers.gradleProperty("bmaps.cmake").orElse(
    sdk.map { "$it/cmake/${libs.findVersion("native-cmake").get().requiredVersion}/bin/cmake" },
)

kotlin.targets.withType<KotlinNativeTarget>().configureEach {
    val targetName = name
    val output = layout.buildDirectory.dir("native/$targetName")
    val configure = tasks.register<Exec>("configureProj${targetName.replaceFirstChar(Char::uppercase)}") {
        dependsOn("prepareProj", "prepareProjSqlite")
        inputs.dir(nativeSource)
        inputs.dir(projSource)
        inputs.dir(sqliteSource)
        inputs.property("cmake", cmake)
        outputs.file(output.map { it.file("CMakeCache.txt") })
        commandLine(cmake.get(), "-S", nativeSource.asFile, "-B", output.get().asFile,
            "-DBMAPS_PROJ_SOURCE=${projSource.get().asFile.absolutePath}",
            "-DBMAPS_SQLITE_SOURCE=${sqliteSource.get().asFile.absolutePath}",
            "-DCMAKE_SYSTEM_NAME=iOS", "-DCMAKE_OSX_ARCHITECTURES=arm64",
            "-DCMAKE_OSX_SYSROOT=${if (targetName == "iosArm64") "iphoneos" else "iphonesimulator"}",
            "-DCMAKE_OSX_DEPLOYMENT_TARGET=15.0", "-DCMAKE_BUILD_TYPE=Release")
    }
    val build = tasks.register<Exec>("buildProj${targetName.replaceFirstChar(Char::uppercase)}") {
        dependsOn(configure)
        inputs.dir(nativeSource)
        inputs.dir(projSource)
        inputs.dir(sqliteSource)
        inputs.file(output.map { it.file("CMakeCache.txt") })
        outputs.dir(output.map { it.dir("lib") })
        commandLine(cmake.get(), "--build", output.get().asFile, "--target", "bmaps_proj", "--parallel", "4")
    }
    compilations.getByName("main").cinterops.create("proj") {
        definitionFile.set(project.file("src/nativeInterop/cinterop/proj.def"))
        includeDirs(nativeSource)
        extraOpts("-libraryPath", output.get().dir("lib").asFile.absolutePath)
        tasks.named(interopProcessingTaskName).configure { dependsOn(build) }
    }
}
