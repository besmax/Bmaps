plugins {
    id("app.android.library")
    id("app.serialization")
}

kotlin {
    sourceSets.commonMain.dependencies {
        api(project(":core:map-engine"))
    }
}
