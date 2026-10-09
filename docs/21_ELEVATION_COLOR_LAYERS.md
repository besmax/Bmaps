# Generated elevation color layers

## Current state — 2026-10-09

Source implementation is present for local generation from package-owned DEM, settings, background scheduling, regeneration, normal layer controls, transfer, and disk-size accounting. Compilation, automated test execution, emulator/device execution, performance measurements, and user acceptance have not been performed for this increment. The user reserved builds and emulator/device testing. This document records implemented source behavior, not verified runtime acceptance.

- [x] Generation settings and defaults in a separate dialog.
- [x] Shared cancellable generator and durable job/checkpoint files.
- [x] Android CoroutineWorker/WorkManager and iOS coroutine/BGProcessingTask scheduling.
- [x] Safe replacement of a generated layer and preservation of presentation settings.
- [x] Normal raster-layer visibility, opacity, ordering, and legend in Layers.
- [x] Actual map size updates and per-layer/physical-capacity checks.
- [x] Generated layer metadata/assets included in existing package transfer.
- [x] Automated verification scenarios added to source.
- [x] Bounded grid/block/row caches, two-worker rendering pipeline, batch transactions, memory progress, and range cache implemented in source.
- [x] Aggregate stage timings and native decode/cache counters added.
- [ ] Android/iOS compilation and test execution.
- [ ] Device acceptance, interruption/relaunch checks, and representative DEM performance measurements.

## User flow

For a ready map containing elevation data, Layers offers Create elevation layer. An unsupported dataset/layout, uninterpreted GDAL metadata, or a raster with no valid elevations produces an explicit error. A package without DEM has no generation action. A package already containing the generated layer instead offers Regenerate elevation layer. There is one generated elevation layer per package.

The separate dialog defaults to a light-to-dark green palette, automatic elevation range across the entire package DEM, and two color handles at opposite palette ends. Blue, amber, and purple presets and explicit minimum/maximum elevations in meters are available. The shared palette track has two independent handles: the upper handle chooses the shade for minimum elevation; the lower handle chooses the shade for maximum elevation. Each handle is a separately labeled accessible Material slider, retaining keyboard and accessibility adjustment and a 48 dp interaction area. Handles may cross to reverse the colors or coincide to produce a uniform color for valid heights. They choose shades within the preset, independently of the numerical height range. Changing palettes preserves handle positions. A separate preview shows the resulting gradient from minimum to maximum elevation. The former Reverse colors checkbox is removed. Manual ranges are independently validated in the dialog ViewModel; both values and their difference must be finite and the maximum must exceed the minimum. Defaults allow pressing Generate without editing any field. Later dialogs load the last successfully generated options.

Submitting schedules generation and closes the settings dialog. Closing Layers or leaving the viewer does not cancel the job. Layers shows range scanning, tile progress, errors, and explicit Cancel generation. Generation itself never requires network access. The first generated layer is visible above the existing layers at 50% opacity. Regeneration preserves its stable ID, visibility, opacity, and render order. Save/Cancel in Layers retains the existing presentation semantics. Generating or regenerating a file is a separate durable operation, not an unsaved layer-presentation draft.

The legend in the generated layer's row displays the color ramp and its resolved minimum/maximum in meters rounded to one decimal place, together with the layer's disk size in decimal MB also rounded to one decimal place. Formatting affects display only; stored elevations, byte counts and color mapping retain their full precision. The map remains usable during generation, with the previous layer retained during regeneration. The viewer refreshes its package session after completion while retaining its camera through the existing viewport conversion.

## Ownership

`domain:map-builder` owns `ElevationReliefOptions`, `ElevationReliefStyle`, generation job states, `ElevationLayerGenerator`, the scheduling contract, and the controller connecting preparation to scheduling. `LocalPackageRepository` owns the final manifest/file commit and updates Room catalog metadata. The generated layer uses the existing raster renderer; it does not add terrain business logic to `shared` or a dependency between feature modules.

`core:storage` owns bounded numerical DEM row/grid reads, physical-block range scanning, and job/range-cache file access. Android JNI and iOS cinterop expose the same native batch operation, avoiding a bridge call per rendered pixel. `core:map-engine` exposes a small Android/Skia PNG encoder. Native objects close independently of coroutine cancellation. Generation is serialized across packages within the shared generator to bound native readers and image buffers.

