/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

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
