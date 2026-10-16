---
type: "module_registry"
project: "Bmaps"
purpose: "Maintain a live index of all project modules, their strict responsibilities, and layer classifications."
---

# Bmaps: Module Registry

This document serves as the single source of truth for the project's module topography. Before creating a new module or adding a dependency, agents must consult this registry to ensure architectural compliance.

## 1. Umbrella Layer
* **`:shared`**
    * **Responsibility:** The root aggregator. Assembles the Metro DI graph, connects interfaces to implementations, and exposes the root UI targets.
    * **Constraints:** Must contain ZERO presentation, domain, or data logic. Only this module is allowed to configure iOS `.framework` generation.

## 2. Feature Layer (Presentation & UI)
Modules containing Compose Multiplatform screens, ViewModels, and presentation-layer validation logic.
* **`feature:shell`**
    * **Responsibility:** Application chrome, theme selection/application, and a responsive Settings screen with its own ViewModel and independent immediate persistence. Platform presentation adapters read the installed app version code for the About/licensing card. Accepts content/navigation callbacks from `:shared`; never imports other features. Visual theme definitions come from `core:ui`. Theme preferences use `core:datastore` and ViewModel injection uses `core:di`.
* **`feature:constructor`**
    * **Phase 3C:** Online map presentation, source/style selection, presentation validation, loading/error/retry state, attribution, and a separate dialog-scoped credentials ViewModel. Uses `core:ui`, `domain:providers`, `core:map-engine`, `core:location`, `core:datastore`, and `core:di`.
    * **Responsibility:** UI for the map builder. Handles user interaction for bounding box selection, continuous zoom range selection, layer ordering and visibility, and initiating the download process.
* **`feature:library`**
    * **Responsibility:** Home UI for local packages, constructor FAB, search/status/favourite filters, incremental loading, details, avatar preferences, and confirmed deletion. Retains download progress and recovery actions. Map details own a cancellable full-verification ViewModel and result display. Phase 10 adds native package import/sharing through a separate transfer ViewModel.
* **`feature:viewer`**
    * **Responsibility:** The offline map rendering screen. Phase 6 owns package sessions, regional raster configuration, exact-level selection, lifecycle viewport retention, details, and favourite/avatar editing. Depends on `domain:map-builder`, `domain:activity`, `core:map-engine`, `core:location`, `core:ui`, and `core:di`. Phase 7 owns raster layer composition. Phase 8 adds a separate annotation editor, kind-specific layer managers, SVG marker icons, Undo, GeoJSON controls, and presentation-only clustering for nearby markers, small lines, and polygons. Phase 9 adds a separate map-position ViewModel, saved coordinate formatting and package-owned DEM sampling for the bottom center readout. Track T4 adds `TrackViewModel`, immutable activity display state, preparation/route import, compact controls, camera-follow integration and a segmented overlay layer; the application session retains recording ownership. Track T5 adds history/review ViewModels, paginated activity/route lists, source-aware charts, synchronized map/time selection and package-free viewing, plus share/export UI.

### Feature package organization

Feature roots are `bes.max.bmaps.feature.<module>`. Packages always group code by capability, then by role: `ui` owns screens, dialogs, components and Compose effects; `presentation` owns ViewModels, their state/events, validation and presentation calculations. There are no root-level `ui` or `presentation` packages. Main map screens belong to `map`, the library list to `catalog`, and shell composition to `navigation`. State and event types remain next to their owning ViewModel. Create role subpackages only where code exists. Packages are organizational boundaries within the existing module; they do not introduce dependencies between features or change ViewModel ownership.

