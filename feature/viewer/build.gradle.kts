plugins {
    id("app.feature")
}

kotlin {
    sourceSets.commonMain.dependencies {
        implementation(project(":domain:map-builder"))
        implementation(project(":core:map-engine"))
    }
}
