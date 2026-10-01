/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

import java.util.Properties
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    id("app.kmp.library")
    id("app.tiff.source")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
val nativeSource = rootProject.layout.projectDirectory.dir("core/tiff-native/src/main/cpp")
val tiffSource = layout.buildDirectory.dir("libtiff/tiff-${libs.findVersion("libtiff").get().requiredVersion}")
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
    val configure = tasks.register<Exec>("configureTiff${targetName.replaceFirstChar(Char::uppercase)}") {
        dependsOn("prepareLibtiff")
        inputs.dir(nativeSource)
        inputs.dir(tiffSource)
        inputs.property("cmake", cmake)
        outputs.file(output.map { it.file("CMakeCache.txt") })
        commandLine(cmake.get(), "-S", nativeSource.asFile, "-B", output.get().asFile,
            "-DBMAPS_TIFF_SOURCE=${tiffSource.get().asFile.absolutePath}",
            "-DCMAKE_SYSTEM_NAME=iOS", "-DCMAKE_OSX_ARCHITECTURES=arm64",
            "-DCMAKE_OSX_SYSROOT=${if (targetName == "iosArm64") "iphoneos" else "iphonesimulator"}",
            "-DCMAKE_OSX_DEPLOYMENT_TARGET=15.0", "-DCMAKE_BUILD_TYPE=Release")
    }
    val build = tasks.register<Exec>("buildTiff${targetName.replaceFirstChar(Char::uppercase)}") {
        dependsOn(configure)
        inputs.dir(nativeSource)
        inputs.dir(tiffSource)
        inputs.file(output.map { it.file("CMakeCache.txt") })
        outputs.dir(output.map { it.dir("lib") })
        commandLine(cmake.get(), "--build", output.get().asFile, "--target", "bmaps_dem", "--parallel", "4")
    }
    compilations.getByName("main").cinterops.create("dem") {
        definitionFile.set(project.file("src/nativeInterop/cinterop/dem.def"))
        includeDirs(nativeSource)
        extraOpts("-libraryPath", output.get().dir("lib").asFile.absolutePath)
        tasks.named(interopProcessingTaskName).configure { dependsOn(build) }
    }
}
