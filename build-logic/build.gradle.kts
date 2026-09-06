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
    implementation(plugin(libs.plugins.kotlinMultiplatform))
    implementation(plugin(libs.plugins.androidMultiplatformLibrary))
    implementation(plugin(libs.plugins.composeMultiplatform))
    implementation(plugin(libs.plugins.composeCompiler))
    implementation(plugin(libs.plugins.metro))
}
