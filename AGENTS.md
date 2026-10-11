---
type: "agent_conventions"
project: "Bmaps"
purpose: "Strict architectural and coding rules for AI agents and human developers."
---

# Project Documentation
Read only the documentation relevant to the current task before making changes. Do not read every file in `docs/` by default. Use targeted searches and relevant sections to establish context; expand reading only when the task affects another area or a referenced contract is needed.

Use this guide to select documentation, not as a mandatory reading checklist:

| Task area | Relevant documentation |
| --- | --- |
| Product scope and requirements | `docs/1_VISION_AND_PRODUCT.md` |
| Architecture, dependencies, module creation or ownership, feature package organization | `docs/2_ARCHITECTURE.md`, `docs/3_MODULES.md` |
| UI state, ViewModels, events, stable presentation models and composable decomposition (screens, lists and list items) | `docs/4_STATE_AND_UI.md` |
| Phase work, current status and acceptance criteria | `docs/5_IMPLEMENTATION_PLAN.md` |
| Domain contracts and package format | `docs/6_CONTRACTS_AND_PACKAGE_FORMAT.md` |
| Feature shell, navigation and application composition | `docs/7_FEATURE_SHELL.md` |
| Providers, networking and credentials | `docs/8_PROVIDER_NETWORKING_AND_CREDENTIALS.md` |
| Map rendering, camera control and coordinate transformations | `docs/9_MAP_ENGINE.md` |
| Visual design and styling | `docs/10_BMAPS_DESIGN.md` |
| Package storage, MBTiles, disk-size accounting, cached verification, dirty-state recovery and reconciliation | `docs/11_PACKAGE_STORAGE.md` |
| Download execution, scheduling, progress and restore | `docs/12_DOWNLOAD_PIPELINE.md` |
| Library adaptive layouts, package opening/deletion, manual full verification and cancellation, offline viewer, adaptive control layout, immersive gestures and viewport retention | `docs/13_LIBRARY_AND_OFFLINE_VIEWER.md` |
| Raster layer alignment, ordering, visibility, opacity and composition | `docs/14_RASTER_LAYER_COMPOSITION.md` |
| Map annotations, marker icons, geometry editing, persisted vertex heights, callout coordinate-system display, GeoJSON and GPX import | `docs/15_ANNOTATIONS_AND_GEOJSON.md` |
| Map object clustering, cluster interaction and expansion | `docs/15_ANNOTATIONS_AND_GEOJSON.md`, `docs/plans/MAP_OBJECT_CLUSTERING.md` (design proposal; verify current implementation in source) |
| Elevation/DEM download selection, tile-envelope request bounds/margins, area/size estimates, footprint verification, provider credentials and persistence | `docs/16_ELEVATION_DOWNLOADS.md` |
| Elevation/DEM reading, TIFF/native integration, bounded grid/block caches, sampling, center lookup cancellation, compact viewer center readout, annotation callout height and pre-save vertex height sampling | `docs/17_ELEVATION_READER.md` |
| Generated elevation color layers, palette/range settings, background generation, performance pipeline/range cache, regeneration and disk-size accounting | `docs/21_ELEVATION_COLOR_LAYERS.md` |
| Licensing, provenance, source headers, packaged notices and release signing | `docs/18_LICENSING_AND_PROVENANCE.md`, `CONTRIBUTING.md` |
| Package import/export, `.bmaps` transfer format, standalone MBTiles and native document access/sharing | `docs/19_PACKAGE_TRANSFER.md` |
| Current location/GPS, permissions, native location providers, lifecycle, accuracy overlays, initial focus and navigator preparation | `docs/20_CURRENT_LOCATION.md` |

For tasks spanning multiple areas, read the relevant sections from each affected area. Discover additional documents with targeted filename or text searches when needed. Reuse context already read in the conversation unless it has changed or needs verification. Update affected documentation when behavior, contracts, or implementation status changes.

Keep this task-to-document guide current as part of the same change that adds, renames, moves, splits, or removes project documentation. Also update the affected rows when a document gains a new responsibility or a task area gets its own implementation document. Before completing documentation work, check that each new or changed task area has an accurate entry here and that referenced paths still exist. Do not leave new documents discoverable only through searches or links in other documents.

