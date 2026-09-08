plugins {
    id("app.android.library")
    id("app.serialization")
}

kotlin {
    sourceSets.commonMain.dependencies {
        api(project(":domain:providers"))
        api(project(":core:map-engine"))
        api(libs.kotlinx.coroutines.core)
    }
    sourceSets.commonTest.dependencies {
        implementation(libs.kotlinx.coroutines.test)
    }
}
