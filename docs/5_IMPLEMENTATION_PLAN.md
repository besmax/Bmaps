# Bmaps Implementation Plan

Planning baseline: 2026-09-08. Status: Phase 1 contracts implemented and verified. Phase 0's framework ownership prerequisite is complete; its remaining work is still pending.

This plan follows `AGENTS.md` and documents 1–4. Checked boxes represent completed deliverables. Estimates are intentionally omitted until the platform integration spikes establish effort and supported formats. The table below preserves the original source-inspection baseline; current Phase 1 decisions are recorded in `6_CONTRACTS_AND_PACKAGE_FORMAT.md`.

## 1. Verified starting point

Repository inspection confirms:

| Area | Current state | Required work |
| --- | --- | --- |
| Native applications | Android and iOS entry points exist | Replace template UI with feature composition |
| `:shared` | Template Compose screen and greeting helpers; direct network, IO, and renderer dependencies | Restrict to DI assembly, root composition, and platform entry points |
| Core | Seven registered modules with build files and no implementation sources | Implement infrastructure incrementally |
| Features and domain | Listed in documentation but absent from settings and source tree | Create the five documented modules as their phases begin |
| Build logic | KMP, Android library, Compose, and Metro conventions exist | Correct framework ownership and complete application/persistence conventions |
| iOS framework | Base KMP convention configures a static `Shared` framework for every consumer | Generate the framework exclusively for `:shared` |
| Android application | Configuration remains in its module build file | Move reusable configuration into build logic |
| Persistence | Room runtime and SQLite dependencies declared | Add code generation, schemas, database construction, and migrations |
| Tests and automation | No application test sources or CI configuration found in tracked-file inspection | Establish checks alongside implemented behavior |

This is a source inspection baseline. Builds, dependency resolution, and device execution have not been verified as part of preparing the plan. Catalog versions are existing project choices, not a compatibility endorsement.

## 2. Scope and delivery milestones

The first usable slice is intentionally smaller than the full documented MVP. It does not redefine or remove the remaining requirements.

| Milestone | User-visible outcome | Phases |
| --- | --- | --- |
| M0: Foundation | Both apps launch the feature shell; framework and DI boundaries are correct | 0–2 |
| M1: Offline vertical slice | Select provider and area, download a small package, find it in the library, reopen it offline | 3–6 |
| M2: Map editing | Compose aligned raster layers and persist markers, routes, and polygons | 7–8 |
| M3: Complete product scope | Read supported elevation files, use verified coordinate transformations, import/export and share packages | 9–10 |
| M4: Release candidate | Recovery, performance, accessibility, and platform checks pass | 11 |

Vector tile rendering, professional grid UI, BLE transport, and local Wi-Fi transport remain future extensions. Their contracts should remain possible without implementing those features now. Routes mean user-drawn geometry; automatic routing is not specified. Live GPS tracking is not specified.

## 3. Decisions to settle before dependent implementation

Record decisions in the relevant existing architecture/product document. These are targeted design tasks, not reasons to block unrelated work.

| Decision | Proposed direction or required evidence | Deadline |
| --- | --- | --- |
| Repository implementation ownership | Keep domain contracts and their orchestration/adapters in domain modules where they depend on core APIs; core must never import domain. Bind implementations through Metro in `:shared`. | Phase 1 |
| Missing domain responsibilities | Initially group package lifecycle, library, annotation, elevation, and transfer contracts under `domain:map-builder`, explicitly expanding its registry entry. If this becomes incoherent, propose focused domain modules and update the registry before creating them. | Phase 1 |
| Geometry ownership | Keep reusable geospatial primitives and transformations in `core:map-engine`, following the registry; expose renderer-independent APIs. Domain must not use MapComposeMP types. Domain plans downloads using these primitives. | Phase 1 |
| Multiple raster layers | Proposed package layout: retain `map_data.mbtiles` for the base layer and add `layers/<layer-id>.mbtiles`, referenced by `config.json`. Resolve the current single-file example before multi-layer writes. | Phase 1 |
| Package identity | Use stable IDs and safe storage directory names; treat the user-visible name as metadata. Version the package manifest from the first package. | Phase 1 |
| Provider availability | Choose actual providers after verifying current download rights, attribution, authentication, caching, and request limits from their official documentation. The OSM example is not authorization to bulk-download any particular endpoint. | Phase 3 |
| Download lifecycle | First slice: foreground execution with durable checkpoints. Define behavior on suspension and termination; automatic background completion requires a separate platform feasibility decision. | Phase 5 |
| CRS support | Specify the exact SK-91 definition, applicable region, parameters/grids, reference fixtures, and accuracy target before implementing its transformation. A name alone is insufficient. | Phase 9 |
| DEM support | Define accepted TIFF/GeoTIFF encodings, compression, CRS, no-data rules, and size limits after a bounded reader spike on both platforms. | Phase 9 |
| Transfer format | Proposed versioned archive of a consistent package snapshot, with a manifest and integrity metadata. Define standalone MBTiles and GeoJSON import behavior separately. | Phase 10 |

