# Elevation reader feasibility increment

Implemented 2026-09-27 as the elevation part of Phase 9's first feasibility step. Device/emulator execution and actual OpenTopography dataset acceptance remain with the user. The 2026-09-29 increment adds coordinate formatting, package-owned DEM sampling and viewer center altitude; SK-91 and device/provider-file acceptance remain pending. The combined Phase 9 feasibility checkbox is not complete.

## Ownership and native dependency

`core:storage` exposes `DemReaderFactory.open(path)`, immutable `DemMetadata`, and a closeable `DemReader` with `sampleCell(column, row)` and `sample(latitude, longitude)`. Metro supplies the factory. File access runs on `Dispatchers.IO`; a per-reader mutex serializes reads and close. Close is idempotent and completes even when the calling coroutine is cancelled. Cancellation during open closes the allocated native handle. `LocalOpenedPackage` obtains its DEM path through `PackageFiles.asset`, lazily opens one reader, and serializes sampling and close under its session mutex. Package deletion closes these sessions before removing files.

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

`DemSample.Value.rawValue` is explicitly a **raw numerical sample**, with unknown physical units and vertical reference. GDAL XML metadata (tag 42112) is exposed as `hasGdalMetadata`. The storage API still returns raw values without applying it; the package altitude adapter conservatively rejects that metadata, including scale/offset/unit overrides. Only the package adapter interprets supported, known OpenTopography datasets as unscaled meters according to the provider contract below. Unknown dataset identity remains unsupported. NoData ASCII is parsed in the C numeric locale; Float32 sentinels are rounded to the stored sample type before comparison. NaN/infinity samples return `NoData`. Zero remains a valid value. Out-of-coverage results, NoData and read failures are distinct.

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
5. Verify the provider-based physical elevation semantics and package-owned viewer sampling below with actual downloaded files. Keep the separately pending SK-91 reference-definition spike independent.

## References

