# Library and offline viewer

Phase 6 implementation, 2026-09-14. Source review only; builds, schema generation, automated test execution, and manual/native acceptance remain with the user.

## Navigation and library

The home destination is the local map library. An extended floating action button opens the constructor; tapping a ready map opens `viewer/{packageId}`. Incomplete entries open their details and retain the existing missing-tile restore controls. There is no bottom navigation or retained tab back stack. Constructor and viewer navigation return to the library through Back. The top-bar gear opens the existing preferences dialog, retaining its dialog-owned draft and ViewModel lifetime.

Search, readiness filters, favourites, and filter-bound keyset pagination execute through `core:database`. Changing search or filters resets the loaded page count. Ongoing downloads remain above the result list even when filters exclude their package. Details show WGS 84 bounds, exact downloaded zoom levels, tile counts, size, status, and elevation availability. Empty search results are distinct from an empty library.

Each package supports a favourite flag and six built-in vector avatars: map, mountain, forest, water, city, and camp. Both library details and viewer details can change the avatar; favourites can be toggled in either screen. These are device-local library preferences, stored separately from download and manifest metadata. They survive checkpoints, finalization, reconciliation, and ordinary app restarts. Photo picking and avatar export are not implemented.

## Persistence and deletion

Room schema version 2 adds `package_preferences`, keyed by package ID with a cascading foreign key. The explicit 1-to-2 migration creates this table without changing existing package/job records. The version 1 exported schema is retained. Generate and review version 2 during the user's next build; no schema hash has been fabricated.

Favourite filtering is part of the SQL query and cursor identity. Repository observations also react to preference updates, including changes in an already open viewer. Unknown avatar keys display the default map icon.

Deletion requires an explicit confirmation naming the map. Existing jobs go through the download executor so native scheduling and runner work stop before package deletion. Confirmed deletion also permits a job that completed while the confirmation was open. Packages without jobs use the repository directly. The repository marks DELETING, closes owned MBTiles sessions, removes staging/final files, and deletes metadata and cascading preferences. Existing reconciliation retries interrupted deletion; errors remain actionable in the library.

## Offline rendering and lifetime

`ViewerViewModel` injects only `PackageRepository`. It opens a verified `OpenedPackage` and supplies its local MBTiles tile sources to `RasterMapRenderer`; no online source, provider credentials, HTTP cache, or download executor participates in viewer tile reads. Existing queued downloads remain independently managed by Phase 5 and must be stopped when measuring viewer-only network activity.

The renderer runs inside the package-owning coroutine. Retry cancels and joins that coroutine before opening its replacement. Renderer cancellation joins tile reads and closes tile sources before the enclosing package session closes. The navigation entry owns the ViewModel, preserving the renderer and viewport across ordinary recreation and background/foreground transitions. Leaving the entry releases the session. Viewport persistence across process death or reopening a popped viewer is not promised.

The initial viewport centers the downloaded geographic area within its tile-aligned region. Continuous downloaded levels use an automatic tile pyramid when its dimensions fit the engine. Every downloaded level is also available explicitly. Separated levels, or an automatic pyramid that would overflow engine dimensions, use explicit level selection without silently dropping levels. Explicit-level mode limits zoom-out to roughly eight tiles along the larger extent, avoiding unbounded requests when a large high-detail region has no lower-resolution tiles. Display zoom and source level remain separate controls. A selected regional level can exceed zoom 18; coordinate precision is bounded by the existing level-52 geometry limit and renderer dimensions.

Date-line-crossing maps expose both geographic halves as separate regions with canonical XYZ addressing. They do not stretch a narrow crossing selection across the whole world. Switching levels preserves the geographic center when possible; switching halves fits the new region. Missing and unreadable local tiles have visible messages, and package-open/configuration errors support Retry. Map details retain downloaded bounds, levels, size, elevation availability, and elevation metadata. Phase 6 renders the base raster layer; layer composition and controls remain Phase 7.

## Verification handoff

Authored or updated, not run:

- `OfflinePyramidTest`: high-level regional XYZ addresses, exact separated levels, automatic-pyramid overflow, and both date-line edges.
- `ViewerViewModelTest`: same-entry reuse, retry closure ordering, final owner cleanup, missing-package retry, and observed favourite/avatar mutations.
- Existing native package-storage scenarios: preferences survive failed-download recovery, finalization, and database recreation; favourite paging and cursor-filter isolation; deletion cascades preferences and closes tile sources.
- Existing Android navigation and raster scenarios: home FAB, gear accessibility description, and Back replace tab interactions.

User acceptance should cover migration from an existing version 1 catalog, relaunch persistence, more than 200 filtered maps, and deletion during download completion. For the M1 flow, download a package, terminate the app, disable connectivity and clear disposable HTTP cache, relaunch, open the map, and pan/zoom all downloaded levels on both platforms. Include separated levels, a high-detail regional map, a date-line-crossing selection, damaged/missing files, repeated Retry, rotation, and backgrounding. Verify deleted files and Room records agree. Compilation, generated schema validation, actual image decoding, and native acceptance are still pending.

Source checks completed: `git diff --check` and parsing of changed feature XML resources, including string/drawable reference consistency. These checks do not establish compilation or runtime behavior.

## Attribution dialog

The offline map shows a bottom-right info icon when the rendered base layer has attribution entries. Tapping it opens a scrollable dialog with every attribution text and URL from that layer, a suggestion to visit the source links, and an Open link action for each entry. Done, outside tap, and Back dismiss the dialog. Visibility belongs to `ViewerState`; opening another package resets it. Opening a link dismisses the dialog and uses the existing URI handler and snackbar error event. Online-map attribution presentation is unchanged.
