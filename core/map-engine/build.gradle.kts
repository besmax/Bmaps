plugins {
    id("app.android.library")
    id("app.compose.multiplatform")
    id("app.serialization")
}

kotlin {
    sourceSets.commonMain.dependencies {
        implementation(libs.mapcompose.mp)
        api(libs.kotlinx.io.bytestring)
        api(libs.kotlinx.io.core)
        implementation(libs.kotlinx.coroutines.core)
    }
    sourceSets.commonTest.dependencies {
        implementation(libs.kotlinx.coroutines.test)
    }
}
