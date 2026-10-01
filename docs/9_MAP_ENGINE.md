# Coordinates and Raster Renderer

Phase 3B implements Web Mercator math, a renderer-independent raster API, the MapComposeMP adapter, and a deterministic offline sample in the constructor. Phase 3C now supplies the online constructor, credential entry, and provider selection as described in document 8; the deterministic sample remains a debug/test fixture.

## Coordinates and coverage

`WebMercator` implements the existing `CoordinateTransformer` for EPSG:4326 and EPSG:3857 inside the square tile world's coverage. Longitude is -180 through +180 degrees; latitude is ±85.0511287798066 degrees. Unsupported CRS, non-finite coordinates, and positions outside coverage fail explicitly. The Phase 9 display transformer additionally uses bundled PROJ data for geographic 2D EPSG:4326, EPSG:4284 and EPSG:9475; SK-91 was removed from scope on 2026-09-29. Native builds and runtime acceptance remain pending.

`normalized`, `geographic`, `tileAt`, and `tileBounds` convert between geographic coordinates, the unit square, and canonical top-origin XYZ tiles. At +180 longitude and the southern edge, point lookup chooses the last tile; -180 chooses the first. Internal edges use floor ownership. These endpoint rules do not silently clamp unsupported coordinates.

`splitBounds` splits an antimeridian-crossing selection into west-to-180 and -180-to-east rectangles without sorting away the crossing. Zero-area or out-of-coverage bounds fail. Rendering does not automatically repeat the world or join crossing regions; callers explicitly handle the split.

