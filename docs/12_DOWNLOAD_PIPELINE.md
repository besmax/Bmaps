# Download pipeline and current state

Phase 5 implementation, 2026-09-13. Source review only: no builds, test execution, simulator/device runs, or live tile downloads were performed. Compilation and acceptance remain assigned to the user. Phase 6 offline viewing and library management are now implemented, with verification pending; see `13_LIBRARY_AND_OFFLINE_VIEWER.md`.

## User flow

After choosing an area, the settings dialog accepts a map name and exact zoom levels and shows tile count, advisory decimal MB, available storage, and absence of elevation data. The local-time default name is `yyyy-MM-dd_HH:mm`. Presentation validates the name, provider coverage, projection, and zoom limits independently before submission. Estimates use the same coverage calculation as execution, with a metadata reserve and average tile cost; they are not measured download sizes. Submission requires estimated size within 300,000,000 bytes and free space of at least twice the estimate for working files. Storage also enforces actual package and capacity limits.

Notification permission is requested at submission where needed. Denial does not prevent downloading or library progress. Successful submission returns to the library. Active downloads appear above the map list with durable completed/total counters and pause/cancel controls. Incomplete entries show warnings, missing tile counts, actionable failure guidance, and Restore. Cancel in this UI retains partial work. List loading uses the database's keyset pagination and can continue beyond 200 maps.

All new manifests explicitly contain `elevation: null`; the library shows no elevation data. DEM downloading remains future work. Ready packages are listed, but tapping into an offline viewer and the remaining library details/filter/deletion UI belong to Phase 6.

## Execution and recovery

`TileDownloadPlanner` streams tile keys rather than allocating a full plan. `DurableDownloadExecutor` owns start/pause/cancel/resume commands, persists the request before scheduling, and uses stable package IDs for idempotent submission. `DownloadRunner` has four concurrent tile operations globally, bounded per-job queues, and reuses the provider adapter's request pacing, concurrency limits, and bounded retry/backoff behavior. Native schedulers receive only durable job identity, not credentials.

Production downloads check raster headers/dimensions before committing. Full pixel decoding remains the renderer's responsibility. Successful logical tile identities are authoritative in MBTiles; Room carries durable job state and progress. A missing response records a failed tile and continues; an exhausted typed network/provider failure checkpoints the failure and stops the job. Unattempted tiles remain implicitly missing. Restore enumerates the stored coverage and skips committed tiles, downloading only what is missing. Required missing tiles prevent finalization.

Fresh execution checks the configured provider download capability and source configuration. Restore rechecks these before fetching more tiles. A complete staged tile set can retry finalization without opening a network source. Provider capability configuration is an application gate, not evidence of external download entitlement; the existing local provider configuration changes are preserved.

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

The constructor can add provider/style layers to the selected area. Every layer uses the same selected zoom levels, with presentation validation against each provider’s coverage and tile matrix. Estimates and package limits include all layers, including hidden layers. The root writes to `map_data.mbtiles`; each additional layer writes to `layers/<layer-id>.mbtiles`. Initial visibility and opacity are persisted in the durable request and manifest.

Android schedules a unique sequential WorkManager chain with one worker per layer, carrying the package job identity and durable layer index. Additional workers execute only after successful predecessor completion. iOS executes the same layer-scoped runner sequentially within its foreground/background processing opportunity. A runner checks that predecessors are complete before opening the next source. Missing root tiles stop the chain. Each layer closes its source and persists QUEUED at the boundary; only completion of the entire package triggers finalization and READY. Restore skips completed layers without reopening their providers and skips committed tiles within incomplete layers. No Room schema change is needed: layer requests and manifests are already serialized durably. User-run validation remains pending.
