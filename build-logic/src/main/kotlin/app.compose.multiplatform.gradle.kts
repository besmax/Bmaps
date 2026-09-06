import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("app.kmp.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    sourceSets.commonMain.dependencies {
        implementation(libs.findBundle("compose-core").get())
    }
}

pluginManager.withPlugin("com.android.kotlin.multiplatform.library") {
    kotlin.sourceSets.named("androidMain") {
        dependencies {
            implementation(libs.findLibrary("compose-uiTooling").get())
        }
    }
}