- [libtiff open options and allocation limits](https://libtiff.gitlab.io/libtiff/functions/TIFFOpenOptions.html)
- [libtiff opening modes](https://libtiff.gitlab.io/libtiff/functions/TIFFOpen.html)
- [GeoTIFF 1.1 specification](https://docs.ogc.org/is/19-008r4/19-008r4.html)
- [Android KMP native-build limitations](https://developer.android.com/kotlin/multiplatform/plugin#unsupported-features)

## Viewer center increment — 2026-09-29

`OpenedPackage.elevation(latitude, longitude)` returns meters plus a vertical-reference label, or distinct missing, NoData, outside-coverage, unsupported and unavailable states. The adapter accepts the existing reader subset and known `elevationDataset` identities: NASADEM and SRTM15Plus use EGM96, while COP30, COP90 and EU_DTM use EGM2008. These are provider-defined geoid heights; no conversion between vertical references is performed. Generic/legacy assets with dataset `NONE`, explicit unsupported vertical GeoKeys, and GDAL XML metadata remain unsupported. Rejecting all GDAL XML for altitude is conservative: even a statistics-only tag is currently rejected until metadata interpretation is implemented.

The unit policy is unscaled meters for these provider products. Vertical references follow [OpenTopography's dataset table](https://www.opentopography.org/blog/new-point-elevation-api-opentopography). File-by-file verification of the downloaded representations remains pending user testing; this implementation does not establish all-dataset compatibility. No network requests occur during offline sampling.

`MapPositionViewModel` owns its package session, observes the renderer's actual camera center and saved preferences, and clears the previous altitude as soon as the center changes. A cancellable 120 ms settling delay avoids DEM reads for every animation frame; results from retired lookups cannot update the current point. Raster rendering remains independent of DEM failures. One decimal place is display rounding, not a claim of decimeter DEM accuracy. A tile-only package omits the elevation row. The fixed black cross has a white outline and does not intercept gestures.

Coordinate formats are signed latitude then longitude: decimal degrees (six decimal places), degrees/minutes (three minute decimals), and degrees/minutes/seconds (one second decimal). Rounding carries across minute/degree boundaries. Persisted EPSG:3857 uses the existing transformer and labeled X/Y meters; unknown systems are explicitly unavailable. SK-91 remains unimplemented. Preferences retain their existing CRS identifier separately from the new format key.

Source changes only in this increment: no builds, automated tests, device runs or live provider calls were performed, at the user's request. Suggested manual acceptance: all three formats across negative coordinates and rounding boundaries; changing zoom/region; rapid pan and navigation; tile-only packages; known DEM reference points including zero/negative values; NoData and coverage edges; unsupported/corrupt DEM; deletion/session closure; annotation tools and large text without overlapping the bottom readout.

## Elevation diagnostics and delivery to the widget

Diagnostics use the `BmapsElevation` marker. Common Kotlin messages go to standard output, and exception stack traces go to standard error; Android exposes these through System.out/System.err in Logcat, and an attached iOS run exposes them in the Xcode console. Filter by message text, not exclusively by the Android tag or error severity. Native Android diagnostics use the `BmapsElevation` Logcat tag with ERROR/WARN severity; iOS/host native diagnostics use standard error. The native target links the Android system log library.

Messages cover widget start/stop, package open results, DEM open requests, successfully read metadata, unsupported metadata/dataset identity, native open/metadata/sample failures, result-kind transitions, recovery and close failures. Kotlin failures retain exception stack traces before conversion to UI state. Native libtiff callbacks retain their original module and formatted error/warning text. Wrapper validation failures record function, source line, status and available raster dimensions/sample/compression/block metadata. Native statuses 3/4/5 mean unsupported, invalid/unreadable and memory limit respectively. Each reader emits at most 16 native messages per severity plus a suppression notice; identical consecutive package-level failures are suppressed until recovery. Routine camera movement and every successful height are not logged. A `widget_result` value is logged on the first result and when the result kind changes, not for each numeric update. Cancellation is normal control flow and is not logged as an error.

The data path is:

1. During construction, `DownloadRunner.downloadElevation` streams the selected OpenTopography DEM into `elevation.geotiff.part`. Storage finishes the file, checks its TIFF header, renames it to `elevation.geotiff`, and records the asset and dataset in the manifest. Header acceptance alone does not establish that the reader supports the complete file.
2. `ViewerScreen` connects `MapPositionViewModel` to `RasterMapRenderer.camera`. Its normalized viewport center belongs to the active tile pyramid, including the currently selected antimeridian region. `coordinateAt` turns that position into WGS 84 latitude/longitude. The crosshair is fixed at this same screen center.
3. The ViewModel combines these coordinates with saved display preferences. Formatting only changes the displayed coordinates; DEM lookup always uses WGS 84. The previous height is cleared on a new lookup, and `collectLatest` cancels superseded work. The 120 ms settling delay precedes DEM sampling.
4. The widget opens and retains its own `OpenedPackage` session, independent of the raster renderer's session. Repository verification can fail before any DEM reader is opened. An absent elevation asset omits the elevation row.
5. `LocalOpenedPackage.elevation` lazily opens one `DemReader` using the resolved private package asset path. Known dataset identity selects the provider-based vertical reference. Unknown identity and uninterpreted GDAL XML metadata produce Unsupported. Open failures are cached for the session, so moving the map does not repeatedly reopen the same unreadable file; reopening the viewer creates a new attempt.
6. `DemReader` runs file operations on the IO dispatcher under a mutex. GeoTIFF origin, signed pixel steps and PixelIsPoint/PixelIsArea semantics identify the raster cell. Lookup uses floor after the applicable half-cell shift, with no bilinear interpolation. Out-of-coverage is distinct from NoData.
7. The native reader decodes only the required strip/tile (up to 8 MiB), reuses its last decoded block, reads the numerical sample and checks its NoData sentinel/finite value. The domain adapter interprets supported provider samples as unscaled meters and returns `PackageElevation.Value` with its vertical-reference label.
8. The ViewModel writes that result into its immutable StateFlow. `ViewerScreen` observes it with `collectAsStateWithLifecycle`; `MapPositionOverlay` renders the value with one decimal place, or the corresponding availability message. No online query or GPS altitude participates in this path. Closing the widget/session releases the native reader; package deletion also closes registered sessions first.

`Elevation: could not read data` currently combines package-open failure, DEM invalid/unreadable or closed state, and an unexpected lookup/observation exception. It does not identify one root cause. Use the first native ERROR and subsequent `dem_open`, `dem_sample`, `widget_package_open` or `widget_lookup` message to locate the failure. Unknown layouts/CRS/GDAL metadata use the separate unsupported-data message. An unexpected per-lookup exception now leaves camera observation active, permitting a subsequent center update to try again.

Diagnostics were added after a user-reported read failure. The agent did not reproduce the failing file. The subsequent JNI correction resolved the SRTM15Plus error according to user confirmation; builds and test execution remain with the user.

## Android JNI handle/status correction — 2026-09-29

A reported SRTM15Plus failure reached `demFailure` directly from `AndroidDemReader.openNativeDem`, without native diagnostic lines in the supplied excerpt. Source review found that the JNI bridge overloaded a signed `jlong`: success returned the pointer bits, failure returned a negative status, and Kotlin treated every value `<= 0` as failure. On Android ARM64 a valid tagged heap pointer can have its sign bit set. See [Android tagged pointers](https://source.android.com/docs/security/test/tagged-pointers). The old check could misclassify a successfully opened reader, truncate the pointer into an unrelated error code, and leak that reader. This is a confirmed code defect, but the supplied excerpt alone does not prove it caused that particular device failure.

JNI `NativeDem.open(path, handle)` now returns an independent integer status and writes the complete pointer bits to a one-element `long[]`. Kotlin accepts any nonzero handle when status is zero; the tag is preserved on later metadata/sample/close calls. JNI initializes the output to zero and closes an opened reader if publishing the handle raises a Java exception. No pointer-tagging settings are changed.

`jni_dem_open status=0 handlePresent=true` identifies a successful JNI open. Actual native failures retain their numeric `nativeStatus` in `DemReadException`; statuses 3, 4 and 5 mean unsupported, invalid/unreadable and memory limit. If status 4 remains after rebuilding, inspect the preceding native `BmapsElevation` ERROR messages to diagnose the TIFF itself. Rebuild and reinstall the complete Android app, including its native library, because the Java/JNI method signature changed; Kotlin-only Apply Changes is insufficient. Close/reopen the map to discard the session-cached failure. Builds and tests remain with the user.

User confirmation (2026-09-29): the JNI correction resolved the reported SRTM15Plus elevation error on Android. This is manual confirmation of that scenario; independent height-reference checks, other datasets and iOS acceptance remain pending.
