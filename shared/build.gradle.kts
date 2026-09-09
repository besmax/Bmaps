plugins {
    id("app.shared")
}

kotlin {
    sourceSets.commonMain.dependencies {
        implementation(project(":core:di"))
        implementation(project(":domain:providers"))
        implementation(project(":core:network"))
        implementation(project(":core:datastore"))
        implementation(project(":feature:shell"))
        implementation(project(":feature:constructor"))
        implementation(project(":feature:library"))
        implementation(project(":feature:viewer"))
        implementation(libs.metro.viewmodel.compose)
        implementation(libs.navigation.compose)
    }
    sourceSets.commonTest.dependencies {
        implementation(libs.kotlinx.coroutines.test)
    }
}
