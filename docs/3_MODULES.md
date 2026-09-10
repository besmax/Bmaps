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
    * **Responsibility:** Application chrome, theme presentation, and preferences dialog with its own ViewModel. Accepts content/navigation callbacks from `:shared`; never imports other features. Theme preferences use `core:datastore` and ViewModel injection uses `core:di`.
* **`feature:constructor`**
    * **Phase 3C:** Online map presentation, source/style selection, presentation validation, loading/error/retry state, attribution, and a separate dialog-scoped credentials ViewModel. Uses `domain:providers`, `core:map-engine`, `core:datastore`, and `core:di`.
    * **Responsibility:** UI for the map builder. Handles user interaction for bounding box selection, zoom level toggling, layer opacity control, and initiating the download process.
* **`feature:library`**
    * **Responsibility:** UI for managing local `.mbtiles` packages. Displays downloaded maps, storage metrics, and handles user actions for deleting or sharing packages.
* **`feature:viewer`**
    * **Responsibility:** The offline map rendering screen. Integrates with the local `TileStreamProvider` to render `.mbtiles` databases, apply polygon/marker annotations, and render DEM altitude overlays.

## 3. Domain Layer (Business Logic & Contracts)
Modules containing pure use cases, models, and interface contracts.
* **`domain:providers`**
    * **Responsibility:** Provider/style identities, extensible provider definitions, endpoint and credential-reference contracts, configuration, attribution, and online/offline capabilities. Includes the provider registry, URL builders, and online tile-source adapter. Provider policy remains here; raw HTTP execution belongs to `core:network`.
    * **Dependencies:** `core:map-engine` for renderer-independent geometry and tile contracts; `core:network` for bounded HTTP, `core:datastore` for encrypted credentials, and `core:di` for Metro contributions.
* **`domain:map-builder`**
    * **Responsibility:** Offline build planning and execution contracts, versioned package manifests, size policy, lifecycle, library/open/delete contracts, and failures/progress. Owns package-related annotation, elevation, and transfer business contracts as those phases are implemented.
    * **Dependencies:** `domain:providers` for source identities/configuration and `core:map-engine` for geometry/tile contracts. The domain-to-domain dependency is one-way; providers do not depend on map-builder.
    * **Implementation ownership:** Domain adapters implement domain repositories using lower-level core APIs; core modules never import domain contracts. Metro bindings are assembled in `:shared`. No persistence or download implementation exists in Phase 1.

## 4. Core Layer (Infrastructure & Data)
Isolated infrastructure modules. Cross-dependencies within this layer must be minimized.
* **`core:network`**
    * **Responsibility:** Ktor HTTP client configuration, interceptors, timeouts, and bounded raw byte downloading capabilities. Depends on `core:di` for application-scoped client bindings; knows nothing about providers.
* **`core:database`**
    * **Responsibility:** Main application database (Room KMP) for metadata (e.g., saved projects, history).
    * **Constraints:** Custom pagination and complex filtering must be implemented directly here. Do not use Paging 3.
* **`core:mbtiles`**
    * **Responsibility:** Specialized SQLite driver logic to dynamically read/write tile blobs to `.mbtiles` files on the device filesystem.
* **`core:datastore`**
    * **Responsibility:** KMP DataStore implementation for persisting user preferences and system flags (e.g., default coordinate system, theme), and encrypted provider credentials. Platform encryption keys remain in Android Keystore or iOS Keychain.
    * **Dependencies:** `core:di` for application scope and Metro contributions. Platform DataStore construction remains here; platform entry points supply Android application context through the umbrella graph.
* **`core:storage`**
    * **Responsibility:** Cross-platform file system management (`kotlinx-io-core`). Handles directory creation, `.mbtiles` packaging, DEM matrix file parsing, and I/O for sharing/importing, also there are classes for working with annotations.db and classes for import/export our map objects (markers, routes, etc.) to/from GEOJson located .
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