`feature:viewer` owns a navigation-scoped progress ViewModel and a separate dialog-scoped settings ViewModel. Clearing the dialog store stops presentation observation. Once Generate is accepted, durable preparation and scheduling complete independently of dialog cancellation. `shared` only exposes/initializes the platform scheduler and extends the existing Android worker factory wiring.

## Raster generation

The output MBTiles uses the root layer's tile size, Web Mercator tile matrix, geographic bounds, and exact zoom levels, including sparse selections. Every required tile is written, including fully transparent tiles. This preserves the existing complete-coverage/open/import contract. Complete downloaded edge tiles are colored, including pixels outside the original selection rectangle. Only actual DEM footprint and NoData restrict coloring. New downloads obtain DEM for the complete tile envelope plus a two-cell nominal-grid margin; see `16_ELEVATION_DOWNLOADS.md`. Older narrower DEMs can still leave transparent borders until replaced. NoData and out-of-coverage pixels are transparent; zero and negative elevations remain valid.

For each tile, pixel centers are transformed from global XYZ Web Mercator positions into geographic DEM coordinates. The generator reads neighboring sample rows in bounded native batches and performs bilinear height interpolation before assigning colors. PixelIsArea/PixelIsPoint sample-center and footprint conventions are retained. Interpolation requiring a NoData neighbor remains transparent; it does not bridge missing regions. Edge samples are repeated only inside the valid DEM footprint. The normalized height fraction interpolates between the selected positions in the preset palette; heights outside a manual range clamp to the corresponding selected endpoint color. Layer opacity remains separate from encoded colors.

Automatic range selection scans physical TIFF blocks sequentially in native code, excluding NoData and padded cells outside the actual raster. Each cancellable chunk processes at most eight blocks and 16 MiB of decoded block work. The resulting extrema are saved in device-local `range.json`, keyed by SHA-256 of the actual DEM bytes and cache format version. A new automatic generation hashes the source before using that cache; recoloring avoids another numerical range scan, but still incurs a sequential file/hash pass. An interrupted incomplete scan restarts. The current job's already resolved style and committed tiles survive interruption. Flat DEMs receive a one-meter range around their constant value. TIFF overviews and a persistent per-pixel height cache are not used.

### Generation performance — 2026-10-09

The user reported very slow tile generation. The source now replaces repeated neighbor-row reads and per-tile durable writes with the following bounded operations. The optimization has not yet been built, executed, or benchmarked.

- Precomputed per-axis sample indices, interpolation weights and clipping flags. Longitude plans are reused for the same tile column. Fully transparent/outside tiles avoid DEM reads. Each tile uses its global XYZ pixel centers; original selection bounds do not mask edge tiles.
- Unique source rows/columns read through `DemReader.readGrid`, at most 131,072 doubles per call (1 MiB), 8,192 columns and 1,024 rows. Native code groups requested cells by physical block and reads each requested block once per call, scattering results back to row-major order. Grouping scratch space is at most 2 MiB; JNI adds bounded temporary arrays. DEM rows reused by later tiles share a 4 MiB LRU cache, invalidated when the selected columns change.
- An opt-in native LRU of up to eight decoded blocks and 16 MiB per generation reader; blocks still obey the existing 8 MiB individual limit. Ordinary center sampling keeps its single-block policy. Lazy allocation avoids allocating the whole cache during open.
- One DEM producer, two color/PNG workers on `Dispatchers.Default`, and one database writer. The sampled queue holds one tile; the encoded queue holds two. A sampled 512-pixel tile contains at most 1,024 × 1,024 doubles (8 MiB). Queues, workers and the producer can retain several such grids, plus the row cache, pixel/PNG/codec buffers, TIFF buffers and library allocations; the byte caps are not a process RSS guarantee. Generation remains serialized across packages.
- MBTiles transactions hold at most 32 tiles or 4 MB (4,000,000 bytes) and flush after approximately one second when a partial batch is pending, or at pipeline completion. Existing FULL SQLite synchronization, size limits and capacity checks remain enabled. Fresh output skips per-tile existence queries; recovery checks existing tiles and resumes from actual committed database counts.
- `StateFlow` publishes committed batch progress in memory. Durable JSON progress checkpoints occur at most once per second; preparation, state/style changes, interruptions, cancellation and final progress are persisted immediately. Every durable update validates the active token under the same file lock as its write, preventing deleted/cancelled jobs from being recreated. Worker cancellation retains only successful database transactions. Android notification updates are conflated and spaced by at least 500 ms.

