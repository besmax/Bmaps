rootProject.name = "Bmaps"

pluginManagement {
    includeBuild("build-logic")

    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        ivy {
            name = "libtiffSource"
            url = uri("https://download.osgeo.org/libtiff/")
            patternLayout { artifact("[artifact]-[revision].[ext]") }
            metadataSources { artifact() }
            content { includeModule("org.libtiff", "tiff") }
        }
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

include(":androidApp")
include(":shared")
include(":domain:providers", ":domain:map-builder")
include(":feature:shell", ":feature:constructor", ":feature:library", ":feature:viewer")

include(
    ":core:ui",
    ":core:network",
    ":core:database",
    ":core:mbtiles",
    ":core:datastore",
    ":core:storage",
    ":core:tiff-native",
    ":core:map-engine",
    ":core:di",
)
