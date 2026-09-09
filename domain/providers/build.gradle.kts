plugins {
    id("app.android.library")
    id("app.serialization")
    id("app.di")
}

kotlin {
    sourceSets.commonMain.dependencies {
        api(project(":core:map-engine"))
        implementation(project(":core:network"))
        implementation(project(":core:datastore"))
        implementation(project(":core:di"))
        implementation(libs.kotlinx.coroutines.core)
    }
    sourceSets.commonTest.dependencies {
        implementation(libs.ktor.client.mock)
        implementation(libs.kotlinx.coroutines.test)
    }
}