Documents under `docs/plans/` describe proposals and implementation approaches; they do not establish completion or override the current architecture and contracts. Use the relevant implementation document, `docs/5_IMPLEMENTATION_PLAN.md`, and source code to establish current behavior. Distinguish source implementation, automated verification, and user-reported device acceptance when updating status; do not mark unperformed builds or tests as passed.

# Bmaps: AI Agent Core Conventions

## 1. Commenting and Documentation
* **Minimal Comments:** Write comments *only* when absolutely necessary to explain complex, non-obvious logic or the "why" behind a specific architectural decision. If the code is self-explanatory, do not leave any comments.
* **English Only:** All comments, commit messages, and documentation must be written strictly in English.
* **License Notices:** Preserve original Bmaps license/provenance headers and `NOTICE`. Add the standard header to new original source files using `scripts/license_headers.py --write` after reviewing ownership; check with `scripts/license_headers.py`. Required legal notices are an intentional exception to the minimal-comments rule. Never replace third-party notices or attribute third-party code to the project owner. See `CONTRIBUTING.md` and `docs/18_LICENSING_AND_PROVENANCE.md`.

## 2. Architectural Boundaries
* **Composable Decomposition:** Split large composables into separate logical components. Every list must have its own composable, and each list item must have a separate item composable. Screen entry points resolve ViewModels, observe state and wire events; extracted UI components receive display data and callbacks. Keep small screen-local components in the same file. Extract a separate file only when a component is substantial in size or is used from multiple files. Keep feature-specific components in their owning feature. See `docs/4_STATE_AND_UI.md`.
* **Stable Presentation Models:** Always map Domain/Data models into screen-owned presentation models before exposing display state or passing it to composables. Keep these models in the owning `presentation` package, use only structurally immutable/stable fields and immutable collections (including nested values), and declare truthful Compose `@Immutable`/`@Stable` contracts. Do not wrap an unstable Domain object or ordinary `List`/`Set`/`Map` in an annotated class and claim stability. Pass each child only the display fields it needs so unrelated updates can be skipped. See `docs/4_STATE_AND_UI.md`.
* **Presentation Layer Validation:** Data validation rules must be invoked independently inside the presentation layer (UI/ViewModel). Do not embed data validation logic inside structural business use cases. Use cases must maintain single responsibility.
* **Data Layer Pagination:** Pagination and filtering must be custom-built directly within the data layer (`core:database`). This ensures maximum architectural flexibility for complex filters. Do not use standard generic pagination libraries (e.g., Paging 3).
* **Umbrella Module:** The `:shared` module acts exclusively as an umbrella module to assemble the Metro DI graph and expose platform entry points. It must not contain business logic.
* **Framework Generation:** Do not configure iOS static `.framework` generation in base convention plugins. Framework compilation is strictly reserved for the umbrella `:shared` module to prevent duplicate artifacts and build conflicts.

## 3. Modularity and Build System
* **Build-Logic:** All Gradle configuration must be managed through the `build-logic` composite build using Kotlin DSL convention plugins. Feature and core modules must apply these plugins rather than declaring manual configurations.
* **Dependency Management:** Dependencies must be resolved exclusively via the central Gradle Version Catalog.
* **Module Hierarchy:**
    * `app` depends on `feature`, `domain`, `core`.
    * `feature` depends on `domain`, `core`. Feature modules cannot depend on other feature modules.
    * `domain` depends on `core`.
    * `core` modules are isolated infrastructure. Cross-imports within `core` should be strictly minimized.

## 4. Tech Stack Constraints
* **UI:** Compose Multiplatform for all UI rendering.
* **Map Engine:** MapComposeMP for tile rendering.
* **Network:** Ktor Client with platform-specific engines, specifically OkHttp for Android and Darwin for iOS.
* **Storage:** Room KMP for primary application metadata; custom SQLite driver for `.mbtiles` read/write operations.
* **DI:** Metro DI (`dev.zacsweers.metro`). Use global scopes and avoid manual dependency passing.
* **File IO:** `kotlinx-io-core` for byte stream manipulation and cross-platform file handling.
