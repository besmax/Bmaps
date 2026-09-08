import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("app.kmp.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    sourceSets.commonMain.dependencies {
        api(libs.findLibrary("kotlinx-serialization-json").get())
    }
}
