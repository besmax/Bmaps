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
    id("app.ios.keychain-test")
}

kotlin {
    sourceSets.commonMain.dependencies {
        implementation(project(":core:di"))
        api(libs.androidx.datastore.preferences)
        api(libs.kotlinx.coroutines.core)
    }
    sourceSets.iosMain.dependencies {
        implementation(libs.okio)
    }
    sourceSets.commonTest.dependencies {
        implementation(libs.kotlinx.coroutines.test)
    }
}
