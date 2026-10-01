/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

plugins {
    id("app.android.library")
    id("app.compose.multiplatform")
    id("app.serialization")
    id("app.di")
    id("app.proj.ios")
}

kotlin {
    sourceSets.commonMain.dependencies {
        implementation(project(":core:di"))
        implementation(libs.mapcompose.mp)
        api(libs.kotlinx.io.bytestring)
        api(libs.kotlinx.io.core)
        implementation(libs.kotlinx.coroutines.core)
    }
    sourceSets.androidMain.dependencies {
        implementation(project(":core:proj-native"))
    }
    sourceSets.commonTest.dependencies {
        implementation(libs.kotlinx.coroutines.test)
    }
}
