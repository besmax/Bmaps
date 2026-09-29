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
