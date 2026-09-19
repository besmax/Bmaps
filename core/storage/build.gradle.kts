plugins {
    id("app.android.library")
    id("app.di")
}

kotlin {
    sourceSets.commonMain.dependencies {
        api(libs.kotlinx.io.core)
        implementation(libs.kotlinx.coroutines.core)
        implementation(libs.androidx.sqlite.bundled)
        implementation(project(":core:di"))
    }
}
