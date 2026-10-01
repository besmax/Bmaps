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
