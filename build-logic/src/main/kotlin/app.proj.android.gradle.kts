import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("com.android.library")
    id("app.proj.source")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
val projSource = layout.buildDirectory.dir("nativeSources/proj/proj-${libs.findVersion("proj").get().requiredVersion}")
val sqliteSource = layout.buildDirectory.dir("nativeSources/proj-sqlite/sqlite-amalgamation-${libs.findVersion("proj-sqlite").get().requiredVersion}")

android {
    namespace = "bes.max.bmaps.core.proj"
    compileSdk = libs.findVersion("android-compileSdk").get().requiredVersion.toInt()
    ndkVersion = libs.findVersion("android-ndk").get().requiredVersion
    defaultConfig {
        minSdk = libs.findVersion("android-minSdk").get().requiredVersion.toInt()
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild.cmake {
            arguments += listOf("-DBMAPS_PROJ_SOURCE=${projSource.get().asFile.absolutePath}",
                "-DBMAPS_SQLITE_SOURCE=${sqliteSource.get().asFile.absolutePath}", "-DANDROID_STL=c++_shared")
        }
    }
    externalNativeBuild.cmake {
        path = file("src/main/cpp/CMakeLists.txt")
        version = libs.findVersion("native-cmake").get().requiredVersion
    }
}

tasks.configureEach {
    if (name.startsWith("configureCMake") || name == "preBuild") dependsOn("prepareProj", "prepareProjSqlite")
}