| Module | Capability packages relative to the feature root |
| --- | --- |
| `constructor` | `map.ui`, `map.presentation`, `map.fixture`; `selection.ui`, `selection.presentation`; `download.ui`, `download.presentation`; `credentials.ui`, `credentials.presentation`; `location.ui` |
| `library` | `catalog.ui`, `catalog.presentation`, `transfer.presentation` |
| `shell` | `navigation.ui`, `navigation.presentation`; `settings.ui`, `settings.presentation`; `about.ui` |
| `viewer` | `map.ui`, `map.presentation`; `annotations.ui`, `annotations.ui.icons`, `annotations.presentation`; `elevation.ui`, `elevation.presentation`; `position.ui`, `position.presentation`; `location.ui`; `activity.ui`, `activity.presentation`; `history.ui`, `history.presentation` |

`map.fixture` contains the constructor's sample tile source; its Compose widget and ViewModel belong to `map.ui` and `map.presentation`. Viewer annotation icons retain the existing SVG catalog and rendering helpers in `annotations.ui.icons`. Platform notification-permission adapters share `constructor.download.ui` with their common declaration; app-version adapters share `shell.about.ui`. Tests use the package of the subject under test, importing other capabilities explicitly when needed. Generated Compose resource namespaces remain unchanged.

`domain:map-builder` also owns `AnnotationElevationResolver`, which fills unknown vertex heights from the package DEM before editor commits.

`CalloutLocationViewModel` belongs to `viewer.annotations.presentation` and owns package-local height lookup and angular-format state for object callouts; callout UI stays in `viewer.annotations.ui`.

The umbrella imports screen entry points from their owning UI packages: `constructor.map.ui.ConstructorScreen`, `constructor.map.ui.FullScreenMap`, `constructor.download.ui.MapSaveSettingsContent`, `constructor.credentials.ui.ProviderCredentialsContent`, `library.catalog.ui.LibraryScreen`, `shell.navigation.ui.AppShell`, `shell.settings.ui.SettingsScreen`, and `viewer.map.ui.ViewerScreen`.

## 3. Domain Layer (Business Logic & Contracts)
Modules containing pure use cases, models, and interface contracts.
* **`domain:activity`**
    * **Responsibility:** Application-owned segmented reusable routes and frozen route snapshots, activity lifecycle policy, timestamped/provenance-bearing telemetry, repository/session contracts, independent validation, data limits, and wearable DTOs. T1 implements storage/import adapters; T2 implements the phone session, native service/background bridges, bounded recording, quality, statistics and recovery. T3 owns the full-geometry spherical index, bounded ordered/directed matching, quality/progress/gap policy, control/deviation alerts, navigation commands and frozen recorded return. T5 adds caller-owned streaming geometry GPX and versioned telemetry JSON export; reusable-route derivation can replace a link only after deliberate route deletion. Domain values are not annotated as Compose-stable presentation models.
    * **Dependencies:** `core:map-engine` for renderer-independent coordinates; `core:storage` and `core:database` primitive APIs for repository adapters; `core:di` for application scope and `core:location` for shared phone fixes. Serialization, coroutines, immutable collections, kotlinx-io and XMLUtil resolve through the catalog. Android native hosting uses catalog-managed AndroidX core; iOS uses system Core Location/UIKit. No feature, platform HealthKit, or renderer implementation imports. Applies `app.android.library`, `app.serialization`, and `app.di`; no iOS framework generation.
    * **T0–T3 status:** T0 app build/launch was reported by the owner without a specified platform. T1 source and regression scenarios are present; T1 compilation/tests and migration/native acceptance are unrun. T2 source/regression checks are present, with builds/tests/native background acceptance pending. The umbrella binds repositories, clock and session, and exposes native graph entry points. T3 source and authored navigation/return/storage scenarios are present, with builds/tests/calibration pending. UI and production wearable integration remain later phases. See `22_TRACK_MODE_AND_TELEMETRY.md`.
* **`domain:providers`**
    * **Responsibility:** Provider/style identities, extensible provider definitions, endpoint and credential-reference contracts, configuration, attribution, and online/offline capabilities. Includes the provider registry, URL builders, and online tile-source adapter. Provider policy remains here; raw HTTP execution belongs to `core:network`. Also owns OpenTopography dataset identities, request constraints/estimates, credential resolution, and the streaming elevation provider adapter.
    * **Dependencies:** `core:map-engine` for renderer-independent geometry and tile contracts; `core:network` for bounded HTTP, `core:datastore` for encrypted credentials, and `core:di` for Metro contributions.
