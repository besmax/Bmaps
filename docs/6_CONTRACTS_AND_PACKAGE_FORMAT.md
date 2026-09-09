# Phase 1 Contracts and Package Format

Status: Phase 1 contract baseline. Phase 2 preferences/shell and Phase 3A online networking/encrypted credentials are implemented in documents 7–8. Package persistence, renderer integration, validation UI, and download orchestration remain later work.

## Ownership

`core:map-engine` owns renderer-independent geometry, tile descriptors, tile sources, and coordinate transformation contracts. `domain:providers` owns provider/style definitions. `domain:map-builder` owns package manifests and build/library lifecycle contracts. The module registry records the one-way dependency from map-builder to providers. Domain repository implementations will adapt core APIs; core does not implement interfaces that require importing domain. `:shared` will assemble Metro bindings.

No screen logic, use-case implementation, or DI graph is introduced in Phase 1. The framework convention fix is the minimal Phase 0 prerequisite: `app.kmp.library` declares targets; only `:shared` applies `app.shared`, which generates the static framework. The remainder of Phase 0 is still outstanding.

## Coordinates and levels

- `GeographicCoordinate` and `BoundingBox` use WGS-84 degrees. Bounds are west/south/east/north. West greater than east denotes antimeridian crossing; it must not be sorted away. Full-world longitude coverage is -180 to 180.
- `ProjectedCoordinate` carries a CRS identifier and x/y in that CRS's units and axis convention. For EPSG:4326 in this application, x is longitude and y is latitude. EPSG:3857 uses easting/northing in meters.
- CRS identifiers are extensible strings. There is deliberately no guessed SK-91 definition or transform; Phase 9 must supply a precise identifier, reference parameters, and accuracy fixtures.
- `TileKey` always uses canonical top-origin XYZ coordinates. Columns and rows use `Long`; levels use `Int`. Provider adapters convert to their endpoint convention and MBTiles adapters convert to bottom-origin storage. URL path ordering (ArcGIS z/row/column) is independent of row origin.
- No model applies an arbitrary maximum level of 18. Level 0 is valid. `LevelLimitsConfig` describes source availability, not renderer magnification. A null maximum means unresolved/not declared, not proof that the server has unlimited levels. The source integration must resolve finite supported levels before enumerating a download.
- `ScaleLimitsConfig` constrains display magnification independently. Null means no provider-imposed display constraint. Initial scale is relative to the renderer's fit-to-bounds scale; scroll coordinates are normalized map coordinates, with (0, 0) at top-left and (0.5, 0.5) centered. The renderer adapter translates these conventions rather than exposing engine scroll units.
- Empty `BoundariesConfig.boundingBoxList` means no additional provider-specific coverage restriction; projection coverage still applies. Multiple boxes denote the union of available areas.
- The later planner must detect arithmetic overflow and avoid materializing the entire tile matrix. Supporting all available levels does not mean requesting nonexistent tiles or unlimited precision.

## Providers and styles

`ProviderId` and `StyleId` wrap strings rather than closed enums. Add a definition with another ID/style list to register a new provider; core algorithms should not switch on built-in provider IDs. `BuiltInProviders` is seed data, not a service singleton or a production repository implementation.

Each provider has full configuration, attribution, and capabilities. A style may replace each of those independently. `configOverride` is a complete replacement, not a partially merged object; use `provider.config.copy(...)` when creating a partial customization. A validated `BuildLayerRequest` snapshots the effective configuration and selected non-secret endpoint parameters so it can be resumed deterministically.

| Provider / style | Endpoint | Source levels | Initial formats |
| --- | --- | --- | --- |
| `osm` / `world-street-map` | `https://tile.openstreetmap.org/{z}/{x}/{y}.png` | 0–19 | PNG |
| `osm` / `osmand-hd` | `https://tile.osmand.net/hd/{z}/{x}/{y}.png` | 1–19 | PNG, 512 × 512 |
| `arcgis` / `world-imagery` | `https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}` | Resolve from service metadata in Phase 3 | JPEG |
| `yandex` / `map` | `https://tiles.api-maps.yandex.ru/v1/tiles/` | 0–20 | PNG |
| `thunderforest` / `atlas` | `https://tile.thunderforest.com/atlas/{z}/{x}/{y}.png` | 0–22 | PNG |

