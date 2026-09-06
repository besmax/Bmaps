plugins {
    id("app.android.library")
    id("app.compose.multiplatform")
}

kotlin {
    sourceSets.commonMain.dependencies {
        implementation(libs.mapcompose.mp)
    }
}
