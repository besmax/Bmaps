# Elevation reader feasibility increment

Implemented 2026-09-27 as the elevation part of Phase 9's first feasibility step. Device/emulator execution and actual OpenTopography dataset acceptance remain with the user. SK-91, coordinate preferences, package-session integration and viewer altitude/overlays remain later work. The combined Phase 9 feasibility checkbox is not complete.

## Ownership and native dependency

`core:storage` exposes `DemReaderFactory.open(path)`, immutable `DemMetadata`, and a closeable `DemReader` with `sampleCell(column, row)` and `sample(latitude, longitude)`. Metro supplies the factory. File access runs on `Dispatchers.IO`; a per-reader mutex serializes reads and close. Close is idempotent and completes even when the calling coroutine is cancelled. Cancellation during open closes the allocated native handle. Future package integration must obtain paths through `PackageFiles.asset` and tie reader lifetime to the package session before supporting deletion while a DEM is open.

`core:tiff-native` contains the C wrapper and Android JNI library. The Android KMP plugin does not support `externalNativeBuild`, so this is a standalone Android library consumed only by storage's Android source set. The storage iOS targets compile the same C wrapper and embed static archives through cinterop. Neither native module generates a framework; only `:shared` does that.

The central catalog pins libtiff 4.7.2 and its OSGeo tarball SHA-256. `app.tiff.source` resolves and verifies the archive before extraction. `app.tiff.android` owns NDK/CMake settings and 16 KiB ELF page alignment. `app.tiff.ios` owns native compilation and cinterop. It uses the catalog-selected Android SDK CMake installation by default; `-Pbmaps.cmake=/absolute/path/to/cmake` overrides the executable for iOS builds. Platform zlib provides Deflate; no host Homebrew codec library is linked into mobile binaries. The libtiff license is bundled with common UI resources, alongside the existing font license.

## Initial supported file contract

- Classic TIFF and BigTIFF, either byte order; first image directory only. Overviews and auxiliary masks are not used.
- One contiguous grayscale band, top-left orientation, depth one, no extra samples.
- Signed/unsigned 8-, 16-, or 32-bit integers; IEEE Float32/Float64. Values remain numerical samples, never converted to colors.
- Uncompressed, LZW, Deflate/Adobe Deflate and PackBits. libtiff handles applicable integer/floating-point predictors. Other codecs are disabled and rejected.
- Explicit geographic WGS 84 (`GTModelTypeGeoKey=2`, `GeographicTypeGeoKey=4326`) and explicit PixelIsArea or PixelIsPoint. Angular units, when supplied, must be degrees. Citation keys are ignored. Redundant semi-major-axis/inverse-flattening keys are accepted only when equal to WGS 84. Other CRS-defining keys, projected/custom/vertical CRS definitions, rotated matrices, multiple tie points and missing georeferencing are rejected.
- Positive x/y pixel scale and one finite tie point, with no z transform; north-up coverage entirely within longitude ±180 and latitude ±90. PixelIsPoint coverage extends half a cell around sample centers. Date-line wrapping is not inferred.
- Decoded blocks and encoded blocks read by the wrapper must each fit within 8 MiB. A valid TIFF exceeding that subset is rejected; the file itself has no aggregate size cap.

Geographic lookup selects the nearest sample. PixelIsArea uses the containing cell; PixelIsPoint uses a half-cell footprint around each point. West/north footprint edges are included and east/south edges excluded. Exact midpoint ties select the east/south sample. There is no bilinear interpolation in this increment.

`DemSample.Value.rawValue` is explicitly a **raw numerical sample**, with unknown physical units and vertical reference. GDAL XML scale/offset/unit metadata is not applied or inferred. Do not label these values as meters or render them as authoritative altitudes until the next increment defines and validates that metadata against actual provider files. NoData ASCII is parsed in the C numeric locale; Float32 sentinels are rounded to the stored sample type before comparison. NaN/infinity samples return `NoData`. Zero remains a valid value. Out-of-coverage results, NoData and read failures are distinct.

`DemReadException` distinguishes unsupported layouts/CRS, invalid or unreadable content, wrapper memory limits, and use after close. Some libtiff allocation-limit failures surface as invalid/unreadable because libtiff exposes an open/decode failure rather than a typed allocation failure. Missing data blocks and short/failed decoding never become zero elevation.

## Memory and IO