The initial endpoint URLs follow the requested catalog. They are not claims that unauthenticated requests succeed or that every geographic area has imagery at every advertised level. ArcGIS format/coverage and exact contributor attribution must be reconciled with service metadata during integration. Thunderforest's current documentation uses `api.thunderforest.com`; verify the requested `tile.thunderforest.com` host during Phase 3.

`TileEndpoint.address` substitutes only z/x/y and returns an unencoded structured address. It does not perform HTTP, attach keys, validate coordinates, or authorize downloads. The Phase 3A online adapter merges declared endpoint parameters with their defaults, rejects missing mandatory values, resolves `CredentialReference` through platform credential storage, and lets Ktor encode query values exactly once. Keys, signed URLs, and credential values must never enter manifests, progress objects, or logs.

Yandex declares `lang`, `scale`, `projection`, and `maptype`; defaults are `en_US`, `1.0`, `web_mercator`, and `map`. Its coordinate query includes x/y/z and l=map. `projection` is a string in the current API, not a float. Spherical Mercator is explicitly selected to match the default tile matrix. Changing projection or image scale must also change the effective matrix configuration; the UI validation/integration must prevent mismatches. Request signing, where needed by the selected account, belongs to the runtime credential adapter.

Online viewing and offline permission are separate capabilities. The public OSM endpoint is `PROHIBITED` for offline downloads. Other seed definitions are `REQUIRES_VERIFICATION` until actual account/provider terms are established; this does not inherit OSM's prohibition for OsmAnd. Runtime rate limits and required attribution may be more specific than the initial catalog. Missing verified download capability must be surfaced before scheduling work.

Sources checked for catalog design:

