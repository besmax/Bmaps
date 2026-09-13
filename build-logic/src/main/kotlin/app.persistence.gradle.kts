import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("app.android.library")
    id("app.di")
    id("app.serialization")
    id("com.google.devtools.ksp")
    id("androidx.room")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    sourceSets.commonMain.dependencies {
        api(libs.findLibrary("androidx-room-runtime").get())
        implementation(libs.findLibrary("androidx-sqlite-bundled").get())
        api(libs.findLibrary("kotlinx-coroutines-core").get())
    }
}

dependencies {
    listOf("kspAndroid", "kspIosArm64", "kspIosSimulatorArm64").forEach {
        add(it, libs.findLibrary("androidx-room-compiler").get())
    }
}

room { schemaDirectory("$projectDir/schemas") }