Memory mapping and automatic strip chopping are disabled. One decoded strip/tile is cached, at most 8 MiB. libtiff is configured for at most 8 MiB per internal allocation and 32 MiB cumulative internal allocations per handle. Codec/allocator/runtime overhead is additional; these are buffer limits, not an exact process RSS guarantee. Each open reader has its own budget, so callers must close sessions rather than accumulate them. Whole files and full rasters are never copied into Kotlin arrays. libtiff owns native random access; application fixture/package byte IO continues to use kotlinx-io.

## Verification and user handoff

Host C checks use an independently sourced GDAL DEM plus small independently generated fixtures. Provenance, hashes and regeneration instructions are in `core/tiff-native/src/test/fixtures/README.md`. The C suite covers strip/tile edges, both byte orders, four codec choices, BigTIFF, numerical types, NoData, corruption, unsupported CRS/layouts and oversized-block rejection. It passes with AddressSanitizer and UndefinedBehaviorSanitizer. Three common Kotlin tests cover geographic edge conventions and reader ownership/lifecycle.

On the development Mac, random samples from a sparse 512 MiB uncompressed BigTIFF passed with 2,129,920 bytes maximum resident set size in the standalone host harness (`/usr/bin/time -l`). This is a single host fixture measurement, not a mobile performance claim or a complete format/memory audit.

Android JNI compilation for all four default ABIs, common host tests, Android device-test compilation, iOS arm64/device and arm64/simulator Kotlin compilation, and iOS simulator test-executable linking passed. Native execution on mobile platforms remains unverified. The Android full `assembleDebug` attempt was blocked by unavailable Google Maven lint 32.1.0 artifacts, potentially due to the active VPN. Skipping annotation extraction also prevents AAR packaging because its typedef output is required. Full AAR/app packaging therefore remains unverified; no fake outputs or lint-policy changes were introduced, and dependency retries were stopped at the user's request.

To reproduce the host C suite with CMake on `PATH` (or substitute the SDK CMake executable), after resolving the pinned source once:

```sh
./gradlew :core:tiff-native:prepareLibtiff
cmake -S core/tiff-native/src/main/cpp -B /tmp/bmaps-dem-host \
  -DBMAPS_TIFF_SOURCE="$PWD/core/tiff-native/build/libtiff/tiff-4.7.2" \
  -DBMAPS_DEM_TESTS=ON -DCMAKE_BUILD_TYPE=Debug
cmake --build /tmp/bmaps-dem-host --parallel 4
ctest --test-dir /tmp/bmaps-dem-host --output-on-failure
```

The fixture binaries are checked in; Python is needed only for regeneration or creating the large sparse fixture. Add `-DCMAKE_C_FLAGS="-fsanitize=address,undefined -fno-omit-frame-pointer"` in a separate build directory for the sanitizer run.

Commands for the user, after selecting a connected Android device or booted iOS simulator:

```sh
./gradlew :core:storage:connectedAndroidDeviceTest
./gradlew :core:storage:iosSimulatorArm64Test
```

The native smoke tests exercise the actual JNI/cinterop path with embedded Int16 big-endian LZW and Float32 tiled BigTIFF fixtures. They are authored for both platforms; they have not been run by the agent.

Before expanding Phase 9:

1. Run the native smoke tests on both platforms, including an Android 16 KiB-page environment if available.
2. Inspect actual downloads from SRTM15+, NASADEM, COP30, COP90 and EU_DTM with an independent tool. Record sample type, compression, predictor, block dimensions, CRS/keys, NoData, scale/offset and vertical units/reference. No API credentials or URLs belong in fixtures or logs.
3. Compare selected reader values against independent reference values for every offered dataset. Unsupported files must fail explicitly; extend the documented subset based on evidence.
4. Profile realistic large files on mobile, repeatedly open/sample/close, and check cancellation/concurrent read/close. The native call is synchronous, so cancellation waits for the current bounded block decode.
5. Define physical elevation semantics, then add package-owned sessions and viewer altitude presentation. Keep the separately pending SK-91 reference-definition spike independent.

## References

- [libtiff open options and allocation limits](https://libtiff.gitlab.io/libtiff/functions/TIFFOpenOptions.html)
- [libtiff opening modes](https://libtiff.gitlab.io/libtiff/functions/TIFFOpen.html)
- [GeoTIFF 1.1 specification](https://docs.ogc.org/is/19-008r4/19-008r4.html)
- [Android KMP native-build limitations](https://developer.android.com/kotlin/multiplatform/plugin#unsupported-features)
