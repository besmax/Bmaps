plugins {
    id("app.android.library")
}

kotlin {
    sourceSets.commonMain.dependencies {
        implementation(libs.androidx.room.runtime)
        implementation(libs.androidx.sqlite.bundled)
        // Data-layer pagination will be custom-built here; do not use Paging 3.
    }
}
