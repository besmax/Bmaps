plugins {
    id("app.feature")
}

kotlin {
    sourceSets.commonMain.dependencies {
        implementation(project(":core:ui"))
        implementation(project(":domain:map-builder"))
        implementation(project(":core:map-engine"))
        implementation(project(":core:datastore"))
    }
}
