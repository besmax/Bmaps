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
2. Write bounded tile batches. Check physical free space for database/journal growth. Bound committed database pages using the effective package allowance and reserve 1 MiB for the manifest. All assets share `min(manifest limit, 300,000,000)` bytes. Temporary SQLite journals need additional free space; they are not payload progress.
3. Require all requested tiles and zero unresolved failures. Persist FINALIZING in Room.
4. Close databases, record asset sizes and each layer's expected tile count, write/synchronize `config.json`, and verify SQLite integrity, exact requested coordinate coverage, counts, asset sizes, and the complete file inventory. PNG/JPEG signatures are screened on writes/reads; full pixel decoding remains the renderer's responsibility.
5. Synchronize package files, atomically rename staging to ready, synchronize the parent directories, then commit READY/COMPLETED together in Room.

A process death before promotion leaves staged work recoverable. A death after promotion but before the Room transaction is repaired by validating the final package and completing its metadata. A valid final orphan can be reindexed. An orphan without a valid manifest and complete tile tables is retained as CORRUPT, never inferred ready from its directory name. Staging with no durable request is retained for removal; it cannot be resumed safely. Missing final directories become MISSING. A corrupt orphan with no readable manifest has unknown (`null`) summary bounds; it is not assigned invented geographic coverage. Interrupted deletion closes owned tile sources, removes both directories, then removes the cascading Room rows.

Reconciliation does not silently delete user package data. Corrupt or missing assets remain visible. An interruption during initial directory/schema creation can leave an unrecoverable initial workspace; remove/recreate that entry rather than claiming it has a resumable tile set. Filesystem and Room failures remain failures even when no space remains to persist a failure row; subsequent reconciliation is the recovery path. Atomic rename and fsync behavior still need native verification.

## Manifest and elevation

Version 1 serialization explicitly writes defaults, including `"elevation": null`. Every newly constructed package currently has no elevation data, and the library summary persists `hasElevationData = false`. The settings dialog states that elevation is not included. A future DEM import/download can populate the existing `PackageAsset` with the `.geotiff` path and size; no empty DEM file is created today.

Final layers carry `tileCount` as completeness evidence in addition to exact zoom selection. Drafts have a null count and cannot be opened. Phase 1 manifests remain serializable, but opening legacy/imported files without this evidence, arbitrary MBTiles layouts, checksums, and externally produced packages requires the Phase 10 importer. A supplied SHA-256 digest is currently rejected rather than accepted without verification.

## Phase 5 integration

Implemented in `12_DOWNLOAD_PIPELINE.md`: validated settings submission, bounded download execution, missing-tile restore, library progress, Android WorkManager foreground notifications, and iOS BGProcessingTask opportunities with foreground lifetime extension. iOS retains the existing Ktor Darwin transport; it does not implement background URLSession transfer continuation. Offline viewing and remaining library management belong to Phase 6.

Configured provider capabilities gate download execution. Storage and runner fixtures make no network requests.

## Verification handoff

Authored but not run:

- `PackageTileCoverageTest`: dateline overlap, exact separated levels, and count overflow.
- `PackageStorageDeviceTest` / `PackageStorageIosTest`: real Room/SQLite/filesystem scenarios for missing-tile recovery, reopening after database recreation, duplicate results, elevation absence, promotion recovery, orphan reindexing, missing directories, source closure on deletion, stable filtered pagination, SQL rollback on size limits, bounded stream cleanup, and path traversal rejection.

User verification should also exercise disk exhaustion, cancellation at each commit boundary, hot-journal recovery after process kill, symlink rejection on both platforms, and concurrent reads/deletion. No new build, host test, simulator, device, or network-download execution was performed for this phase.
