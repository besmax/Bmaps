# Download pipeline and current state

Phase 5 implementation, 2026-09-13. Source review only: no builds, test execution, simulator/device runs, or live tile downloads were performed. Compilation and acceptance remain assigned to the user. Phase 6 offline viewing and library management are now implemented, with verification pending; see `13_LIBRARY_AND_OFFLINE_VIEWER.md`.

## User flow

After choosing an area, the settings dialog accepts a map name and exact zoom levels and shows tile count, advisory decimal MB, available storage, and an optional elevation dataset. The local-time default name is `yyyy-MM-dd_HH:mm`. Presentation validates the name, provider coverage, projection, and zoom limits independently before submission. Estimates use the same coverage calculation as execution. The dialog immediately shows a preliminary average-cost estimate, then refines it asynchronously from spatial tile samples for each source/style and zoom. Submission uses the latest displayed estimate and requires free space of at least twice the combined estimate (all layers plus elevation) for working files. The 300,000,000-byte raster-layer limit is temporarily disabled for device testing; storage continues checking physical capacity. Estimates are advisory, not measured final download sizes.

Notification permission is requested at submission where needed. Denial does not prevent downloading or library progress. Successful submission returns to the library. Active downloads appear above the map list with durable completed/total counters and pause/cancel controls. Incomplete entries show warnings, missing tile counts, actionable failure guidance, and Restore. Cancel in this UI retains partial work. List loading uses the database's keyset pagination and can continue beyond 200 maps.

Tile-only manifests explicitly contain `elevation: null`. Optional OpenTopography GeoTIFF downloading, secure key entry/replacement, and required-asset completion are implemented in `16_ELEVATION_DOWNLOADS.md`. Ready packages are listed, but tapping into an offline viewer and the remaining library details/filter/deletion UI belong to Phase 6.

## Execution and recovery

`TileDownloadPlanner` streams tile keys rather than allocating a full plan. `DurableDownloadExecutor` owns start/pause/cancel/resume commands, persists the request before scheduling, and uses stable package IDs for idempotent submission. `DownloadRunner` has four concurrent tile operations globally, bounded per-job queues, and reuses the provider adapter's request pacing, concurrency limits, and bounded retry/backoff behavior. Native schedulers receive only durable job identity, not credentials.

Production downloads check raster headers/dimensions before committing. Full pixel decoding remains the renderer's responsibility. Successful logical tile identities are authoritative in MBTiles; Room carries durable job state and progress. A missing response records a failed tile and continues; an exhausted typed network/provider failure checkpoints the failure and stops the job. Unattempted tiles remain implicitly missing. Restore enumerates the stored coverage and skips committed tiles, downloading only what is missing. Required missing tiles prevent finalization.

Fresh execution checks the configured provider download capability and source configuration. Restore rechecks these before fetching more tiles. A complete staged tile set skips tile sources; a requested DEM must also be committed before finalization. A complete set of all requested assets can retry finalization without network access. Provider capability configuration is an application gate, not evidence of external download entitlement; the existing local provider configuration changes are preserved.

Manual pause, failure, and retained cancellation survive restart. Interrupted queued/running/finalizing work reconciles to queued work; a promoted complete package is reindexed instead. Cancellation joins runner cleanup and closes sources. Finalization uses the Phase 4 durable promotion protocol and is retryable across the promotion/metadata boundary. No package schema version change is introduced by Phase 5.

## Android

`AndroidDownloadScheduler` uses unique WorkManager work per package with a connected-network constraint. The injected worker factory is configured through the application entry point. `MapDownloadWorker` supplies foreground `dataSync` information, a low-importance notification channel, throttled progress updates, an application content intent, and a Pause action. The manifest declares the foreground service and notification permissions and disables automatic WorkManager initialization in favor of the injected configuration.

