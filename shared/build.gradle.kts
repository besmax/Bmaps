plugins {
    id("app.shared")
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
