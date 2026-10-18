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
| Product scope and requirements | [Product scope](docs/1_VISION_AND_PRODUCT.md) |
| Architecture, dependency flow and umbrella boundaries | [Dependency flow](docs/2_ARCHITECTURE.md#1-macro-architecture--dependency-flow), [Umbrella](docs/2_ARCHITECTURE.md#2-the-umbrella-pattern-shared) |
| Module ownership, dependencies, new modules and feature package organization | [Native hosts](docs/3_MODULES.md#native-application-hosts), [Module registry](docs/3_MODULES.md#1-umbrella-layer), [Feature packages](docs/3_MODULES.md#feature-package-organization), [New modules](docs/3_MODULES.md#5-adding-a-new-module) |
| UI state, ViewModels and one-off events | [MVVM+](docs/4_STATE_AND_UI.md#1-mvvm-presentation-architecture), [State/events](docs/4_STATE_AND_UI.md#2-state--event-management) |
| Stable presentation models and composable/list/item decomposition | [Stable display models](docs/4_STATE_AND_UI.md#4-ui-performance--compose-stability), [Composable decomposition](docs/4_STATE_AND_UI.md#composable-decomposition) |
| Application phase status and acceptance criteria | [Current status](docs/5_IMPLEMENTATION_PLAN.md#current-implementation-status), [Phase deliverables](docs/5_IMPLEMENTATION_PLAN.md#4-detailed-implementation-sequence), [Completion evidence](docs/5_IMPLEMENTATION_PLAN.md#6-verification-strategy-and-completion-tracking) |
| Verification responsibilities, test selection, documentation checker and CI | [Verification policy](#verification-policy), [Documentation maintenance](CONTRIBUTING.md#documentation-maintenance) |
| Domain coordinates, package format, assets and lifecycle contracts | [Coordinates](docs/6_CONTRACTS_AND_PACKAGE_FORMAT.md#coordinates-and-levels), [Package format](docs/6_CONTRACTS_AND_PACKAGE_FORMAT.md#version-1-package-manifest), [Lifecycle](docs/6_CONTRACTS_AND_PACKAGE_FORMAT.md#lifecycle-consistency-and-cancellation) |
| Feature shell, navigation and application composition | [Shell/DI ownership](docs/7_FEATURE_SHELL.md#module-ownership), [Navigation/settings](docs/7_FEATURE_SHELL.md#navigation-and-settings-layout), [Activity destinations](docs/7_FEATURE_SHELL.md#activity-navigation) |
| Providers, networking and credentials | [Ownership](docs/8_PROVIDER_NETWORKING_AND_CREDENTIALS.md#ownership-and-composition), [Requests](docs/8_PROVIDER_NETWORKING_AND_CREDENTIALS.md#url-builders-and-configuration), [Credentials](docs/8_PROVIDER_NETWORKING_AND_CREDENTIALS.md#credential-storage), [Provider availability](docs/8_PROVIDER_NETWORKING_AND_CREDENTIALS.md#online-constructor-and-provider-availability) |
| Map rendering, camera APIs and coordinate transformations | [Coordinates/coverage](docs/9_MAP_ENGINE.md#coordinates-and-coverage), [Renderer/camera](docs/9_MAP_ENGINE.md#raster-api-and-engine-limits), [PROJ](docs/9_MAP_ENGINE.md#phase-9-proj-integration--2026-09-29) |
| Visual design and styling | [Visual design](docs/10_BMAPS_DESIGN.md), [Activity UI](docs/10_BMAPS_DESIGN.md#activity-ui) |
| Package storage, MBTiles, disk accounting and recovery | [Storage ownership](docs/11_PACKAGE_STORAGE.md#ownership), [Recovery](docs/11_PACKAGE_STORAGE.md#finalization-and-reconciliation), [Verification/dirty state](docs/11_PACKAGE_STORAGE.md#cached-verification-and-interrupted-mutations--2026-10-11) |
| Map-owned route assets and active-map deletion protection | [Package route lifecycle](docs/11_PACKAGE_STORAGE.md#map-owned-detailed-routes--2026-10-16), [Route identity/consistency](docs/22_TRACK_MODE_AND_TELEMETRY.md#812-map-owned-route-storage) |
| Download execution, scheduling, progress and restore | [Download flow](docs/12_DOWNLOAD_PIPELINE.md#user-flow), [Execution/recovery](docs/12_DOWNLOAD_PIPELINE.md#execution-and-recovery), [Android](docs/12_DOWNLOAD_PIPELINE.md#android), [iOS](docs/12_DOWNLOAD_PIPELINE.md#ios) |
| Library layout, package opening/deletion and manual verification/cancellation | [Library layout/navigation](docs/13_LIBRARY_AND_OFFLINE_VIEWER.md#navigation-and-library), [Deletion](docs/13_LIBRARY_AND_OFFLINE_VIEWER.md#persistence-and-deletion), [Manual verification](docs/13_LIBRARY_AND_OFFLINE_VIEWER.md#full-verification-in-map-details--2026-10-11) |
| Offline viewer, adaptive controls, viewport retention and immersive gestures | [Viewer lifetime](docs/13_LIBRARY_AND_OFFLINE_VIEWER.md#offline-rendering-and-lifetime), [Immersive gestures](docs/13_LIBRARY_AND_OFFLINE_VIEWER.md#offline-immersive-mode) |
| Raster layer alignment, ordering, visibility, opacity and composition | [Alignment](docs/14_RASTER_LAYER_COMPOSITION.md#constructor-and-alignment), [Composition](docs/14_RASTER_LAYER_COMPOSITION.md#configuration-and-rendering) |
| Map objects, marker icons, geometry editing and mixed GPX/GeoJSON import | [Editing](docs/15_ANNOTATIONS_AND_GEOJSON.md#ownership-and-editing), [Marker icons](docs/15_ANNOTATIONS_AND_GEOJSON.md#svg-marker-catalog), [Confirmed import/routes](docs/15_ANNOTATIONS_AND_GEOJSON.md#unified-object-import-and-map-owned-routes) |
| Map object clustering, cluster interaction and expansion | [Current annotations/clustering](docs/15_ANNOTATIONS_AND_GEOJSON.md#ownership-and-editing), [Clustering proposal](docs/plans/MAP_OBJECT_CLUSTERING.md) (proposal; verify current implementation in source) |
| Persisted vertex heights and callout coordinate-system display | [Vertex heights](docs/15_ANNOTATIONS_AND_GEOJSON.md#persisted-vertex-heights--2026-10-10), [Callout CRS](docs/15_ANNOTATIONS_AND_GEOJSON.md#callout-display-crs-correction--2026-10-11) |
| DEM download selection, estimates, credentials, persistence and tile-envelope margins | [Selection/credentials](docs/16_ELEVATION_DOWNLOADS.md#selection-and-credentials), [Download/persistence](docs/16_ELEVATION_DOWNLOADS.md#download-and-persistence), [Envelope/margins](docs/16_ELEVATION_DOWNLOADS.md#complete-tile-coverage-with-dem-margin--2026-10-09) |
| DEM reading, TIFF/native IO, bounded caches and sampling | [Supported DEM](docs/17_ELEVATION_READER.md#initial-supported-file-contract), [IO/cache limits](docs/17_ELEVATION_READER.md#memory-and-io), [Batch sampling](docs/17_ELEVATION_READER.md#bounded-row-batches-for-color-generation--2026-10-09) |
| Viewer center elevation/readout, lookup cancellation and pre-save height sampling | [Center readout/cancellation](docs/17_ELEVATION_READER.md#viewer-center-increment--2026-09-29), [Pre-save/callout heights](docs/15_ANNOTATIONS_AND_GEOJSON.md#persisted-vertex-heights--2026-10-10) |
| Generated elevation layers, palette/range, background generation, regeneration and accounting | [Relief settings](docs/21_ELEVATION_COLOR_LAYERS.md#user-flow), [Generation/cache](docs/21_ELEVATION_COLOR_LAYERS.md#raster-generation), [Background execution](docs/21_ELEVATION_COLOR_LAYERS.md#durable-execution-and-lifecycle), [Commit/accounting](docs/21_ELEVATION_COLOR_LAYERS.md#commit-regeneration-and-storage-size) |
| Licensing, provenance, source headers, packaged notices and release signing | [Source/packaged notices](docs/18_LICENSING_AND_PROVENANCE.md#provenance-notices), [Release signing](docs/18_LICENSING_AND_PROVENANCE.md#signed-release-procedure), [Contributions](CONTRIBUTING.md) |
| Package import/export, .bmaps format, standalone MBTiles and native documents | [Transfer format](docs/19_PACKAGE_TRANSFER.md#version-1-bmaps-wire-format), [MBTiles import](docs/19_PACKAGE_TRANSFER.md#standalone-mbtiles-subset), [Native sharing/access](docs/19_PACKAGE_TRANSFER.md#native-access-and-cleanup) |
| Current location/GPS, sources, permissions, freshness/conflicts and initial focus | [Location sources/contracts](docs/20_CURRENT_LOCATION.md#ownership-and-contracts), [Permissions/lifecycle/focus](docs/20_CURRENT_LOCATION.md#presentation-and-lifecycle) |
| Shared map/recorder location, acquisition uptime/provenance/altitude and background ownership | [Recorder/location boundary](docs/20_CURRENT_LOCATION.md#activity-owned-recording-and-navigation), [Paused foreground location](docs/20_CURRENT_LOCATION.md#tracking-screen-foreground-location), [Session/background lifecycle](docs/22_TRACK_MODE_AND_TELEMETRY.md#88-phone-recording-and-recovery) |
| Track-mode scope, route/activity ownership and domain contracts | [Scope](docs/22_TRACK_MODE_AND_TELEMETRY.md#1-scope), [Entities/lifetime](docs/22_TRACK_MODE_AND_TELEMETRY.md#2-product-entities-and-ownership), [Domain contracts](docs/22_TRACK_MODE_AND_TELEMETRY.md#81-implemented-domain-contracts) |
| Track start dialog, map-selected start, dedicated screen, controls, arrow and follow/free camera | [Entry points](docs/22_TRACK_MODE_AND_TELEMETRY.md#3-map-entry-points-and-layout), [Preparation/tracking/camera](docs/22_TRACK_MODE_AND_TELEMETRY.md#810-map-preparation-and-tracking-ui) |
| Complex-route matching, progress/gaps, navigation commands/alerts and recorded return | [Navigation behavior](docs/22_TRACK_MODE_AND_TELEMETRY.md#4-route-navigation-behavior), [Matcher/return contracts](docs/22_TRACK_MODE_AND_TELEMETRY.md#89-route-matching-progress-and-recorded-return) |
| Activity telemetry, clocks, recording quality, pause, recovery and spatial/catalog consistency | [Storage/batching](docs/22_TRACK_MODE_AND_TELEMETRY.md#82-selected-storage-and-consistency-design), [Lifecycle/clocks](docs/22_TRACK_MODE_AND_TELEMETRY.md#83-start-clocks-lifecycle-and-recovery), [Persistence/import](docs/22_TRACK_MODE_AND_TELEMETRY.md#87-persistence-and-detailed-gpx-import) |
| Activity lists/details, paginated history/routes, charts/map/timeline, vector review and GPX/JSON exports | [Completion/reuse](docs/22_TRACK_MODE_AND_TELEMETRY.md#7-completion-review-and-reusable-routes), [Browsing/review/exports](docs/22_TRACK_MODE_AND_TELEMETRY.md#811-activity-browsing-review-and-sharing) |
| Apple Watch, wearable protocol, buffering and platform feasibility | [Watch scope](docs/22_TRACK_MODE_AND_TELEMETRY.md#6-apple-watch), [Protocol/buffering](docs/22_TRACK_MODE_AND_TELEMETRY.md#84-wearable-protocol-and-retention), [Feasibility/probes](docs/22_TRACK_MODE_AND_TELEMETRY.md#85-platform-feasibility-assessment-and-source-probe) |
| Track T0–T7 status, authored checks, verification handoff and remaining acceptance | [Current feature status](docs/22_TRACK_MODE_AND_TELEMETRY.md#9-current-state-and-evidence), [Test coverage](docs/22_TRACK_MODE_AND_TELEMETRY.md#authored-verification-coverage), [Acceptance/remaining work](docs/22_TRACK_MODE_AND_TELEMETRY.md#10-temporary-implementation-plan) |

For tasks spanning multiple areas, read the relevant sections from each affected area. Discover additional documents with targeted filename or text searches when needed. Reuse context already read in the conversation unless it has changed or needs verification. Update affected documentation when behavior, contracts, or implementation status changes.

Keep this task-to-document guide current as part of the same change that adds, renames, moves, splits, or removes project documentation. Also update the affected rows when a document gains a new responsibility or a task area gets its own implementation document. Before completing documentation work, check that each new or changed task area has an accurate entry here and that referenced paths still exist. Do not leave new documents discoverable only through searches or links in other documents.

Maintain one authoritative description of each contract and update it in place when behavior changes. Architecture documents describe boundaries; the module registry describes ownership/dependencies; feature documents describe detailed behavior and limits. Use section links instead of copying feature update paragraphs across documents. Application phase status belongs in `docs/5_IMPLEMENTATION_PLAN.md`; track-mode status and evidence belong in `docs/22_TRACK_MODE_AND_TELEMETRY.md` section 9. Do not append activity change history or retain superseded activity designs; preserve current constraints, pending work and truthful verification evidence.

Documents under `docs/plans/` describe proposals and implementation approaches; they do not establish completion or override the current architecture and contracts. Use the relevant implementation document, `docs/5_IMPLEMENTATION_PLAN.md`, and source code to establish current behavior. Distinguish source implementation, automated verification, and user-reported device acceptance when updating status; do not mark unperformed builds or tests as passed.

## Verification policy

* Project/application builds, APK assembly, framework linking, app launches and every device/emulator/simulator run always belong to the user. Agents must not launch these tasks independently, including device-backed automated suites.
* Agents run only focused automated tests relevant to their change and runnable without a device/emulator/simulator. Compilation required by the selected test task is permitted; separate build/assembly/link tasks remain with the user. For documentation/checker changes, use the Python checks documented in [CONTRIBUTING.md](CONTRIBUTING.md#documentation-maintenance).
* Select the smallest meaningful test scope. Record the exact commands and results, failures or environmental blockers. Do not treat authored tests, syntax checks or historical passing results as verification of current behavior.
* At the end of each task, after focused verification, ask whether the user wants all tests run. Do not start the full suite without that answer. Full automated runs by the agent still exclude device/emulator/simulator tasks; hand those to the user.
* Earlier handoffs assigning automated tests to the user describe the previous policy and do not override this section. Preserve their actual execution evidence without turning unrun checks into passing results.

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
* **UI:** Compose Multiplatform for all UI rendering, with one exception approved by the project owner on 2026-10-12: SwiftUI is permitted only for the Apple Watch companion's watchOS UI. This exception does not extend to phone UI.
* **Map Engine:** MapComposeMP for tile rendering.
* **Network:** Ktor Client with platform-specific engines, specifically OkHttp for Android and Darwin for iOS.
* **Storage:** Room KMP for primary application metadata; custom SQLite driver for `.mbtiles` read/write operations.
* **DI:** Metro DI (`dev.zacsweers.metro`). Use global scopes and avoid manual dependency passing.
* **File IO:** `kotlinx-io-core` for byte stream manipulation and cross-platform file handling.