Presentation owns user-input validation, invoked separately before structural use cases. Storage and network boundaries must still reject corrupt files, unsupported formats, and failed IO operations; those checks are not form validation.

## 4. Detailed implementation sequence

### Phase 0 — Correct and verify the build foundation

Dependencies: none.

- [x] Remove framework creation from `app.kmp.library`; retain platform targets there and configure framework generation exclusively for `:shared` through umbrella-specific build configuration.
- [ ] Add an Android application convention and move reusable Android application settings into build logic.
- [ ] Add catalog entries and convention support for the code-generation, serialization, coroutines, and test dependencies actually required by subsequent phases. Verify compatibility before selecting additions.
- [ ] Configure Room generation and schema export through a persistence convention when introducing the first database; do not apply Room tooling to unrelated modules.
- [ ] Verify Metro compilation for Android and iOS with a minimal binding and graph.
- [ ] Discover and document actual Gradle tasks for common/host tests, Android assembly, and iOS framework linking. Establish CI with an appropriate macOS job for iOS.
- [ ] Correct the duplicated persistence heading in document 2, resolve its broad MVI wording in favor of document 4's MVVM+ rule, and update the template README to describe the umbrella architecture.

Acceptance: Android debug assembly and iOS simulator framework linking pass; both native apps launch; only `:shared` produces the application framework. Record commands and environment prerequisites. Do not upgrade the stack merely because newer versions exist.

### Phase 1 — Define package and application contracts

Dependencies: Phase 0. Executed with the required framework-convention correction; remaining Phase 0 work is not marked complete. Details and format decisions: `6_CONTRACTS_AND_PACKAGE_FORMAT.md`.

- [x] Register and scaffold `domain:providers` and `domain:map-builder` using convention plugins.
- [x] Update document 3 with the decided ownership of package lifecycle and other domain responsibilities before implementation.
- [x] Define provider identity, supported zoom range, tile addressing, content format, attribution, and capability models.
- [x] Define package ID, bounds, zoom range, layer descriptors, manifest version, asset references, timestamps, and package lifecycle states.
- [x] Define renderer-independent coordinates, bounds, tile keys, coordinate-system identifiers, and transformation contracts in the documented core owner.
- [x] Define contracts for provider lookup, download planning/execution, package observation/open/delete, and tile reading.
- [x] Define failure categories with explicit cancellation behavior and immutable progress snapshots.
- [x] Specify package compatibility rules, incomplete-package visibility, and reconciliation between local files and central metadata.
- [x] Reserve a tile-content discriminator for future vector support; implement raster only.

Acceptance: module dependencies obey the hierarchy; the versioned manifest has representative fixtures; contracts can support a fake online source and a fake local source without exposing renderer types. No speculative generic framework is introduced.

Verification: 10 Android host tests passed; domain contracts compiled for iOS device and simulator; Android debug assembly and shared iOS simulator framework linking passed. Initial 300,000,000-byte total-package policy, PNG/JPEG support, and extensible provider/style configuration are defined. Actual size enforcement, HTTP, and rendering remain in their implementation phases. Native apps were not launched during Phase 1.

### Phase 2 — Build the feature shell and DI composition

Dependencies: Phase 1.

