/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

plugins {
    id("app.android.library")
    id("app.di")
    id("app.tiff.ios")
}

kotlin {
    sourceSets.commonTest.dependencies {
        implementation(libs.kotlinx.coroutines.test)
    }
    sourceSets.getByName("androidDeviceTest").dependencies {
        implementation(libs.androidx.testExt.junit)
    }
    sourceSets.androidMain.dependencies {
        implementation(project(":core:tiff-native"))
    }
    sourceSets.commonMain.dependencies {
        api(libs.kotlinx.io.core)
        implementation(libs.kotlinx.coroutines.core)
        implementation(libs.androidx.sqlite.bundled)
        implementation(project(":core:di"))
    }
}
