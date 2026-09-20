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
    * **Responsibility:** Application chrome, theme selection/application, and preferences dialog with its own ViewModel. Accepts content/navigation callbacks from `:shared`; never imports other features. Visual theme definitions come from `core:ui`. Theme preferences use `core:datastore` and ViewModel injection uses `core:di`.
* **`feature:constructor`**
    * **Phase 3C:** Online map presentation, source/style selection, presentation validation, loading/error/retry state, attribution, and a separate dialog-scoped credentials ViewModel. Uses `core:ui`, `domain:providers`, `core:map-engine`, `core:datastore`, and `core:di`.
    * **Responsibility:** UI for the map builder. Handles user interaction for bounding box selection, continuous zoom range selection, layer ordering and visibility, and initiating the download process.
* **`feature:library`**
    * **Responsibility:** Home UI for local packages, constructor FAB, search/status/favourite filters, incremental loading, details, avatar preferences, and confirmed deletion. Retains download progress and recovery actions. Sharing remains Phase 10.
* **`feature:viewer`**
    * **Responsibility:** The offline map rendering screen. Phase 6 owns package sessions, regional raster configuration, exact-level selection, lifecycle viewport retention, details, and favourite/avatar editing. Depends on `domain:map-builder`, `core:map-engine`, `core:ui`, and `core:di`. Phase 7 owns raster layer composition. Phase 8 adds a separate annotation editor, kind-specific layer managers, SVG marker icons, Undo, GeoJSON controls, and presentation-only clustering for nearby markers, small lines, and polygons. DEM overlays remain a subsequent phase.

## 3. Domain Layer (Business Logic & Contracts)
Modules containing pure use cases, models, and interface contracts.
* **`domain:providers`**
    * **Responsibility:** Provider/style identities, extensible provider definitions, endpoint and credential-reference contracts, configuration, attribution, and online/offline capabilities. Includes the provider registry, URL builders, and online tile-source adapter. Provider policy remains here; raw HTTP execution belongs to `core:network`.
    * **Dependencies:** `core:map-engine` for renderer-independent geometry and tile contracts; `core:network` for bounded HTTP, `core:datastore` for encrypted credentials, and `core:di` for Metro contributions.
* **`domain:map-builder`**
    * **Responsibility:** Offline build planning and execution contracts, versioned package manifests, size policy, lifecycle, library/open/delete contracts, and failures/progress. Owns annotation geometry, validation rules, GeoJSON mapping, and repository contracts. Elevation and transfer business contracts follow in later phases.
    * **Dependencies:** `domain:providers` for source identities/configuration and `core:map-engine` for geometry/tile contracts. Phase 4 adds `core:storage`, `core:mbtiles`, `core:database`, and `core:di` for the package repository adapter. The domain-to-domain dependency is one-way; providers do not depend on map-builder.
    * **Implementation ownership:** Domain adapters implement domain repositories using lower-level core APIs; core modules never import domain contracts. Metro bindings are assembled in `:shared`. Phase 4 implements persistence and reconciliation through `LocalPackageRepository`; Phase 5 adds streaming tile planning, durable execution, Android WorkManager and iOS BGProcessingTask adapters. The constructor owns submission validation; the library owns list/progress/recovery presentation. `core:map-engine` exposes raster dimension validation without renderer types. See `12_DOWNLOAD_PIPELINE.md`.

## 4. Core Layer (Infrastructure, Shared UI & Data)
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
* **`core:mbtiles`**
    * **Responsibility:** Specialized SQLite driver logic to dynamically read/write tile blobs to `.mbtiles` files on the device filesystem.
    * **Phase 4:** Custom bundled-SQLite adapter, XYZ/TMS conversion, bounded atomic tile/failure batches, integrity checks, and serialized handle access. No core-to-core dependencies.
* **`core:datastore`**
    * **Responsibility:** KMP DataStore implementation for persisting user preferences and system flags (e.g., default coordinate system, theme), and encrypted provider credentials. Platform encryption keys remain in Android Keystore or iOS Keychain.
    * **Dependencies:** `core:di` for application scope and Metro contributions. Platform DataStore construction remains here; platform entry points supply Android application context through the umbrella graph.
* **`core:storage`**
    * **Responsibility:** Cross-platform file system management (`kotlinx-io-core`). Handles directory creation, `.mbtiles` packaging, DEM matrix file parsing, and I/O for sharing/importing, Phase 8 owns package-local annotation SQLite access; GeoJSON semantic mapping belongs to `domain:map-builder`.
    * **Phase 4:** Private staging/final directories, bounded streaming, synchronization, capacity/size checks, path safety, and cleanup. Depends on `core:di` for platform location bindings and the catalog-pinned bundled SQLite driver for annotation databases. DEM parsing and transfer IO remain future work.
* **`core:map-engine`**
    * **Responsibility:** Wrappers for `MapComposeMP`. Encapsulates geospatial mathematics, bounding box calculations, and coordinate system transformations (WGS-84, SK-91).
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
