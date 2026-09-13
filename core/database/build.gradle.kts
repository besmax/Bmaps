plugins {
    id("app.persistence")
}

kotlin {
    sourceSets.commonMain.dependencies {
        implementation(project(":core:di"))
    }
}
