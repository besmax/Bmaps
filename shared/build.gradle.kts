/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

plugins {
    id("app.shared")
}

kotlin {
    sourceSets.commonMain.dependencies {
        implementation(project(":core:di"))
        implementation(project(":domain:providers"))
        implementation(project(":domain:map-builder"))
        implementation(project(":core:database"))
        implementation(project(":core:storage"))
        implementation(project(":core:sharing"))
        implementation(project(":core:map-engine"))
        implementation(project(":core:location"))
        implementation(project(":core:network"))
        implementation(project(":core:datastore"))
        implementation(project(":feature:shell"))
        implementation(project(":feature:constructor"))
        implementation(project(":feature:library"))
        implementation(project(":feature:viewer"))
        implementation(libs.metro.viewmodel.compose)
        implementation(libs.navigation.compose)
    }
    sourceSets.commonTest.dependencies {
        implementation(libs.kotlinx.coroutines.test)
    }
}