On API 31+, system interruption requeues retained work while explicit cancellation pauses it. Older Android versions conservatively pause when the stop reason cannot be distinguished; Restore remains available. Foreground work is still subject to platform restrictions and quotas. Verify permission denial, network constraint changes, process death, and foreground-service startup on supported target versions. See [Android long-running workers](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running).

## iOS

`IosDownloadScheduler` registers `bes.max.bmaps.downloads` during application launch, before launch completion, on the main queue. The application declares background processing and the permitted task identifier. Foreground downloads use a UIKit background lifetime extension; BGProcessingTask supplies later OS-scheduled processing opportunities with a network requirement. Both execute the existing Ktor Darwin transport and shared runner.

Expiration cancels and joins active work, preserves checkpoints, and requests another opportunity. If scheduling is unavailable, retained work is paused for manual restore. Foreground activation resumes queued jobs. Background completion/failure uses localized local notifications when permitted; the library supplies live progress. There is no continuous Android-style progress notification.

This implementation does not use background URLSession transfers and does not promise ongoing transfer while suspended, immediate background execution, or continuation after user force quit. Background timing belongs to iOS. Validate expiration and relaunch on physical devices; simulator behavior alone cannot establish these guarantees. See [Apple BGProcessingTask network requirements](https://developer.apple.com/documentation/backgroundtasks/bgprocessingtaskrequest/requiresnetworkconnectivity).

## Verification handoff

Authored but not run: `DownloadRunnerTest` covers missing-only restore, cancellation/source closure, network failure, revoked download permission, finalization without provider access, stale paused work, and agreement between streamed planning and estimates. `DownloadValidationTest` covers presentation constraints; existing settings and storage scenarios were adjusted for the name format and preservation of failed jobs.

User acceptance checklist:

1. Build Android and iOS, review generated Room schema, and run the authored common/native scenarios.
2. With a configured authorized source, select a small area and separated zoom levels; verify name, estimate, capacity feedback, and no-elevation labels.
3. Observe library and Android notification progress; deny notification permission and confirm in-app progress still works.
4. Interrupt connectivity, pause, cancel while retaining, and restore. Verify committed tile count never duplicates and only missing tiles are requested again.
5. Kill/relaunch during tile commit and finalization; verify explicit pauses/failures remain actionable and complete promoted packages recover correctly.
6. Exercise iOS expiration/background opportunities and Android constraints/quota interruption on physical devices.
7. Exercise insufficient storage, missing tiles, invalid credentials, provider capability changes, and raster dimension failures. Partial packages must never appear ready.
8. Verify pagination with more than 200 packages. Offline viewer acceptance follows in Phase 6.

Static whitespace validation is recorded separately from runtime acceptance; no successful compilation or test result is claimed for this phase.

## Phase 7 layer execution

The constructor can add provider/style layers to the selected area. Every layer uses the same selected zoom levels, with presentation validation against each provider’s coverage and tile matrix. Combined estimates include all layers, including hidden layers. The historical 300 MB per-layer limit is temporarily disabled. `estimatedLargestLayerBytes` remains available in the estimate contract; `estimatedPackageBytes` drives combined size display and free-space checks. The root writes to `map_data.mbtiles`; each additional layer writes to `layers/<layer-id>.mbtiles`. Initial visibility and opacity are persisted in the durable request and manifest.

Android schedules a unique sequential WorkManager chain with one worker per layer, carrying the package job identity and durable layer index. Additional workers execute only after successful predecessor completion. iOS executes the same layer-scoped runner sequentially within its foreground/background processing opportunity. A runner checks that predecessors are complete before opening the next source. Missing root tiles stop the chain. Each layer closes its source and persists QUEUED at the boundary; only completion of the entire package triggers finalization and READY. Restore skips completed layers without reopening their providers and skips committed tiles within incomplete layers. No Room schema change is needed: layer requests and manifests are already serialized durably. User-run validation remains pending.

## Local elevation generation scheduling — 2026-10-09

Elevation color generation is a separate local job, not an extra network download stage or a change to the package's READY status. It uses the shared generator, Android `ElevationGenerationWorker` in the existing worker factory, and a distinct iOS BGProcessingTask identifier with no network requirement. Private durable options/progress and provisional MBTiles support interrupted execution. Native application startup initializes the new scheduler alongside download scheduling. The original download's tile totals remain unchanged; actual package byte totals grow when the generated asset commits. See `21_ELEVATION_COLOR_LAYERS.md` for behavior, lifecycle limits, and pending verification.

Elevation performance changes (2026-10-09): generation uses one DEM producer, two color/PNG workers, bounded channels and one batch writer. StateFlow replaces progress-file polling; durable checkpoints are periodic with immediate state transitions. Native grids/block caches and a content-verified range cache reduce repeated DEM work. No network pipeline concurrency, package READY state, or historical download total changes are introduced. Aggregate stage diagnostics and pending performance acceptance are described in `21_ELEVATION_COLOR_LAYERS.md`; builds and device tests remain with the user.

## Background size estimates and temporary limit removal — 2026-10-10

`PackageSizePolicy.ENFORCE_LAYER_SIZE_LIMIT = false` bypasses the effective raster-layer size ceiling, including limits recorded in existing manifests/jobs. The serialized `maxLayerBytes` contract and historical 300 MB constant are retained so the policy can be restored. Downloads, finalization, opening, generated layers, and standalone MBTiles normalization use the same effective policy. Format bounds, bounded tile/batch buffers, provider permissions, coordinate support, and physical-capacity checks remain enforced. Transfer archives retain their separate 32 GB payload limit.

The settings ViewModel owns a cancellable, dialog-scoped background estimate and an `estimating` flag. A 350 ms debounce avoids sampling every slider movement. The spinner and localized “Calculating size…” text appear alongside the estimate; settings and confirmation remain available. Changing levels/layers/elevation cancels the old calculation, and confirmation snapshots the current estimate for submission. Invalid composition/download capability prevents sampling. Size uncertainty no longer rejects an area against 300 MB; unavailable/overflowed estimates and insufficient free space still prevent submission.

`SampledDownloadEstimator` samples up to nine distinct spatial positions per zoom/coverage rectangle (up to eighteen across the dateline), without enumerating the tile matrix. Each layer prioritizes zooms with the largest tile counts. Requests share the existing provider transport, credentials, pacing and validation; estimator concurrency is two, each read has a three-second deadline, and the total refinement deadline is twenty seconds. Measured means replace the 32,000-byte fallback only for successful sampled zooms. Missing/failed tiles do not count as zero-size successes. Results update progressively and source sessions close on success, timeout, or cancellation. Unmeasured zooms retain the fallback. Sampling incurs a small amount of network traffic; it does not persist sampled tile payloads into download staging.

Refined layer size is 65,536 bytes of database reserve plus the sum of tile counts multiplied by `ceil(mean payload bytes * 1.08) + 128`. This is an approximate SQLite-overhead model, not a guaranteed upper bound; spatial content variability and actual database page packing need device calibration. DEM retains its existing dataset/coverage estimate. An application-scoped bounded cache keeps up to 2,048 measured tile lengths keyed by source/style, effective configuration, endpoint parameters, and tile coordinate; it holds no credentials or image payloads and does not survive restart.

Verification before the owner requested leaving builds to them: 55 domain Android host tests and 9 targeted settings/download-validation host tests passed, and Android debug assembly succeeded. Full constructor host suite exposed an unrelated existing `OnlineMapViewModelTest` dispatcher failure. Final source review then removed an unnecessary planner call when a displayed estimate is supplied and decoupled sampling validation from the editable map name; those final edits have static validation only. iOS compilation was not performed. Further builds, simulator/device launches and manual acceptance are left to the owner. No device download, UI responsiveness measurement, or accuracy acceptance has been performed for this change.
