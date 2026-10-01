# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
# Required Notice: Copyright (c) 2026 Maksim Bespalov.
# Required Notice: Bmaps — https://github.com/besmax/Bmaps
# License: https://polyformproject.org/licenses/noncommercial/1.0.0
# Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.

cmake_minimum_required(VERSION 3.22)
project(bmaps_dem LANGUAGES C)

if(NOT EXISTS "${BMAPS_TIFF_SOURCE}/CMakeLists.txt")
    message(FATAL_ERROR "BMAPS_TIFF_SOURCE must point to the catalog-pinned libtiff source")
endif()
set(CMAKE_C_STANDARD 11)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_ARCHIVE_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/lib")
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
foreach(option tiff-tools tiff-tests tiff-contrib tiff-docs tiff-install tiff-cxx tiff-framework
        jpeg old-jpeg jbig lerc lzma zstd webp libdeflate pixarlog logluv ccitt thunder next mdi)
    set(${option} OFF CACHE BOOL "" FORCE)
endforeach()
set(zlib ON CACHE BOOL "" FORCE)
set(lzw ON CACHE BOOL "" FORCE)
set(packbits ON CACHE BOOL "" FORCE)
find_package(ZLIB REQUIRED)
add_subdirectory("${BMAPS_TIFF_SOURCE}" libtiff EXCLUDE_FROM_ALL)
set_target_properties(tiff PROPERTIES FRAMEWORK FALSE ARCHIVE_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/lib")

if(ANDROID)
    add_library(bmaps_dem SHARED bmaps_dem.c bmaps_dem_jni.c)
    target_link_libraries(bmaps_dem PRIVATE log)
    target_link_options(bmaps_dem PRIVATE "-Wl,-z,max-page-size=16384")
else()
    add_library(bmaps_dem STATIC bmaps_dem.c)
endif()
target_include_directories(bmaps_dem PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}")
target_link_libraries(bmaps_dem PRIVATE TIFF::tiff m)
target_compile_options(bmaps_dem PRIVATE -Wall -Wextra -Werror)

option(BMAPS_DEM_TESTS "Build host reader checks" OFF)
if(BMAPS_DEM_TESTS)
    enable_testing()
    add_executable(dem_test ../../test/cpp/dem_test.c)
    target_link_libraries(dem_test PRIVATE bmaps_dem)
    add_test(NAME dem_reader COMMAND dem_test "${CMAKE_CURRENT_SOURCE_DIR}/../../test/fixtures")
endif()
