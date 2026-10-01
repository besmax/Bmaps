/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.provider.Provider
import org.gradle.plugin.use.PluginDependency

plugins {
    `kotlin-dsl`
}

// Resolve plugin markers from the same catalog aliases used by the main build.
fun DependencyHandler.plugin(alias: Provider<PluginDependency>) = alias.map {
    create("${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}")
}

dependencies {
    implementation(plugin(libs.plugins.androidNativeLibrary))
    implementation(plugin(libs.plugins.androidApplication))
    implementation(plugin(libs.plugins.kotlinMultiplatform))
    implementation(plugin(libs.plugins.androidMultiplatformLibrary))
    implementation(plugin(libs.plugins.composeMultiplatform))
    implementation(plugin(libs.plugins.composeCompiler))
    implementation(plugin(libs.plugins.metro))
    implementation(plugin(libs.plugins.kotlinSerialization))
    implementation(plugin(libs.plugins.ksp))
    implementation(plugin(libs.plugins.room))
}
