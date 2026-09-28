import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("com.android.library")
    id("app.tiff.source")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
val tiffSource = layout.buildDirectory.dir("libtiff/tiff-${libs.findVersion("libtiff").get().requiredVersion}")

android {
    namespace = "bes.max.bmaps.core.tiff"
    compileSdk = libs.findVersion("android-compileSdk").get().requiredVersion.toInt()
    ndkVersion = libs.findVersion("android-ndk").get().requiredVersion
    defaultConfig {
        minSdk = libs.findVersion("android-minSdk").get().requiredVersion.toInt()
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild.cmake {
            arguments += "-DBMAPS_TIFF_SOURCE=${tiffSource.get().asFile.absolutePath}"
        }
    }
    externalNativeBuild.cmake {
        path = file("src/main/cpp/CMakeLists.txt")
        version = libs.findVersion("native-cmake").get().requiredVersion
    }
}

tasks.configureEach {
    if (name.startsWith("configureCMake") || name == "preBuild") dependsOn("prepareLibtiff")
}
