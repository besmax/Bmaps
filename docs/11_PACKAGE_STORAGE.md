# Package storage and recovery

Phase 4 implementation, 2026-09-12. Source review only: builds, schema generation, and all test execution are left to the user. This document records the Phase 4 storage foundation. Phase 5 now submits downloads and adds scheduling, Restore controls, progress, and notifications; current integration status is in `12_DOWNLOAD_PIPELINE.md`.

Android compilation follow-up: replaced the unavailable `OsConstants.O_DIRECTORY` reference with `O_RDONLY` and a descriptor-based `fstat`/`S_ISDIR` check before synchronizing directories. Descriptor closure remains in `finally`. Rebuild and runtime verification remain with the user. These operations use the public [Android Os APIs](https://developer.android.com/reference/android/system/Os) and [OsConstants helpers](https://developer.android.com/reference/android/system/OsConstants).

## Ownership

`core:storage` provides app-private directories, bounded streaming through kotlinx-io, capacity checks, safe relative paths, file synchronization, and atomic promotion. Android uses `filesDir/packages`; iOS uses Application Support. Staging and final directories share a filesystem:

```text
packages/staging/<package-id>/map_data.mbtiles
packages/staging/<package-id>/layers/<layer-id>.mbtiles
packages/staging/<package-id>/config.json
packages/ready/<package-id>/...
```

Only package identifiers become directory names. Display names may contain the colon in `2026-09-11_15:38`. Relative path components reject traversal and both existing and dangling symlinks. Stream copies use 64 KiB chunks and an adjacent `.part` file; failures remove that temporary file, preserving an existing destination. The caller owns the supplied source. File operations run on the IO dispatcher under a storage mutex. SQLite paths are handed only to the domain adapter while it owns the package operation lock; they are not UI paths.

`core:mbtiles` uses the pinned bundled SQLite driver through a custom adapter, with explicit read-only and read/write opens. Each handle serializes reads, transactions, and closure. Transactions accept at most 32 outcomes and 4,000,000 bytes of tile payload; one tile is bounded to 2,000,000 bytes. The repository opens and closes a writer per batch, deliberately favoring simple ownership over throughput until profiling is available. SQLite uses DELETE journals and FULL synchronization. Reads never observe a partial transaction. No connections remain open when a staged directory is promoted.

`core:database` owns Room package/job rows and custom pagination. Its new `app.persistence` convention supplies Room/KSP code generation for Android and both iOS targets, using the central catalog. Existing Room 2.8.4 and SQLite 2.6.2 remain pinned; KSP 2.3.8 is added. Schema version 1 has no predecessor to migrate. Export is configured under `core/database/schemas`; the generated schema must be reviewed and committed after the user's first build. No destructive migration fallback is enabled.

`domain:map-builder` adapts these APIs through one application-scoped `LocalPackageRepository`, bound to `PackageRepository` and `PackageBuildStorage` by the umbrella graph. It owns manifest serialization, lifecycle, completeness checks, sessions, and recovery. No core module imports domain models. The first repository operation or observation reconciles storage once; explicit reconciliation is intended for startup/recovery before scheduling workers, not during active downloads.

## Missing tiles and progress

`tiles` contains only committed successful blobs, keyed uniquely by zoom/column/TMS row. All external keys use XYZ; conversion supports levels 0–63 without signed overflow. Geographic package coverage is separately bounded by the existing Double coordinate precision (0–52), not a zoom-18 cap.

Each layer database also contains `bmaps_missing_tiles`, keyed by zoom/column/XYZ row, with a bounded failure code. Missing responses use `MISSING`; network and other typed tile failures retain their category. A successful write removes the corresponding failure in the same transaction. A later failed duplicate cannot downgrade an already stored tile. Unattempted tiles are implicitly missing in the persisted request's coverage. Exact selected levels are persisted in `BuildLayerRequest.zoomLevels` and `PackageLayer.zoomLevels`; an empty set preserves the original inclusive-range meaning. Dateline selections merge overlapping column intervals at low levels.

Room persists the non-secret request snapshot, job state, unique completed count, unresolved failed count, received bytes, package bytes, and typed terminal failure. `missingTiles = totalTiles - completedTiles` includes both failed and unattempted tiles. Received bytes are advisory telemetry and may undercount a batch if the process dies after the tile commit but before the Room checkpoint; they never determine completeness.

MBTiles is authoritative for successful logical tile identities. Room checkpoints follow the tile transaction. Recovery reopens staged databases for writing so SQLite can recover an interrupted journal, rebuilds counts, and changes interrupted active work to QUEUED. Explicit PAUSED, FAILED, and CANCELLED states are preserved. `request`, `contains`, and `setState` provide the persistence operations for the scheduler to resume by job/package ID and skip committed tiles. Observing progress does not launch network work.

Package queries now include incomplete states by default, allowing the library to present warnings. Ordering is timestamp descending, then ID ascending. Cursors carry the last key and exact filter; using one with another filter is rejected. Search treats `%` and `_` as literal characters. Keyset paging has no duplicate/gap guarantee across a changing dataset; refresh the list when job updates change its ordering.

## Finalization and reconciliation

1. Create the package and job in one Room transaction before allocating files. Duplicate IDs with a different request conflict. An identical persisted request returns its existing job ID.
2. Write bounded tile batches. Check physical free space for database/journal growth. Bound each layer’s committed database pages by `min(sizePolicy.maxLayerBytes, 300,000,000)` bytes. Other layers, elevation, annotations, and metadata do not reduce that allowance. Finalization/opening recheck each MBTiles asset individually. There is no aggregate package-size cap; `config.json` retains a separate 1 MiB format bound. Temporary SQLite journals need additional free space; they are not payload progress.
3. Require all requested tiles and zero unresolved failures. Persist FINALIZING in Room.
4. Close databases, record asset sizes and each layer's expected tile count, write/synchronize `config.json`, and verify SQLite integrity, exact requested coordinate coverage, counts, asset sizes, and the complete file inventory. PNG/JPEG signatures are screened on writes/reads; full pixel decoding remains the renderer's responsibility.
5. Synchronize package files, atomically rename staging to ready, synchronize the parent directories, then commit READY/COMPLETED together in Room.

A process death before promotion leaves staged work recoverable. A death after promotion but before the Room transaction is repaired by validating the final package and completing its metadata. A valid final orphan can be reindexed. An orphan without a valid manifest and complete tile tables is retained as CORRUPT, never inferred ready from its directory name. Staging with no durable request is retained for removal; it cannot be resumed safely. Missing final directories become MISSING. A corrupt orphan with no readable manifest has unknown (`null`) summary bounds; it is not assigned invented geographic coverage. Interrupted deletion closes owned tile sources, removes both directories, then removes the cascading Room rows.

Reconciliation does not silently delete user package data. Corrupt or missing assets remain visible. An interruption during initial directory/schema creation can leave an unrecoverable initial workspace; remove/recreate that entry rather than claiming it has a resumable tile set. Filesystem and Room failures remain failures even when no space remains to persist a failure row; subsequent reconciliation is the recovery path. Atomic rename and fsync behavior still need native verification.

## Manifest and elevation

Version 1 serialization explicitly writes defaults. None retains `"elevation": null`; selected OpenTopography downloads persist `elevationDataset`, stream `elevation.geotiff` into staging, and record its `PackageAsset` and `hasElevationData = true` only after file commit. Requested elevation is required for finalization and checked on package opening. TIFF header screening remains the ingestion gate. Phase 9 now opens supported GeoTIFFs lazily through package-owned DEM readers and closes them before deletion; see `17_ELEVATION_READER.md` for the bounded sampling contract. See `16_ELEVATION_DOWNLOADS.md` for interruption recovery, size limits, and verification.

Final layers carry `tileCount` as completeness evidence in addition to exact zoom selection. Drafts have a null count and cannot be opened. Phase 1 manifests remain serializable. Phase 10 verifies optional SHA-256 digests and imports framed snapshots or the documented complete raster MBTiles subset. Legacy directories without completeness evidence and arbitrary formats remain unsupported. Imports use a fresh identity, staging verification, promotion, and orphan recovery; see `19_PACKAGE_TRANSFER.md`.

## Phase 5 integration

Implemented in `12_DOWNLOAD_PIPELINE.md`: validated settings submission, bounded download execution, missing-tile restore, library progress, Android WorkManager foreground notifications, and iOS BGProcessingTask opportunities with foreground lifetime extension. iOS retains the existing Ktor Darwin transport; it does not implement background URLSession transfer continuation. Offline viewing and remaining library management belong to Phase 6.

Configured provider capabilities gate download execution. Storage and runner fixtures make no network requests.

## Verification handoff

Authored but not run:

- `PackageTileCoverageTest`: dateline overlap, exact separated levels, and count overflow.
- `PackageStorageDeviceTest` / `PackageStorageIosTest`: real Room/SQLite/filesystem scenarios for missing-tile recovery, reopening after database recreation, duplicate results, elevation absence, promotion recovery, orphan reindexing, missing directories, source closure on deletion, stable filtered pagination, SQL rollback on size limits, bounded stream cleanup, and path traversal rejection.

User verification should also exercise disk exhaustion, cancellation at each commit boundary, hot-journal recovery after process kill, symlink rejection on both platforms, and concurrent reads/deletion. No new build, host test, simulator, device, or network-download execution was performed for this phase.


## Phase 6 catalog extension

Room schema version 2 adds device-local package favourite/avatar preferences with an explicit migration from version 1. Existing package manifests and download checkpoints are unchanged. Generate and review the version 2 schema during the next user-run build. Library deletion now invokes the scheduler/runner cancellation path before repository cleanup when a download job exists. Details are in `13_LIBRARY_AND_OFFLINE_VIEWER.md`.

## Phase 7 layer settings

Ready packages support atomic `config.json` replacement through `PackageRepository.setLayerPresentation`. Only visibility, opacity, render order, and the modification timestamp change; tile files and layer IDs remain stable. The file is authoritative and Room metadata follows. Reconciliation removes interrupted `.part` writes before validating the inventory and restores Room metadata from the committed manifest. Package payload limits account for replacing the previous manifest; physical free-space checks still cover the temporary write. A layer completion query uses MBTiles counts to gate subsequent workers without rescanning every tile. See `14_RASTER_LAYER_COMPOSITION.md`.

## Phase 8 annotation storage

`core:storage` now opens package-local `annotations.db` through bundled SQLite, with owner verification, explicit schema versioning, atomic batches, bounded serialized rows, and indexed viewport-envelope queries. `LocalPackageRepository` shares its existing operation lock across annotation operations and package deletion. Initial database creation uses a temporary asset; subsequent SQLite commits precede recoverable manifest/catalog updates. See `15_ANNOTATIONS_AND_GEOJSON.md` for schema, query, and interruption details.

## Generated elevation assets — 2026-10-09

Ready packages can add/replace a generated elevation MBTiles through a repository-owned commit. Private durable jobs/provisional output live outside the package in `elevation-jobs`; completed files move into immutable versioned layer paths before an atomic manifest switch. Open old tile sources retain their file until release; subsequent access/startup removes unreferenced generated versions. Inventory checks permit only known source-retained old versions, while export includes current manifest assets. Interrupted pre-manifest candidates are cleaned up. Package deletion also removes generation job files.

The new layer obeys the effective per-layer size limit and physical-capacity checks. Catalog map size and historical job `packageBytes` use actual directory size, including generated data/manifest and any briefly retained reader version. Reopening after cleanup refreshes size. Historical downloaded tile totals are reconciled separately from generated coverage. See `21_ELEVATION_COLOR_LAYERS.md`; source implementation and added test scenarios are not build/runtime verified.

Generation performance changes (2026-10-09): provisional elevation tiles use the existing 32-tile/4 MB MBTiles transaction limits with FULL synchronization. Committed counts are authoritative on recovery; progress JSON is no longer synchronized after every tile. Private `elevation-jobs/<id>/range.json` caches DEM extrema by actual content SHA-256 and version, survives tile cleanup/regeneration, and is removed on package/job deletion. It is excluded from committed map inventory/size and transfer; generated MBTiles remains included in actual map size. Implementation and static review only; runtime/size/performance acceptance remains pending. See `21_ELEVATION_COLOR_LAYERS.md`.

DEM tile-envelope requests (2026-10-09): production `finishElevation(id, expectedBounds)` opens the provisional DEM, validates its native metadata footprint against complete tile coverage, closes it and only then synchronizes/renames/checkpoints. A mismatch leaves the committed DEM absent and the worker discards the partial asset; downloaded tiles are retained. Larger padded DEM files enter existing actual package/catalog/job byte accounting. Existing/imported inventories retain their prior checks. Source and static review only; new native-backed coverage scenario is not executed. See `16_ELEVATION_DOWNLOADS.md`.

Library size repair (2026-10-09): summary observations remeasure actual ready/staging directory file lengths and repair stale package/job byte totals atomically, using current records under repository/file locks. This includes all layers, DEM, annotations and configuration rather than trusting an old catalog total. Timestamp/order are preserved, unchanged totals cause no write, and missing/unreadable directories keep the existing recorded value/status. External generation/cache storage stays excluded. Library resume restarts observation for a fresh measurement. Existing storage scenarios were extended in source; no build/runtime execution. See `13_LIBRARY_AND_OFFLINE_VIEWER.md`.
