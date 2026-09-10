plugins {
    id("app.feature")
}

kotlin {
    sourceSets.commonMain.dependencies {
        implementation(project(":core:map-engine"))
        implementation(project(":domain:providers"))
        implementation(project(":core:datastore"))
    }
}
