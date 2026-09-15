plugins {
    id("app.feature")
}

kotlin {
    sourceSets.commonMain.dependencies {
        implementation(project(":core:ui"))
        implementation(project(":core:datastore"))
    }
}