`[BmapsElevation] relief_range` logs cache hits, SHA-256 time and total range preparation time. `relief_performance` logs approximately every five seconds and at the end of rendering: committed tiles, wall time, accumulated grid sampling, color, PNG, database and progress time, periodic checkpoint count, native cache hits, decoded blocks/bytes, numerical samples and grid calls. Stage times overlap across workers and must not be added to obtain wall time. Native counters include range preparation. Timing logs do not assert a measured speedup.

Remaining performance acceptance: compare the same DEM, bounds, zoom levels, tile size and palette before/after, including first generation, cached-range recoloring and interruption/recovery. Large/high-zoom packages can still require many output tiles; no multiplier is promised.

Heights follow the existing provider-based meter and vertical-reference policy (EGM96 or EGM2008); no vertical datum conversion is added. Generated colors are a visualization of the DEM, not an increase in its physical resolution or accuracy. The initial supported DEM subset remains described in `17_ELEVATION_READER.md`.

## Durable execution and lifecycle

Jobs live outside package inventory under private `elevation-jobs/<package-id>/job.json`. Their state is QUEUED, RUNNING, COMPLETED, CANCELLED, or FAILED. The job stores a generation token, options, resolved style, exact total/completed tiles, and a typed failure. Writes replace the synchronized JSON file atomically. The provisional `tiles.mbtiles` and its journal, plus the small reusable `range.json`, are kept in that job directory; they are not exported as package assets. Range cache survives regeneration and explicit cancellation but is removed with package/job deletion.

