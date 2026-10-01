/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

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
            name = "projSource"
            url = uri("https://download.osgeo.org/proj/")
            patternLayout { artifact("[artifact]-[revision].[ext]") }
            metadataSources { artifact() }
            content { includeModule("org.osgeo", "proj") }
        }
        ivy {
            name = "sqliteSource"
            url = uri("https://www.sqlite.org/2025/")
            patternLayout { artifact("[artifact]-[revision].[ext]") }
            metadataSources { artifact() }
            content { includeModule("org.sqlite", "sqlite-amalgamation") }
        }
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
    ":core:sharing",
    ":core:network",
    ":core:database",
    ":core:mbtiles",
    ":core:datastore",
    ":core:storage",
    ":core:tiff-native",
    ":core:proj-native",
    ":core:map-engine",
    ":core:di",
)