- [ ] Scaffold `feature:constructor`, `feature:library`, and `feature:viewer` and register them in settings.
- [ ] Replace template content with root navigation composed in `:shared`; keep screen behavior inside features.
- [ ] Define global Metro scopes in `core:di`, assemble the graph in `:shared`, and bind platform services through platform entry points.
- [ ] Provide lifecycle-aware ViewModel creation, including dialog-scoped ViewModels for heavy dialogs; avoid manual service passing.
- [ ] Establish one immutable `StateFlow` state per ViewModel and a `Channel` exposed as `Flow` for one-off events. No reducers, stores, or middleware.
- [ ] Implement preference persistence in `core:datastore` for theme and default coordinate system as those settings become usable.
- [ ] Remove greeting/template resources and relocate direct infrastructure dependencies out of `:shared` unless required for composition.

Acceptance: navigation between feature placeholders works on both platforms without feature-to-feature dependencies; rotation/recreation and iOS lifecycle transitions do not duplicate one-off actions.

### Phase 3 — Render the first online map

Dependencies: Phases 1–2.

- [ ] Implement Ktor configuration in `core:network` with Android OkHttp and iOS Darwin engines, bounded requests, cancellation, timeouts, and typed failures.
- [ ] Implement the provider registry and approved provider configuration through `domain:providers`; keep provider policy out of generic HTTP infrastructure.
- [ ] Build the MapComposeMP wrapper in `core:map-engine` with renderer-independent viewport, tile-source, layer, and interaction APIs.
- [ ] Render one raster source in the constructor with provider selection and visible attribution.
- [ ] Implement coordinate/tile conversion and explicit behavior at projection limits and the antimeridian; avoid silently clamping unsupported selections.
- [ ] Verify renderer tile loading, resource cleanup, and cancellation on Android and iOS before building download orchestration.

Acceptance: both apps pan and zoom a real approved provider; deterministic fixture tests cover coordinate/tile conversion; network failures and missing tiles have recoverable UI states. Use fake responses for routine automated tests.

### Phase 4 — Implement package storage and local tile access

Dependencies: Phase 1; can proceed independently of online UI after contracts stabilize.

- [ ] Implement app-private directories, streaming file IO, temporary workspaces, finalization, and cleanup in `core:storage` using `kotlinx-io-core` with platform adapters where necessary.
- [ ] Implement custom MBTiles read/write access in `core:mbtiles`: metadata, tile blobs, XYZ/TMS row conversion, transactions, and explicit handle ownership.
- [ ] Define serialized writes and safe reads during package construction, with bounded batches and resource cleanup.
- [ ] Implement the central Room database for package metadata and durable job/checkpoint information; exclude annotation geometry.
- [ ] Implement stable custom pagination and filtering directly in `core:database`, with deterministic ordering and tie-breakers.
- [ ] Implement manifest serialization and reopening; define a finalize/reconcile protocol because filesystem and metadata writes are not one atomic transaction.
- [ ] Handle disk exhaustion, interrupted writes, missing assets, duplicate IDs, and stale metadata without exposing partial packages as ready.

Acceptance: a fixture package survives write-close-reopen and returns expected tile bytes; pagination has no duplicates or gaps in a stable dataset; failure fixtures demonstrate cleanup and recovery. Verify SQLite behavior on both platforms.

### Phase 5 — Build the offline constructor and durable download pipeline

Dependencies: Phases 3–4.

- [ ] Add map-name input, bounding-box selection, zoom controls, tile count, estimated size, and destination capacity feedback.
- [ ] Invoke presentation validation independently for name, bounds, zoom range, and provider constraints before submitting a request.
- [ ] Implement tile enumeration/counting in `domain:map-builder` using the shared geospatial primitives; iterate large plans without materializing every tile in memory.
- [ ] Implement bounded download concurrency, provider-specific limits, retry/backoff, and separate handling of unavailable tiles versus transient failures.
- [ ] Persist job parameters, completed work, counters, and status transitions so resume does not restart completed work.
- [ ] Implement start, pause, resume, and cancel behavior with explicit retention/cleanup policy for partial packages.
- [ ] Finalize assets and manifest, then reconcile/update library metadata using the Phase 4 protocol.
- [ ] Define user-facing completeness rules: do not label a package complete when required tiles failed silently.