* **`domain:map-builder`**
    * **Responsibility:** Offline build planning and execution contracts, versioned package manifests, size policy, lifecycle, library/open/delete contracts, and failures/progress. Owns annotation geometry, validation rules, GeoJSON mapping, GPX import, and repository contracts. Catalog-pinned XMLUtil core supplies common XML parsing for GPX on Android/iOS. Elevation asset download selection, failures, and package completion are implemented; Phase 9 adds package-owned elevation sampling and typed availability results; Phase 10 adds stream-based package transfer contracts and snapshot/import orchestration.
    * **Dependencies:** `domain:providers` for source identities/configuration and `core:map-engine` for geometry/tile contracts. Phase 4 adds `core:storage`, `core:mbtiles`, `core:database`, and `core:di` for the package repository adapter. The domain-to-domain dependency is one-way; providers do not depend on map-builder.
    * **Implementation ownership:** Domain adapters implement domain repositories using lower-level core APIs; core modules never import domain contracts. Metro bindings are assembled in `:shared`. Phase 4 implements persistence and reconciliation through `LocalPackageRepository`; Phase 5 adds streaming tile planning, durable execution, Android WorkManager and iOS BGProcessingTask adapters. The constructor owns submission validation; the library owns list/progress/recovery presentation. `core:map-engine` exposes raster dimension validation without renderer types. See `12_DOWNLOAD_PIPELINE.md`.

## 4. Core Layer (Infrastructure, Shared UI & Data)
* **`:core:location`**
    * **Responsibility:** Renderer-independent native location streams, shared map/recorder subscription distribution, foreground permission launchers and location widget lifecycle attachment. Background sensor policy is requested by the domain-owned recorder; core has no domain imports. Android uses internal GPS and available native fused/network providers, supplemented by Google Fused Location Provider when Google Play services are available. Latest fixes are retained per source and selected by acquisition age and reported accuracy, with periodic expiry/fallback selection and severe GPS/fused-versus-network disagreement protection. Native subscriptions remain active independently of Google availability or acquisition failures; the catalog-pinned Google SDK is an Android-only implementation dependency. iOS uses Core Location. Fixes include WGS 84 coordinates, horizontal accuracy, epoch/native uptime, provider, optional speed/speed accuracy, course and altitude/vertical accuracy.
    * **Constraints:** No domain, feature, or renderer dependencies. Collection owns native updates and cancellation releases listeners/delegates. Feature map ViewModels own camera decisions; navigator camera following and background tracking remain future work.
    * **Build:** Existing Android, Compose Multiplatform, and DI convention plugins with catalog dependencies. Metro contributes the platform service and unscoped widget ViewModel; no iOS framework.

* **`:core:sharing`**
    * **Responsibility:** Native document picker/share adapters, transient document access, bounded temporary export files, and expiry cleanup. Compose launchers contain only platform presentation machinery; feature ViewModels own transfer state. Depends on `core:di`, Compose, coroutines, and kotlinx-io; no domain dependencies.
    * **Build:** Existing Android, Compose Multiplatform, and DI convention plugins; platform dependencies come from the central catalog.

Isolated infrastructure modules. Cross-dependencies within this layer must be minimized.
* **`core:ui`**
    * **Responsibility:** Shared Compose Multiplatform visual infrastructure: `BmapsTheme`, palette, typography, shapes, bundled fonts/license, and reusable components such as `MapIconButton` with shared `MapIcons`.
    * **Constraints:** Depends on Compose and resources only; no feature, domain, renderer, persistence, or DI dependencies. Components accept display values and callbacks; feature ViewModels own behavior and validation. Feature-specific components remain in their owning feature.
    * **Build:** Applies `app.android.library` and `app.compose.multiplatform`; does not generate an iOS framework.