Android initializes pending jobs at application startup and schedules unique, network-independent WorkManager work with a foreground progress notification. The existing worker factory also creates `ElevationGenerationWorker`. The foreground type is dataSync, which includes local file processing in the [Android foreground-service contract](https://developer.android.com/develop/background-work/services/fgs/service-types). Platform restrictions and quotas still apply. System interruption preserves the durable request and successfully written tiles for a later execution. Explicit in-app cancellation saves CANCELLED before cancelling work.

iOS registers `bes.max.bmaps.elevation-generation` during launch, uses an application-scope coroutine with foreground lifetime extension, and submits BGProcessingTask requests without a network requirement. Expiration cancels the coroutine safely and preserves queued work. App activation resumes pending work. iOS chooses background execution opportunities; continuous execution while suspended, immediate execution, and execution after a user force quit are not promised.

Starting another generation for the same package while its job is active returns Conflict. Successful writes checkpoint progress. Interrupted SQLite transactions are recovered by SQLite and actual output counts override a stale progress checkpoint. Explicit cancellation/failure discards provisional tiles; another Generate starts a fresh job. Deleting the package deletes its durable generation files; an in-flight generator rejects further publication/commit when its job disappears.

## Commit, regeneration, and storage size

Options persist nullable `minimumColorPosition` and `maximumColorPosition` in [0, 1]; both must be present together or absent together. New dialog submissions explicitly store both positions and set the legacy `reversed` flag to its default false. Absent positions preserve the previous full-palette behavior, including `reversed: true`; explicit positions take precedence over that flag. Opening an existing reversed layer places the minimum handle at 1 and the maximum handle at 0. Completed-layer legends and regeneration dialogs use the same resolved positions. Positions survive job restore and package transfer. These optional fields retain manifest schema version 1.

The stable logical layer ID is `elevation-relief`. Each successful generation has an immutable versioned asset path `layers/elevation-relief-<generation-token>.mbtiles`. Its optional `PackageLayer.elevationRelief` metadata stores both the requested options and resolved height range. Legacy layer metadata defaults to null; the manifest remains schema version 1.

The repository validates tile coverage/counts and package ownership, closes the output database, synchronizes/moves the completed file, then atomically replaces `config.json`. The manifest switch is the authoritative commit. Existing opened tile readers retain their old immutable file; future source openings use the new version. Old versions are excluded from current manifest assets/export and removed once no open source needs them, during subsequent package access or startup reconciliation. A file installed before an interrupted manifest switch is an unreferenced candidate and is cleaned up; the old committed layer survives. A manifest committed before job completion is detected during recovery and treated as completed.

Every generated MBTiles obeys the package's effective per-layer limit, at most 300,000,000 bytes including database overhead. Free space is checked before creation and during tile/job/manifest writes. No aggregate map-size limit is introduced. Regeneration requires extra space for provisional output, SQLite journals, and the old version until readers release it.

Catalog `PackageSummary.sizeBytes` and the completed download job's `packageBytes` are updated from the actual package directory, including the new MBTiles and `config.json`. Briefly retained old reader versions are included while present on disk; cleanup/reopening updates the recorded size. Generation work files and the small persistent range cache are extra application storage outside that committed map size. The optimized pipeline adds no additional committed raster or height-cache asset; the generated MBTiles still contributes its actual file size. Ready-map tile summaries include generated tiles, while original network download totals stay unchanged. Startup reconciliation accounts for generated layers when checking those historical download totals.

`.bmaps` export/import preserves the generated file, options/range, and normal presentation fields using existing raster snapshot/hash/identity-rebinding logic. Job files are device-local and are not transferred. Imported generated layers remain ordinary aligned raster layers.

## Verification

Executed static checks: source/license headers and packaged notices, `git diff --check`, changed resource XML and resource-name references, the iOS background task identifier, and documentation-guide path existence. These checks do not establish compilation or runtime correctness.

### Scenarios prepared, not executed

- `ElevationReliefTest`: negative/zero heights, endpoint clamping, missing values, legacy reverse colors, selected/crossed/coincident color handles, invalid color positions/ranges, manifest round trips, legacy defaults, and generated-layer alignment restrictions.
- Native `dem_test.c`: row/grid batches across existing endian/compression fixtures, arbitrary row/column order, negative/outside indices, NoData, batch size rejection, multi-block cache reuse/decode counts, and physical-block extrema excluding edge padding.
- `DemReaderTest`: grid bounds, opt-in cache forwarding, row-major values, and closed-reader rejection.
- `ElevationTileSamplerTest`: independent per-pixel interpolation reference for Area/Point, bounded unique-grid reads, row-cache reuse, neighboring tile/global pixel consistency, and fully transparent tiles without native reads.
- `PackageStorageScenarios.generatedElevationLayerReplacementAndSizeAccounting`, registered for Android device and iOS tests: initial size increase, retained old readers, presentation preservation, retired-file cleanup, cancelled commit rejection, restart reconciliation with historical download totals, transfer, range-cache exclusion from map size/preservation across tile cleanup, and deletion of job/cache files.

Manual acceptance must cover default/custom settings; dragging both color handles through each other and to matching positions; changing palettes; restoring legacy reversed settings; selected-endpoint legends; keyboard/accessibility control; flat and negative-height regions; DEM edges/NoData and tile seams; all supported datasets; high zoom and sparse zoom selections; pan/zoom during generation; repeated regeneration; cancellation during range scan and tile writing; OS interruption/process relaunch; map deletion/export during generation; physical free-space and layer-limit failures; library size before/after completion and cleanup; and iOS background expiration. Builds, these tests, and visual/performance acceptance remain with the user.

### Metro contribution scope correction — 2026-10-09

The user reported `Metro/MissingBinding` for `ElevationGenerationScheduler` during compilation. Inspection of existing Android build artifacts showed that its generated hint was named `AndroidElevationGenerationSchedulerDev_zacsweers_metro_AppScopeKt`, while the existing download scheduler used the correct project scope `Bes_max_bmaps_core_di_AppScope`. Replace wildcard Metro imports with explicit annotation imports in both elevation schedulers and the new viewer ViewModels to prevent incorrect scope hint resolution. Source correction is present; no agent-run rebuild or runtime tests were performed. Successful compilation and device acceptance remain pending user confirmation.

### Palette endpoint selection — 2026-10-09

Source implementation replaces the direction checkbox with two independent handles on one palette scale and an explicit output preview. Defaults remain the full light-to-dark preset. Settings ViewModel validation includes finite bounded color positions; persisted options, tile coloring, legends and regeneration share those positions. Legacy options/queued jobs retain their previous colors. Color and serialization regression scenarios were added in source; static checks only. Compilation, automated execution, interaction/visual checks and device acceptance remain pending with the user. This change adds small option fields, not an extra raster/cache asset; existing actual MBTiles/map disk-size accounting remains authoritative.

### Complete edge-tile coloring — 2026-10-09

Source changes remove the original selection mask from `ElevationTileSampler`; full tile pixels now use available DEM samples, retaining transparent NoData/outside coverage. Tile count, dimensions, exact zooms and aligned manifest bounds stay unchanged. New download requests include the complete tile envelope and a nominal two-cell margin, with actual footprint verification before commit. The automatic range still scans the entire package DEM, including extra coverage. Existing generated files require regeneration; a narrower old DEM also requires a new download to supply missing samples. Full edge-tile and request/coverage scenarios were prepared but not executed. Compilation and visual/device acceptance remain with the user.