- [OSM tile usage policy](https://operations.osmfoundation.org/policies/tiles/): offline restrictions, attribution, and identifying user agent.
- [OSM tile addressing](https://wiki.openstreetmap.org/wiki/Slippy_map_tilenames): standard source levels and XYZ scheme.
- [OsmAnd tile-source definition](https://github.com/osmandapp/Osmand/blob/master/OsmAnd-java/src/main/java/net/osmand/map/TileSourceManager.java): HD endpoint, level defaults, and image dimensions.
- [Yandex request format](https://yandex.ru/maps-api/docs/tiles-api/request.html): levels, query parameters, and projection names.
- [Thunderforest Map Tiles API](https://www.thunderforest.com/docs/map-tiles-api/): style addressing, credentials, and levels.

## Version 1 package manifest

`PackageManifest` is the serializable domain format for `config.json`. A mandatory `schemaVersion` prevents an unversioned file from silently becoming version 1. `compatibility()` checks format version and raster support only; it is not complete import validation. Structural/file-integrity checks remain the responsibility of the future storage boundary.

The manifest contains stable package identity, display name, WGS-84 bounds, inclusive overall zoom range, UTC epoch-millisecond timestamps, ordered layers, optional annotations/elevation, auxiliary assets, and the package size policy. Layers carry independent source identity (nullable for imported sources), bounds, zoom range, tile content/CRS/matrix metadata, visibility, opacity, and attribution. List order is bottom to top. The overall zoom range spans the layer ranges; each layer may have narrower source coverage.

Storage layout:

```text
<package-id>/
  config.json
  map_data.mbtiles
  layers/<layer-id>.mbtiles
  annotations.db
  elevation.geotiff
```

Only required/present assets are created. Additional layers and optional annotation/elevation files are not required for a single-layer package. All layers initially share identical geographic bounds and compatible tile matrices; offsets and reprojection of raster layers are not implemented.

Package IDs and layer IDs are generated stable opaque storage identifiers, restricted by the future boundary validation to safe filename components. Display names are never filesystem paths. Asset references are package-relative paths; reject absolute paths, traversal, symlink escape, duplicated asset paths, and missing files when importing/opening. Do not trust an imported size or manifest-supplied size limit.

Each asset records its byte size and optional SHA-256 digest. A manifest does not list itself as an asset or checksum itself. Annotation edits invalidate previous sizes/digests; update metadata after changes and generate a consistent snapshot at export. The central database holds the actual total package size; compute it from all files, including `config.json`, rather than merely summing listed assets.

The initial application budget is **300,000,000 bytes**, shared by all package assets and metadata. Download `receivedBytes` measures network payload; `packageBytes` measures actual package storage including database/manifest overhead. They are not interchangeable. Temporary staging, SQLite journals, and export copies also require additional free-space checks. Later writers must reserve overhead, enforce the effective application limit during writes/finalization and subsequent edits/imports, and return `SizeLimitExceeded` rather than silently dropping tiles. The serializable policy is a contract, not Phase 1 enforcement.

Version 1 supports PNG and JPEG tile content only. `VECTOR` reserves a future discriminator but reports unsupported compatibility now. Unknown schema versions and unknown formats must be rejected; no automatic migration is implied. Adding unknown required semantics needs a version change. JSON round-trip fixtures in `ManifestContractsTest` cover mixed raster layers, optional assets, absent version, and unsupported content/version.

## Lifecycle, consistency, and cancellation

The central metadata state is independent of the final manifest:

| State | Behavior |
| --- | --- |
| BUILDING / PAUSED | Staged work; resumable job records are separate from the final package |
| FINALIZING | Assets/manifests are being committed; not available to the viewer |
| READY | Verified final assets and metadata agree; available to open |
| FAILED | Work failed; retained partial data may be repaired/resumed |
| DELETING | Close handles and remove assets, then remove metadata |
| CORRUPT / MISSING | Existing metadata points to invalid/absent package data; offer recovery/removal |

The default library query shows READY packages. Explicit state filters can expose incomplete/recovery entries. `open` returns `NotReady` for partial data; missing and damaged packages produce explicit failures. Query ordering is updated timestamp descending with package ID as a stable tie-breaker. Cursors are opaque, bound to query filters; implementations in `core:database` own filtering/pagination. A filter change resets the cursor.

Filesystem and Room updates cannot form a shared transaction. Phase 4 must stage writes, close/checkpoint databases, write the final manifest, promote files on the same filesystem, and only then mark READY. Startup reconciliation detects abandoned staging, final files without metadata, READY rows without files, and interrupted deletion. A valid orphan final package may be reindexed; an incomplete package must never be inferred READY solely from its directory name.

Download start is idempotent by package ID for the same request; conflicting active requests return `Conflict`. Pause retains checkpoints. Cancel with `KEEP_FOR_RESUME` stops execution while retaining recoverable work; cancel with `DELETE` removes partial work. Resume is defined for paused/failed/retained-cancelled jobs, never completed or deleted jobs. The future executor persists the exact request and completed logical tile identities.

Progress is an immutable snapshot. Completed tiles count unique successful writes, failed tiles count unresolved failures, total tiles count all requested layer/tile pairs, and received bytes may include retries. COMPLETED requires all required tiles and finalization; failure must accompany FAILED. Observing a job must never start or cancel it. Cancelling a collector only ends that observation; explicit executor methods change durable job state.

Suspending contracts propagate `CancellationException`; coroutine cancellation is never converted into a network/IO failure. Expected operational failures use `PackageResult` or `TileReadResult`. Implementations copy collections before publishing and never mutate published snapshots. `TileSource.close` and `OpenedPackage.close` are idempotent; the package session owns its child sources and closes them. Reads after closure return CLOSED. Adapters define safe concurrent reads and release file/network resources on cancellation.

## Verification

Phase 1 host tests cover provider axis ordering, extensibility/configuration, level 0 and large tile indices, manifest round trips/compatibility, and fake online/local source substitutability. Both iOS device and simulator source targets compile. No live tile downloads are part of these tests.

Commands:

```sh
./gradlew :domain:providers:testAndroidHostTest :domain:map-builder:testAndroidHostTest :domain:map-builder:compileKotlinIosArm64 :domain:map-builder:compileKotlinIosSimulatorArm64
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64 :androidApp:assembleDebug
```

Both commands passed, with 10 host tests and no test failures. Apps were not launched and native tests were not executed. Existing build warnings report Compose runtime/plugin version skew, compile SDK 37 beyond AGP 9.1.0's tested range, and an inferred shared framework bundle ID. Phase 0 should align and validate the stack before renderer/device integration. Successful contract compilation does not resolve those runtime compatibility warnings.
