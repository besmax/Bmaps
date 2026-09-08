import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("app.android.library")
    id("app.compose.multiplatform")
    id("app.di")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    sourceSets.commonMain.dependencies {
        implementation(project(":core:di"))
        implementation(libs.findLibrary("metro-viewmodel-compose").get())
        implementation(libs.findLibrary("kotlinx-coroutines-core").get())
    }
    sourceSets.commonTest.dependencies {
        implementation(libs.findLibrary("kotlinx-coroutines-test").get())
    }
}
