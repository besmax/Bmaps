plugins {
    id("app.android.library")
    id("app.di")
}

kotlin {
    sourceSets.commonMain.dependencies {
        api(libs.kotlinx.io.core)
        implementation(libs.kotlinx.coroutines.core)
        implementation(project(":core:di"))
    }
}
