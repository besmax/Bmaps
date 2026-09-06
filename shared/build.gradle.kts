plugins {
    id("app.kmp.library")
    id("app.android.library")
    id("app.compose.multiplatform")
    id("app.di")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.mapcompose.mp)
            implementation(libs.kotlinx.io.core)
            implementation(libs.ktor.client.core)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}
