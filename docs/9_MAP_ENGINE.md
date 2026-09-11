# Coordinates and Raster Renderer

Phase 3B implements Web Mercator math, a renderer-independent raster API, the MapComposeMP adapter, and a deterministic offline sample in the constructor. Phase 3C now supplies the online constructor, credential entry, and provider selection as described in document 8; the deterministic sample remains a debug/test fixture.

## Coordinates and coverage

`WebMercator` implements the existing `CoordinateTransformer` for EPSG:4326 and EPSG:3857 inside the square tile world's coverage. Longitude is -180 through +180 degrees; latitude is ±85.0511287798066 degrees. Unsupported CRS, non-finite coordinates, and positions outside coverage fail explicitly. No SK-91 transform is guessed.

`normalized`, `geographic`, `tileAt`, and `tileBounds` convert between geographic coordinates, the unit square, and canonical top-origin XYZ tiles. At +180 longitude and the southern edge, point lookup chooses the last tile; -180 chooses the first. Internal edges use floor ownership. These endpoint rules do not silently clamp unsupported coordinates.

`splitBounds` splits an antimeridian-crossing selection into west-to-180 and -180-to-east rectangles without sorting away the crossing. Zero-area or out-of-coverage bounds fail. Rendering does not automatically repeat the world or join crossing regions; callers explicitly handle the split.

Floating-point geographic/tile conversions support levels 0–52; higher levels return null because Double cannot reliably distinguish every tile boundary. Integer tile addressing supports levels 0–63. These are numeric representation limits, not an application zoom-18 cap. [OSM's coordinate formulas and Hachiko example](https://wiki.openstreetmap.org/wiki/Slippy_map_tilenames) supply independent fixtures.

## Raster API and engine limits

Public configuration and events contain no MapComposeMP types:

- `TilePyramid`: inclusive source levels, Long origin column/row, rectangle dimensions in tiles at the lowest source level, and square tile size.
- `MapViewport`: normalized center within that rectangle and scale relative to its fit-to-container scale.
- `RasterMapConfig`: initial viewport, display scale limits, and pan/zoom enablement. Rotation is disabled for this adapter.
- `RasterLayer`: stable ID, opacity, and a suspending factory opening a fresh, independently owned source. Layers share the configured tile matrix and coverage; sources must supply matching PNG/JPEG dimensions. The base layer must be opaque because the engine ignores its opacity.
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
