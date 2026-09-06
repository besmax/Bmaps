plugins {
    id("app.android.library")
}

kotlin {
    sourceSets.commonMain.dependencies {
        implementation(libs.androidx.datastore.preferences)
    }
}
