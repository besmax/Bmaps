/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("app.kmp.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    sourceSets.commonMain.dependencies {
        implementation(libs.findBundle("compose-core").get())
    }
}

pluginManager.withPlugin("com.android.kotlin.multiplatform.library") {
    kotlin.sourceSets.named("androidMain") {
        dependencies {
            implementation(libs.findLibrary("compose-uiTooling").get())
        }
    }
}
