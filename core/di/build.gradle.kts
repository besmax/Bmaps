plugins {
    id("app.android.library")
    id("app.di")
}

kotlin {
    sourceSets.commonMain.dependencies {
        api(libs.metro.viewmodel)
    }
}
