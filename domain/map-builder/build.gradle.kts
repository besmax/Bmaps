plugins {
    id("app.android.library")
    id("app.serialization")
    id("app.di")
}

kotlin {
    sourceSets.commonMain.dependencies {
        api(project(":domain:providers"))
        api(project(":core:map-engine"))
        api(libs.kotlinx.coroutines.core)
        implementation(project(":core:di"))
        implementation(project(":core:storage"))
        implementation(project(":core:mbtiles"))
        implementation(project(":core:database"))
    }
    sourceSets.commonTest.dependencies {
        implementation(libs.kotlinx.coroutines.test)
    }
    sourceSets.androidMain.dependencies {
        api(libs.androidx.work.runtime)
        implementation(libs.androidx.core.ktx)
        implementation(libs.kotlinx.coroutines.guava)
    }
    sourceSets.named("androidDeviceTest").dependencies {
        implementation(libs.androidx.testExt.junit)
    }
}