* **`core:network`**
    * **Responsibility:** Ktor HTTP client configuration, interceptors, timeouts, and bounded raw byte downloading capabilities. Depends on `core:di` for application-scoped client bindings; knows nothing about providers.
* **`core:database`**
    * **Responsibility:** Main application database (Room KMP) for metadata (e.g., saved projects, history).
    * **Constraints:** Custom pagination and complex filtering must be implemented directly here. Do not use Paging 3.
    * **Phase 4:** Room package/job schema, typed core records, transactional checkpoints, and filter-bound keyset pagination. Phase 6 adds a migrated package preference table, favourite filtering, and preference-aware observations. Depends only on `core:di` for application scope; persistence tooling is in `app.persistence`.
    * **Track T1:** Schema 4 adds independent activity/route catalog projection tables, custom filtering/keyset queries, and a non-destructive 3-to-4 migration. Raw geometry/telemetry stays outside Room; no package foreign-key cascade. Generated schema review and native migration acceptance remain pending.
* **`core:mbtiles`**
    * **Responsibility:** Specialized SQLite driver logic to dynamically read/write tile blobs to `.mbtiles` files on the device filesystem.
    * **Phase 4:** Custom bundled-SQLite adapter, XYZ/TMS conversion, bounded atomic tile/failure batches, integrity checks, and serialized handle access. No core-to-core dependencies. Phase 10 adds bounded standalone tile iteration and verified package identity rebinding.
* **`core:datastore`**
    * **Responsibility:** KMP DataStore implementation for persisting user preferences and system flags (e.g., default coordinate system, theme), and encrypted provider credentials. Platform encryption keys remain in Android Keystore or iOS Keychain.
    * **Dependencies:** `core:di` for application scope and Metro contributions. Platform DataStore construction remains here; platform entry points supply Android application context through the umbrella graph.
* **`core:storage`**
    * **Responsibility:** Cross-platform file system management (`kotlinx-io-core`). Handles directory creation, `.mbtiles` packaging, DEM matrix file parsing, and I/O for sharing/importing, Phase 8 owns package-local annotation SQLite access; GeoJSON semantic mapping belongs to `domain:map-builder`.
    * **Phase 4:** Private staging/final directories, bounded streaming, synchronization, capacity/size checks, path safety, and cleanup. Depends on `core:di` for platform location bindings and the catalog-pinned bundled SQLite driver for annotation databases. Bounded streamed DEM writes, temporary-file cleanup, and atomic asset commits are implemented. Phase 9 integrates DEM reader lifetime with package sessions in the domain adapter; Phase 10 adds bounded framed transfer IO, SHA-256, and SQLite snapshots.
    * **Track T1:** Application-root activity/route SQLite stores, primitive row APIs, serialized transactions, schema/version checks, journal recovery, capacity checks, symlink rejection, checkpoints, deletion tombstones, and secure deletion. T3 includes private immutable navigation snapshots in the initial activity SQLite schema 1 without a migration or version increase; routes remain schema 1. No domain dependency or recording policy; package inventories/accounting remain isolated.
    * **Phase 9 reader spike:** Owns the common DEM API and serialized reader sessions. Android depends on `core:tiff-native`; iOS embeds the same C wrapper and libtiff through cinterop. No renderer or domain dependencies are introduced.
* **`core:tiff-native`**
    * **Responsibility:** Internal libtiff C wrapper and Android JNI packaging. A standalone Android library is required because the Android KMP plugin does not support `externalNativeBuild`. The C sources are also compiled for iOS by the storage convention plugin. No business logic, DI graph, or iOS framework is defined here.
    * **Build:** `app.tiff.android` and `app.tiff.ios` own native configuration; `app.tiff.source` resolves the checksum-pinned source archive from the central catalog. Only required lossless DEM codecs are enabled.
