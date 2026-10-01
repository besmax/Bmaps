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
}

kotlin {
    sourceSets.commonMain.dependencies {
        api(libs.metro.viewmodel)
    }
}
