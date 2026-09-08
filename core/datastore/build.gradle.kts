plugins {
    id("app.android.library")
    id("app.di")
}

kotlin {
    sourceSets.commonMain.dependencies {
        implementation(project(":core:di"))
        api(libs.androidx.datastore.preferences)
        api(libs.kotlinx.coroutines.core)
    }
    sourceSets.iosMain.dependencies {
        implementation(libs.okio)
    }
    sourceSets.commonTest.dependencies {
        implementation(libs.kotlinx.coroutines.test)
    }
}