Acceptance: download a small known region, interrupt connectivity and process execution, resume without duplicate logical tiles, and finalize a readable package. Cancellation closes resources. Capacity and provider errors remain actionable.

### Phase 6 — Deliver the library and offline viewer

Dependencies: Phases 2, 4–5.

- [ ] Implement package list, details, size/status display, filters, and incremental loading using the custom database queries.
- [ ] Open packages through domain operations and adapt local MBTiles sources to the renderer wrapper.
- [ ] Display geographic bounds, supported zooms, loading/empty states, and missing/corrupt package errors.
- [ ] Implement package deletion with handle closure, file cleanup, metadata reconciliation, and appropriate UI confirmation.
- [ ] Preserve viewport across normal screen recreation and restore relevant preferences.
- [ ] Verify offline viewing makes no tile-network requests and does not depend on a warm HTTP cache.

Acceptance for M1: create a package online, terminate the app, disable connectivity, relaunch, find it in the library, and pan/zoom all downloaded levels on both platforms. Delete it and verify files and metadata agree.

### Phase 7 — Add raster layer composition

Dependencies: Phase 6 and the package-layout decision.

- [ ] Implement multiple raster asset references with stable layer IDs, ordering, visibility, attribution, and independent opacity.
- [ ] Validate identical geographic bounding boxes in the presentation flow; define compatible CRS and zoom behavior and reject unsupported combinations clearly.
- [ ] Extend constructor downloads and package manifests for the agreed multi-layer layout.
- [ ] Add constructor/viewer layer controls without coupling the two feature modules.
- [ ] Persist layer configuration and restore it when reopening a package.

Acceptance: two aligned raster layers render in the expected order; each opacity control operates independently; persisted settings survive reopening; unsupported alignment is reported before composition.

### Phase 8 — Implement annotations and GeoJSON

Dependencies: Phase 6; can proceed independently of Phase 7.

- [ ] Define annotation IDs, point/line/polygon geometry, properties, and domain operations in the agreed domain owner.
- [ ] Implement per-package `annotations.db` access in `core:storage`; choose and document SQLite integration without placing annotations in the central Room database.
- [ ] Define schema versions, transactions, migration behavior, and viewport bounding-box filtering, including geographic edge cases.
- [ ] Implement create/edit/delete flows for markers, routes, and polygons; invoke geometry input validation in presentation.
- [ ] Use dedicated presentation layer classes such as `MarkersLayer`, `RouteLayer`, and `PolygonLayer` for visibility, selection, and popup state.
- [ ] Implement GeoJSON serialization/import with explicit coordinate and property mapping and a documented supported geometry subset.

Acceptance: annotations survive restart and remain isolated between packages; viewport queries return the expected objects; GeoJSON round trips preserve supported geometry and properties; editing does not overload the main screen ViewModel.

### Phase 9 — Implement coordinate-system and elevation support

Dependencies: Phases 3–4 and 6; resolve CRS/DEM decisions before implementation.

- [ ] Run bounded feasibility spikes for SK-91 transformations and TIFF/GeoTIFF reading on both platforms using independently sourced reference fixtures.
- [ ] Document supported CRS definitions, transformation accuracy, DEM encodings, and rejected variants in the product specification.
- [ ] Implement transformation interfaces in `core:map-engine`; keep storage coordinates distinct from user-selected display coordinates.
- [ ] Add coordinate-system selection and formatted coordinate display through presentation and preferences.
- [ ] Implement DEM asset ingestion and bounded-memory parsing/sampling in `core:storage`.
- [ ] Implement geographic-to-raster lookup, no-data handling, interpolation policy, altitude units, and elevation display/overlays in the viewer.
- [ ] Handle missing or unsupported DEM/CRS data explicitly without guessing transformations or showing misleading altitude.

Acceptance: independent reference points meet the documented transformation tolerance; known raster samples yield expected elevation; large fixtures do not require loading the entire DEM; supported files work fully offline on both platforms.

### Phase 10 — Implement import, export, and native sharing

Dependencies: Phases 7–9 for complete-package support; transport contracts can be drafted in Phase 1.

