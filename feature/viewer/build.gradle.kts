/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

plugins {
    id("app.feature")
}

kotlin {
    sourceSets.commonMain.dependencies {
        implementation(project(":core:ui"))
        implementation(project(":domain:map-builder"))
        implementation(project(":core:map-engine"))
        implementation(project(":core:datastore"))
    }
}
