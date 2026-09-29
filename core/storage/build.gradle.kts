plugins {
    id("app.android.library")
    id("app.di")
    id("app.tiff.ios")
}

kotlin {
    sourceSets.commonTest.dependencies {
        implementation(libs.kotlinx.coroutines.test)
    }
    sourceSets.getByName("androidDeviceTest").dependencies {
        implementation(libs.androidx.testExt.junit)
    }
    sourceSets.androidMain.dependencies {
        implementation(project(":core:tiff-native"))
    }
    sourceSets.commonMain.dependencies {
        api(libs.kotlinx.io.core)
        implementation(libs.kotlinx.coroutines.core)
        implementation(libs.androidx.sqlite.bundled)
        implementation(project(":core:di"))
    }
}