Floating-point geographic/tile conversions support levels 0–52; higher levels return null because Double cannot reliably distinguish every tile boundary. Integer tile addressing supports levels 0–63. These are numeric representation limits, not an application zoom-18 cap. [OSM's coordinate formulas and Hachiko example](https://wiki.openstreetmap.org/wiki/Slippy_map_tilenames) supply independent fixtures.

## Raster API and engine limits

Public configuration and events contain no MapComposeMP types:

- `TilePyramid`: inclusive source levels, Long origin column/row, rectangle dimensions in tiles at the lowest source level, and square tile size.
- `MapViewport`: normalized center within that rectangle and scale relative to its fit-to-container scale.
- `RasterMapConfig`: initial viewport, display scale limits, and pan/zoom enablement. Rotation is disabled for this adapter.
- `RasterLayer`: stable ID, opacity, and a suspending factory opening a fresh, independently owned source. Layers share the configured tile matrix and coverage; sources must supply matching PNG/JPEG dimensions. Visibility and opacity apply independently to every user layer, including the root. Hidden and zero-opacity layers do not open sources. The adapter supplies a transparent PNG backing layer because MapComposeMP requires a decodable bottom tile and ignores that layer’s opacity; this backing image is not a package asset.
- `MapEvent`: viewport/tap observations, typed tile failures, and initialization/configuration failures. `positionOf` and `coordinateAt` convert regional interaction positions to/from geographic coordinates within the floating-point precision range.

MapComposeMP 1.1.3 takes Int pixel dimensions, numbers its local pyramid from zero, and uses scale 1.0 at its highest level. The adapter maps local indices to absolute source levels and Long tile origins. `engineSize()` rejects dimension overflow; source levels are never silently removed. Scale values that would overflow scaled viewport pixel coordinates are also rejected. A null maximum display scale selects the engine's normal overzoom allowance, bounded by its integer coordinate range.

For example, a full world with 256-pixel tiles supports source levels 0–22 in one engine instance. Higher source levels are supported through smaller tile-aligned regional windows, including integer-addressed windows reaching level 63. A whole-world 0–23 pyramid cannot fit this engine instance and requires window selection/rebasing by a future caller. Automatic rebasing is not implemented. At minimum source level 1, a full world needs `columns = 2` and `rows = 2`; the default one-by-one window covers one tile at the minimum level.

`OnlineMapViewModel` and the fixture ViewModel own a `RasterMapRenderer`. The renderer owns MapState, source sessions, retained viewport, zoom controls, and a bounded Channel exposed as a generation-tagged event Flow. MapComposeMP types remain internal to the core adapter. `RasterMap` only observes renderer state, reports layout size, and renders MapUI. The owning ViewModel runs the renderer in `viewModelScope`; backgrounding or removing the composable does not shut down MapState or discard its decoded tiles. Clearing the navigation entry's ViewModel cancels the runner and releases the engine and sources. This is in-memory retention, not restoration after process death.

Content or nonzero layout-size changes cancel and join the previous session before opening its replacement. Transient zero-size layout reports are ignored. A runner mutex prevents multiple concurrent owners. The renderer retains its viewport across layout changes for the same content; changing content supplies a new initial viewport. Returning from the background with the same content and dimensions reuses the existing engine and decoded tiles. The feature ViewModel consumes renderer events and rejects retired generations. Initialization and cleanup failures use `MapUnavailableReason`; feature presentation maps failures to string resources.

The configured worker count is 8, matched by the default provider concurrency and native per-host request/connection limits. Provider-specific concurrency and request pacing still apply independently; the default request-start interval is zero. The generic transport retains its global limit of 16.

## Ownership and failure handling

`RasterTileSession` owns opened sources and active read jobs. Partial initialization closes already-opened sources. Disposal shuts down MapState, cancels and joins reads, and closes all sources once, including when cleanup of another source fails. Suspending cancellation remains cancellation.

MapComposeMP 1.1.3's `shutdown()` cancels jobs and immediately closes its tile dispatcher, which caused an iOS `Dispatcher TileCanvasThread was closed` crash during initial layout changes. The adapter cancels and joins the engine scope before calling `shutdown()`, under `NonCancellable`, and closes tile sources in `finally`. This narrowly scoped compatibility workaround accesses the pinned engine's internal scope with visibility suppressions; review it when changing MapComposeMP versions.

Each returned RawSource contains complete in-memory tile bytes. It owns no socket/file handle; MapComposeMP closes it after decoding. PNG/JPEG signature, encoded size (2,000,000 bytes), and exact tile dimensions are checked before decode. Android reads dimensions with BitmapFactory bounds-only decoding; iOS reads dimensions through Skia Codec. This avoids allocating and decoding a validation bitmap before MapComposeMP decodes the image for display. Missing tiles return null; failed requests and rejected signatures, sizes, or dimensions emit `TileFailed` first. Header validation does not prove pixel data is intact. Decode failures after valid headers are handled by MapComposeMP as an absent bitmap and do not currently produce a separate feature error event. The internal event channel is bounded and nonblocking; it is not durable event delivery.

The sample alternates generated PNG/JPEG tiles and makes no HTTP requests. Its ViewModel reports persistent sample errors as state. Global DI contributions are public so the umbrella can discover them. iOS debug builds accept `--preview-map` to open the constructor for repeatable simulator smoke checks; release builds ignore it.

## Verification and continuation record

Pinned source: cached MapComposeMP 1.1.3 artifact, inspected once and extracted to `/tmp/bmaps-mapcompose-1.1.3`. Key APIs: `MapState`, `addLayer`, `Forced`, `onTap`, `setStateChangeListener`, `shutdown`. Engine bounds and ownership above were verified from that source.

```sh
./gradlew :core:map-engine:testAndroidHostTest :core:map-engine:iosSimulatorArm64Test
./gradlew :domain:providers:testAndroidHostTest :androidApp:testDebugUnitTest
./gradlew :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest
./gradlew :shared:compileKotlinIosArm64 :shared:linkDebugFrameworkIosSimulatorArm64
```

Math/session fixtures cover published coordinates, round trips, world/projection edges, dateline splitting, high-zoom local/global addressing, regional interaction conversion, stream independence, corrupt/missing results, cancellation, partial initialization, and repeated cleanup.

`RasterMapDeviceTest` additionally checks rendered pixel colors after navigation, recreation, and reopening. Current environment limits: AGP's UTP 32.1.0 runner dependency is unavailable, and direct instrumentation on the installed API 36.1 image fails in Espresso's `InputManager.getInstance` reflection before the test runs. Robolectric native-graphics pixel capture timed out, so it is not registered as a host test. Keep the device pixel scenario for a supported test runtime. Do not repeat those investigations during Phase 3C.

Native smoke evidence and final test results are recorded in the implementation plan. The iOS debug preview uses the constructor as the navigation graph's start destination; navigating from an initial `LaunchedEffect` raced graph installation and crashed. The fixed iPhone 17 Pro simulator launch displays the fixture. The previous temporary Android AVD is no longer available; the existing AVD was tried in read-only mode, but installation failed with insufficient storage. Android visual acceptance remains pending. Simulator/native graphics verification is not physical-device performance verification.

## Phase 8 vector overlays

`RasterMap` accepts renderer-independent `MapMarker` and `MapPath` values plus composable marker content and a selection callback carrying the local map position. It attaches overlays to the active engine using a composition effect and removes them when their IDs disappear or the engine changes. Existing markers retain their native registration and composable identity: position, stacking order, and anchor updates use `moveMarker`, `updateMarkerZ`, and `updateMarkerOffset`; content reads the latest value by ID. This preserves local animations when object properties or selection change. Changed paths are replaced. Paths support strokes and translucent polygon fills; markers use the viewer's tintable SVG catalog. Geographic annotation models, edit history, visibility, and validation stay in the viewer/domain layers. MapComposeMP remains encapsulated. The native marker/path API was inspected in the cached 1.1.3 sources; rendering acceptance remains pending.

The renderer also exposes a latest `MapCameraSnapshot` flow containing the active pyramid, fit-relative viewport, measured layout, fit scale, effective maximum scale, and visible window. Consumers use this for screen-distance calculations without depending on MapComposeMP types. Camera snapshots are cleared when an engine session is retired.

`RasterMapCallout` attaches composable content with MapComposeMP 1.1.3's `addCallout`, updates its coordinate with `moveCallout`, and calls `removeCallout` when the attachment leaves composition. MapComposeMP types remain private to the adapter. The feature controls local screen offsets and visibility animations. Native `autoDismiss` is disabled so removal can wait for the exit animation; the viewer's `autoDismiss` flag controls logical dismissal on map gestures. Card touch bounds are retained only while attached and excluded from the map's gesture-start/long-press observer, so callout controls and content scrolling stay interactive. Multiple attachments with distinct IDs are supported by the core API.

`RasterMap` observes gesture starts on the initial pointer down outside callout cards without consuming map input. Its long-press observer waits for further pointer events only while pointers remain pressed; an already observed release completes that gesture instead of absorbing the next one. This lets the viewer dismiss a selected-object callout as soon as a following pan starts. Device acceptance remains with the user.

## Phase 9 PROJ integration — 2026-09-29

The selectable geographic systems are WGS 84 (EPSG:4326), SK-42 / Pulkovo 1942 (EPSG:4284) and PZ-90.11 (EPSG:9475). Their geographic 2D definitions are present in [PROJ's EPSG-derived database](https://github.com/OSGeo/PROJ/blob/master/data/sql/geodetic_crs.sql). SK-91 is superseded. EPSG:4284 is not a zoned Gauss–Krüger projected CRS. Web Mercator remains the renderer's projection and existing compatibility path, while these three systems define the new display selector.

The source implementation uses PROJ's C API behind a session-based adapter, via Android JNI and iOS cinterop. Native handles stay private, calls are serialized per session, operations are reused, and sessions release contexts and operations on close. The native module uses convention plugins and catalog-pinned source archives; only `:shared` generates an iOS framework. PROJ's SQLite symbols are isolated from other SQLite users with a separately compiled, renamed SQLite amalgamation. After a user-reported Android Ninja missing-archive failure, the imported SQLite archive path was anchored to the root CMake build directory and `bmaps_proj` was given an explicit dependency on `bmaps_proj_sqlite`. Host CMake configuration and the native static target build passed, including SQLite archive generation. Android JNI shared-library linking, iOS linking, app builds, and runtime acceptance remain pending; no tests were run for this fix.

The matching `proj.db` is embedded in the native library resources. Runtime networking is disabled. Candidate operations that require grids are excluded, so the currently supported offline subset needs no separately bundled grids. PROJ documents its [resource requirements](https://proj.org/en/stable/resource_files.html) and [cross-compilation setup](https://proj.org/en/stable/install.html). Mobile native compilation, package-size measurement, and device viability remain pending.

Use full EPSG CRS identities rather than ellipsoid-only PROJ strings. The adapter normalizes axis order with `proj_normalize_for_visualization` so application x/y stays longitude/latitude in degrees. It selects the most accurate candidate whose area contains the point, prohibits ballpark operations, requires a documented nonnegative accuracy, and excludes operations requiring unavailable grids. It returns explicit missing-operation/out-of-area errors rather than substituting identity. Record and verify operation identity, area, and accuracy against independent fixtures; those runtime checks remain pending. These controls are specified in the [PROJ C API](https://proj.org/en/stable/development/reference/functions.html).

The public display scope is 2D. For a time-dependent operation requiring coordinate time, the adapter supplies the PZ-90.11 reference epoch 2010.0 and exposes it in the widget. DEM heights remain orthometric values in the dataset's own vertical reference, not ellipsoidal inputs to a horizontal conversion. Do not infer survey-grade accuracy from angular display precision. Operation-specific tolerances still need independent reference-point acceptance, not only round trips through the same library.

The source implementation also adds a Preferences selector for the three geographic systems and transforms the WGS 84 map center before formatting the viewer readout. Format selection applies to all three. A legacy EPSG:3857 preference remains supported. This source change is not runtime-verified; builds, tests and native/device acceptance remain with the user.
