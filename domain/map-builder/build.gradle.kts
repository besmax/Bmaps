/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

plugins {
    id("app.android.library")
    id("app.serialization")
    id("app.di")
}

kotlin {
    sourceSets.commonMain.dependencies {
        api(project(":domain:providers"))
        api(project(":core:map-engine"))
        api(libs.kotlinx.coroutines.core)
        implementation(project(":core:di"))
        implementation(project(":core:storage"))
        implementation(project(":core:mbtiles"))
        implementation(project(":core:database"))
    }
    sourceSets.commonTest.dependencies {
        implementation(libs.kotlinx.coroutines.test)
    }
    sourceSets.androidMain.dependencies {
        api(libs.androidx.work.runtime)
        implementation(libs.androidx.core.ktx)
        implementation(libs.kotlinx.coroutines.guava)
    }
}
