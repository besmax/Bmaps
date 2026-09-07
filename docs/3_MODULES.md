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
* **`feature:constructor`**
    * **Responsibility:** UI for the map builder. Handles user interaction for bounding box selection, zoom level toggling, layer opacity control, and initiating the download process.
* **`feature:library`**
    * **Responsibility:** UI for managing local `.mbtiles` packages. Displays downloaded maps, storage metrics, and handles user actions for deleting or sharing packages.
* **`feature:viewer`**
    * **Responsibility:** The offline map rendering screen. Integrates with the local `TileStreamProvider` to render `.mbtiles` databases, apply polygon/marker annotations, and render DEM altitude overlays.

## 3. Domain Layer (Business Logic & Contracts)
Modules containing pure use cases, models, and interface contracts.
* **`domain:providers`**
    * **Responsibility:** Defines models and repositories for available raster tile providers (e.g., OSM, Satellite, topographic).
* **`domain:map-builder`**
    * **Responsibility:** Orchestrates the complex process of compiling an offline map. Calculates required tile matrices (X, Y, Z) from bounding boxes and manages the download queue orchestration.

## 4. Core Layer (Infrastructure & Data)
Isolated infrastructure modules. Cross-dependencies within this layer must be minimized.
* **`core:network`**
    * **Responsibility:** Ktor HTTP client configuration, interceptors, timeouts, and raw byte downloading capabilities.
* **`core:database`**
    * **Responsibility:** Main application database (Room KMP) for metadata (e.g., saved projects, history).
    * **Constraints:** Custom pagination and complex filtering must be implemented directly here. Do not use Paging 3.
* **`core:mbtiles`**
    * **Responsibility:** Specialized SQLite driver logic to dynamically read/write tile blobs to `.mbtiles` files on the device filesystem.
* **`core:datastore`**
    * **Responsibility:** KMP DataStore implementation for persisting user preferences and system flags (e.g., default coordinate system, theme).
* **`core:storage`**
    * **Responsibility:** Cross-platform file system management (`kotlinx-io-core`). Handles directory creation, `.mbtiles` packaging, DEM matrix file parsing, and I/O for sharing/importing.
* **`core:map-engine`**
    * **Responsibility:** Wrappers for `MapComposeMP`. Encapsulates geospatial mathematics, bounding box calculations, and coordinate system transformations (WGS-84, SK-91).
* **`core:di`**
    * **Responsibility:** Global abstractions and scopes for Metro DI.

## 5. Adding a New Module
When a new module is required:
1. Identify the correct layer (`feature`, `domain`, `core`).
2. Create the directory structure using the `build-logic` convention plugins.
3. Update this `3_MODULES.md` file immediately with the new module's name, responsibility, and constraints.