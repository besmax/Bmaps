/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

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