* **`core:proj-native`**
    * **Responsibility:** Internal PROJ C wrapper and Android JNI library. The same sources are embedded through iOS cinterop in `core:map-engine`. Bundles the matching PROJ database and an independently namespaced SQLite amalgamation; no framework, UI, or domain logic.
    * **Build:** `app.proj.source`, `app.proj.android`, and `app.proj.ios` convention plugins own pinned source extraction, native compilation, and resource embedding.
* **`core:map-engine`**
    * **Responsibility:** Wrappers for `MapComposeMP`. Encapsulates geospatial mathematics, bounding box calculations, and coordinate system transformations, including WGS 84 EPSG:4326, Pulkovo 1942 EPSG:4284, and PZ-90.11 EPSG:9475 via PROJ.
    * **Phase 9:** Owns the PROJ adapter behind the transformation contract. Native packaging uses Android JNI/iOS cinterop and convention plugins, catalog-pinned sources, embedded `proj.db`, and an isolated SQLite amalgamation. Only grid-free offline operations are eligible. Source implementation is present; native compilation and runtime acceptance remain pending.
    * **Phase 3B:** Owns the bounded MapComposeMP raster adapter, source-session cleanup, Web Mercator/XYZ mathematics, and regional tile-pyramid configuration.
    * **Contract ownership:** Renderer-independent coordinates, CRS identifiers, bounds, tile keys, content descriptors, and tile-source/transform interfaces. Public contracts do not expose MapComposeMP types. Tile matrix enumeration belongs to `domain:map-builder`; reusable coordinate mathematics belongs here.
* **`core:di`**
    * **Responsibility:** Global abstractions and scopes for Metro DI.
    * **ViewModel integration:** Owns the Metro ViewModel factory binding. This factory creates unscoped ViewModels; navigation entries and native root owners control their lifetime, independently of application-scoped services.

## 5. Adding a New Module
When a new module is required:
1. Identify the correct layer (`feature`, `domain`, `core`).
2. Create the directory structure using the `build-logic` convention plugins.
3. Update this `3_MODULES.md` file immediately with the new module's name, responsibility, and constraints.

## Generated elevation layers — 2026-10-09

No new module is introduced. `domain:map-builder` owns generation contracts/orchestration, durable job state, platform scheduling, final package commit and size updates. `core:storage` owns batch DEM/native access and job-file infrastructure; `core:map-engine` owns native PNG encoding. `feature:viewer` owns progress and a separately scoped settings ViewModel. `shared` only wires scheduler startup and the Android worker factory. See `21_ELEVATION_COLOR_LAYERS.md` for source state and pending compilation/acceptance.

Unified map objects (2026-10-13): `viewer.annotations.presentation.MapObjectsViewModel` owns the global paginated route catalog, selected immutable route preview and confirmed mixed file/pasted import. Its `annotations.ui` components receive immutable display models/callbacks. The existing annotation editor owns package geometry edits; `AnnotationFileViewModel` owns outbound GeoJSON sharing. `domain:activity.ActivityRouteGpx.decodeDocument` returns grouped routes and waypoint DTOs without depending on `domain:map-builder`; the viewer maps waypoints into annotations and coordinates independent repositories. Track imports use the same viewer flow. No new module or schema is introduced.

Phone tracking/map ownership update (2026-10-16): the current contract supersedes the earlier app-owned user-route design. Detailed routes use the registered map `routes.json` asset through a core `PackageRouteStore` interface implemented under package locks; Room filters package-prefixed summaries without a schema change. Independent activity records persist their source map and frozen route snapshots; a shared Metro reservation/durable metadata guard protects active-map deletion. `viewer/{packageId}/track` reuses the map with dedicated controls, foreground location during pause, navigator arrow, free-area follow and explicit pan/recenter handling. Legacy unassigned routes can be copied into a selected map. Activity/route spatial versions stay 1, Room stays 4, Watch stays T7. The source increment has static verification only; builds/tests/native/performance acceptance remain with the owner. Current detailed contracts, limits, evidence and handoff are in `22_TRACK_MODE_AND_TELEMETRY.md` section 8.12.