- [ ] Define domain package-transfer contracts independent of native share sheets and future transport implementations.
- [ ] Implement versioned streaming export of a consistent package snapshot, including all layers, annotations, configuration, elevation, and auxiliary assets.
- [ ] Ensure database snapshots include committed data even when the package was recently edited; avoid copying an inconsistent live database.
- [ ] Implement import into a staging directory with manifest/version checks, asset integrity checks, safe path handling, capacity limits, and collision handling.
- [ ] Finalize imports and register metadata only after successful verification; recover interrupted import/export and clean temporary files.
- [ ] Add native picker/share adapters for Android and iOS, including temporary access grants and cleanup appropriate to each platform.
- [ ] Implement the agreed standalone MBTiles/GeoJSON import flows and unsupported-format messages.

Acceptance: export on Android and import on iOS, and vice versa; verify tiles, layer settings, annotations, and DEM results. Cancellation or malformed archives leave no apparently complete package behind.

### Phase 11 — Harden and prepare a release candidate

Dependencies: Phases 0–10; perform focused verification during each phase, not only here.

- [ ] Exercise process death, platform suspension, low storage, network loss, repeated import/delete, unsupported schema versions, and partially damaged packages.
- [ ] Measure tile latency, pan/zoom responsiveness, memory, download throughput, large-library queries, and annotation viewport queries using documented device/fixture profiles.
- [ ] Set measurable budgets from the first representative device baseline and verify later changes against them.
- [ ] Inspect Compose compiler stability metrics and correct unstable presentation models where measurements show avoidable recomposition.
- [ ] Verify accessibility labels, touch targets, contrast, text scaling, and readable validation/recovery messages.
- [ ] Review provider attribution, application permissions, backup behavior, logging, and credentials against the offline-first/privacy product requirements.
- [ ] Run migration fixtures, deterministic integration tests, and end-to-end smoke flows on Android and iOS; document physical-device checks beyond simulator coverage.
- [ ] Update README, module registry, package-format documentation, supported format/CRS tables, and known limitations to match implementation.

Acceptance for M4: every documented MVP capability has a reproducible acceptance flow on both platforms; known limitations are explicit; critical recovery and data-integrity failures are resolved.

## 5. Dependency and implementation rules

The critical path is foundation → contracts → online rendering/local storage → download pipeline → library/offline viewer. Layer composition and annotations can follow independently. Elevation and CRS feasibility should be investigated early after the foundation because their results affect supported formats. Full package-sharing acceptance follows the final package contents.

Apply these constraints to every work item:

- `:shared` assembles and exposes; it contains no business logic or screen state management.
- Features never depend on features. Core never depends on domain. Minimize and document necessary core-to-core dependencies.
- Core APIs describe infrastructure capabilities; domain adapters translate them to business contracts where needed.
- Use convention plugins and catalog dependencies for every new module and tool integration.
- Keep presentation validation separate from structural use cases.
- Keep annotation data local to each package and pagination/filtering in `core:database`.
- Use global Metro scopes for services while respecting ViewModel, dialog, and file-handle lifetimes.
- Keep all comments and documentation in English; add comments only for non-obvious reasons.

## 6. Verification strategy and completion tracking

| Level | Evidence to maintain |
| --- | --- |
| Pure logic | Tile math, boundary cases, download planning, manifest compatibility, and coordinate reference fixtures |
| Persistence | MBTiles round trips, migrations, annotation isolation, stable pagination, finalization/reconciliation, interrupted IO |
| Network/orchestration | Deterministic fake-provider tests for retries, rate limits, cancellation, checkpoints, and incomplete downloads |
| Presentation | Validation, immutable state transitions, one-off events, and lifecycle-bound dialogs for implemented interactions |
| Platform | SQLite/file/share adapters, DI compilation, native launch, and Android/iOS offline smoke flows |
| Performance | Representative large packages, DEMs, annotation sets, and measured device baselines |

Split each phase into reviewable changes around coherent behavior. Each implementation change records its affected modules, user-visible outcome, verification performed, and remaining limitations. Do not add tests that merely mirror boilerplate or reversible documentation edits.

A phase is complete only when its deliverables and acceptance criteria pass and the documentation reflects the result. Track deviations with rationale rather than silently narrowing the MVP. Following Phase 1, finish the remaining Phase 0 foundation work before feature-shell and renderer integration.
