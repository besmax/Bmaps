import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("com.android.application")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

android {
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    testOptions.unitTests.isIncludeAndroidResources = true
    sourceSets.getByName("test").kotlin.srcDir("src/sharedTest/kotlin")
    sourceSets.getByName("androidTest").kotlin.srcDir("src/sharedTest/kotlin")
}

dependencies {
    add("androidTestImplementation", libs.findLibrary("androidx-compose-ui-test").get())
    add("androidTestImplementation", libs.findLibrary("androidx-testExt-junit").get())
    add("testImplementation", libs.findLibrary("androidx-compose-ui-test").get())
    add("testImplementation", libs.findLibrary("androidx-testExt-junit").get())
    add("testImplementation", libs.findLibrary("robolectric").get())
}
