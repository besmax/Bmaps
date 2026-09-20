# Map object clustering: implementation proposal and Luna handoff

Status: implementation started and Android host-verified on 2026-09-20. Device and simulator verification remains with the user. Preserve all existing workspace changes, including polygon encoding/recovery fixes and the user's editor formatting changes.

## 1. Product decisions

Confirmed by the user: clustering applies to markers, lines, and polygons. Nearby small lines and polygons may collapse into numbered circles alongside markers; large visible shapes remain drawn.

1. Clustering is automatic in the offline viewer. There is no settings toggle in this version.
2. A group of at least two nearby eligible objects is represented by one numbered circle. Count objects, not vertices. Different object kinds and colors may share a cluster.
3. Point markers are eligible. A line or polygon is eligible only while its entire projected bounding-box diagonal is at most **64 dp** and its bounding box is entirely inside the active map region. Large shapes and shapes crossing the region boundary remain fully drawn.
4. When an object belongs to a cluster, suppress its original marker/path. A singleton keeps its existing appearance; do not introduce a numbered `1` circle or proxy icon for singleton shapes.
5. Hidden kinds are excluded from both clustering and counts. Raster-layer visibility does not affect map objects.
6. A selected object is drawn normally and excluded from clustering. While any draft is active, disable clustering for the whole viewer, preserve existing drawing interactions, and draw all draft vertices normally. Resume clustering after Save or Cancel.
7. A cluster click zooms to a computed scale at which its membership actually separates into at least two display groups. Smaller clusters may remain; one click need not reveal every individual object. Merely zooming by a fixed factor or fitting bounds is insufficient.
8. If separation is impossible within the renderer's zoom limit, open a list of that cluster's members. This includes coincident markers. Never make repeated taps produce a no-op.
9. Cluster membership is independent of panning for a fixed package snapshot, region, scale, and visibility. Counts may include members just outside the visible screen. Accessibility wording and the member-list subtitle must say this.
10. This version supports clustering for packages containing at most **1,000 total saved objects**, using a complete, bounded package snapshot. Larger packages keep the existing viewport-based rendering and show a localized explanation in Map objects. Do not cluster an incomplete page and present its size as a complete count.
11. No schema changes, persisted clusters, new dependencies, module creation, network requests, or automatic downloaded-level/region switching.

Why keep large shapes: a long route must not disappear because its midpoint is near a marker. Why a member list: objects with identical positions cannot be separated spatially by any amount of zoom.

## 2. Relevant existing implementation

- `feature/viewer/.../AnnotationEditorViewModel.kt` owns saved viewport items, visibility, selection, drafts, and repository queries. Queries are debounced by 180 ms and stop at 1,000 loaded items.
- `feature/viewer/.../AnnotationLayers.kt` converts annotations into `MapMarker` and `MapPath`; polygons become closed, filled paths.
- `feature/viewer/.../ViewerScreen.kt` currently computes overlays with `remember`. Its keys do not include scale or viewport dimensions, so that calculation alone cannot implement screen-distance clustering.
- `feature/viewer/.../ViewerViewModel.kt` owns the renderer and forwards generation-filtered map events to the editor. Its `configure()` emits a synthetic full-region viewport event; this is not an actual measured viewport and must not drive cluster distance calculations.
- `core/map-engine/.../RasterMapRenderer.kt` computes fit scale and safe maximum scale. `MapViewport.scale` is relative to fit, while MapComposeMP uses absolute engine scale.
- `core/map-engine/.../RasterMapConfig.kt` currently has a controller with only relative zoom commands.
- `core/map-engine/.../RasterMap.kt` removes and adds all overlays whenever either overlay list changes. Do not extend this to recreate every path on every pan or animation frame.

The pinned MapComposeMP 1.1.3 source was inspected in the local Gradle sources archive. `MarkerApi.kt` provides `addClusterer`, `RenderingStrategy.Clustering`, and centered cluster markers. `Clusterer.kt` clusters markers only, uses viewport-dependent membership, fits member bounds on click, and disables clustering at maximum scale. These do not satisfy the chosen mixed-geometry, stable-membership, and terminal-member-list behavior. Use the custom pure clustering algorithm below, rendering its output through ordinary MapComposeMP markers. Do not enable a second native clusterer on those markers.

`LayoutApi.kt` provides suspending `scrollTo(x, y, destScale, animationSpec, screenOffset)`. Default marker anchoring is bottom-center; cluster circles explicitly need center anchoring.

Before implementation, read relevant sections of `docs/2_ARCHITECTURE.md`, `3_MODULES.md`, `4_STATE_AND_UI.md`, `9_MAP_ENGINE.md`, `10_BMAPS_DESIGN.md`, and `15_ANNOTATIONS_AND_GEOJSON.md`. Reuse already-read context. Do not rewrite existing unrelated code.

## 3. Ownership and state

Keep annotation-specific clustering in `feature:viewer`, in a new stateless `AnnotationClusterer.kt`. It may depend on domain annotation models and core geometry. It must not access repositories, Compose state, or MapComposeMP classes. Use plain immutable Kotlin input/output values.

Keep generic camera metrics, camera commands, marker anchoring, and engine synchronization in `core:map-engine`. No domain imports there. No changes to `:shared` DI composition are needed.

Extend the existing editor's single state with a nested immutable clustering state containing:

- Catalog status: `Loading`, `Ready`, `TooMany`, or `Failed`.
- Complete catalog values only for `Ready`; a revision number incremented when the complete snapshot changes.
- Current camera snapshot identity and projected/clustered presentation output.
- A computation revision, so stale asynchronous results can be discarded.
- Optional member-list state: immutable sorted object IDs and the originating catalog revision.
- Optional pending expansion: command ID, camera session token, original member IDs, and catalog/computation revision.

Keep runtime jobs, indexes, and caches private to the ViewModel. Do not create a second StateFlow or a separate reducer/store. A small member list is part of the existing editor and does not need a new heavyweight ViewModel.

New pure feature types should distinguish `Object(id)`, `Cluster(sortedMemberIds)`, and `DraftVertex(index)` as overlay hit targets. Renderer IDs must not be interpreted as annotation IDs by prefix guessing. Maintain an explicit ID-to-target map. Give renderer IDs disjoint namespaces; encode arbitrary annotation IDs without ambiguity. For a cluster use its sorted member IDs with length-prefixed encoding, not `hashCode()` alone. IDs are transient and never persisted.

## 4. Loading a complete clustering snapshot

Continue the existing viewport repository queries for the Map objects list and for rendering when clustering is unavailable. Keep database bounding-box filtering and keyset pagination in their existing data/storage implementation; do not move the panel's query filtering into presentation.

Separately load all package annotations through `repository.annotations(packageId, bounds = null, after = cursor)`:

1. Start on package open and after a successful save, delete, or import. Explicit Retry retries both failed catalog and viewport loads. Panning never reloads the complete catalog.
2. Accumulate pages into a temporary list. Check coroutine cancellation, package identity, and request revision before publishing. Defensively reject duplicate IDs or a nonadvancing cursor as a load failure.
3. Publish `Ready` only after a page has no next cursor and total size is at most 1,000. Exactly 1,000 with no next cursor is supported. Exactly 1,000 with a next cursor is already `TooMany`; do not fetch an unbounded remainder. Discard partial catalog values on failure or overflow.
4. During initial loading, failure, or `TooMany`, show existing viewport objects without clustering. A catalog failure must not hide successfully loaded viewport objects. Report catalog failure separately from the existing viewport-load error so either successful job cannot clear the other's error.
5. On mutation success invalidate the old catalog and its expansion/member-list state immediately, then reload. Do not publish a mixture of old and new catalog pages. Application-level annotation mutations are owned by this editor; if another in-app writer is introduced later, it must invalidate this snapshot too.
6. Package changes cancel catalog, viewport, and clustering jobs and clear all old results. Existing storage recovery for legacy polygons remains in the repository and must be used by both loads.
7. When catalog is `Ready`, render from this complete snapshot, not the viewport page. The panel still shows its current viewport page. Selection lookup must support both catalog and viewport items, because a cluster can reference an object absent from the current page.

Introduce a named presentation constant `MAX_CLUSTER_OBJECTS = 1000`; do not couple this behavior semantically to the GeoJSON import/export limit, even though the numbers currently match. Retain the existing viewport truncation message separately.

## 5. Camera metrics and coordinate contract

Add a renderer-independent immutable `MapCameraSnapshot` in core containing:

- Unique renderer session token (new for every actual engine recreation, including layout resize).
- Content generation and the exact active `TilePyramid`.
- Actual current `MapViewport` and un-clipped local `MapWindow`.
- Actual layout width/height in physical pixels.
- Current and effective minimum/maximum scale, all expressed in **fit-relative units**.
- Whether zoom interaction is enabled.

Publish it as a `StateFlow<MapCameraSnapshot?>` on the renderer. This is persistent renderer state, not another editor ViewModel state. Publish the first snapshot after the engine has a valid layout, update it on actual viewport changes, and clear it when that engine is retired. Do not depend solely on the bounded renderer event channel for latest camera state. Never publish a synthetic full-region window as a measured snapshot.

ViewerScreen supplies current `LocalDensity.current.density` to the editor via a lifecycle-safe effect. Forward renderer camera snapshots to the editor through one collector in ViewerScreen; retain the existing tap/viewport event forwarding for editing and database queries. A camera snapshot is usable only when its generation/pyramid corresponds to the current viewer configuration. Clear obsolete results during the gap between configurations.

For engine dimensions `(W, H)` and layout `(Lw, Lh)`:

```text
fit = min(Lw / W, Lh / H)
s = viewport.scale                         // relative scale
absoluteScale = fit * s
baseDpX = W * fit / density
baseDpY = H * fit / density
distanceDp(a, b, s) = hypot((a.x-b.x)*baseDpX*s,
                            (a.y-b.y)*baseDpY*s)
```

Use Double throughout projection/distance calculations. Convert dp to pixels only at the appropriate UI boundary. Do not use raw geographic degree distances, tile zoom numbers, or `MapViewport.scale` as if it were an absolute engine scale. Retain rectangular-map aspect ratio. Invalid/zero dimensions, nonfinite metrics, or unavailable projection mean normal overlays without clustering, not an exception or an empty map.

Use existing Web Mercator behavior for projection. Do not wrap the world or cluster across separately selectable date-line regions. Respect the existing floating-point geographic conversion limit at source level 52; above it, do not invent approximate clustering coordinates. Preserve original domain coordinates and geometry.

## 6. Geometry preparation and grouping algorithm

### Preparation

Cache projected geometry and bounds per `(catalogRevision, pyramid)`, not per pan. Filter hidden kinds before grouping. Exclude the selected object and all draft data. During drafting use the existing normal overlay flow.

- Marker anchor: its projected point; exclude markers outside the active region or unsupported projection coverage, matching existing rendering.
- Shape anchor: center of its full projected bounding box, not average vertex position or geographic longitude/latitude center.
- Shape size: projected bounding-box diagonal using the distance formula at the candidate scale. Only shapes of size `<= 64 dp` wholly inside the active local region `[0,1] x [0,1]` are clustering candidates. Other intersecting shapes stay as original paths.
- Invalid candidate projection never suppresses an otherwise renderable original path.

### Grouping

Use an undirected proximity graph. Connect two eligible anchors when their distance is **at most 64 dp**. Clusters are connected components with at least two members. Singleton components remain individual objects. A chain A-B-C may form one cluster even when A-C exceeds 64 dp; this transitive grouping is deliberate and must have a test. Do not silently replace this rule with a grid-cell grouping or greedy nearest-center algorithm.

Implement components with union-find and a spatial hash whose cell side is 64 dp. Check all nine neighboring cells, and use the exact squared-distance comparison before unioning. Sort inputs and output member IDs lexicographically for deterministic results. Cell coordinates use `floor`, not integer truncation, and sufficiently wide integer keys. Handle nonfinite or out-of-range cell indices by a correct bounded pairwise fallback, not overflow. At most 1,000 candidates means at most 499,500 pair comparisons even in the dense fallback. Do not create all graph edges in memory.

For each component retain its members, original geometry bounds, and arithmetic-mean anchor in projected coordinates. Clustering considers the whole active-region snapshot, not only on-screen candidates. Consequently zooming can only remove edges or remove shapes from eligibility; it cannot join previously separate components when the input snapshot stays fixed. This monotonic property is required by expansion search.

### Badge placement and culling

Keep grouping independent of pan. For display, determine component members whose projected geometry bounds intersect the actual visible window expanded by 32 dp on each side. A marker has zero-size bounds. If none intersect, do not render its circle.

Place a visible circle at the arithmetic mean of these visible member anchors, clamped into the intersection of the viewport and local map region with a 32 dp inset. If that intersection is narrower than 64 dp on an axis, use its midpoint on that axis. If there is no viewport/region intersection, draw no circle. This prevents a long connected component's off-screen global centroid from making all of its on-screen members disappear. Membership and count still include the whole component; placement alone changes with pan.

Draw original paths/markers only for objects not represented by any cluster. Do not render a hidden member merely because its cluster circle is currently culled. Selected objects remain exempt and visible normally.

## 7. Exact cluster-click behavior

Resolve the clicked ID using the current hit-target map. Ignore stale targets, duplicate taps while an expansion is pending, and cluster actions during draft editing or mutations. Recheck catalog revision, visible kinds, selected ID, and camera session before applying any computed answer.

Let `C` be the clicked member set, `s0` current scale, and `sMax` effective maximum scale from the current renderer snapshot. Define `split(s)` by running the same grouping/eligibility algorithm at scale `s` on the same complete input snapshot:

```text
split(s) = no output group contains every original member of C
```

Treat nonclustered objects, including shapes that become too large, as singleton groups. Membership loss from data changes is not a successful spatial split; cancel that obsolete request instead.

1. If zoom is disabled, `sMax <= s0 * (1 + 1e-6)`, or `split(sMax)` is false, open the member list without moving the camera.
2. Otherwise binary-search `[s0, sMax]` for the first splitting scale, using 24 iterations and cancellation checks. Maintain a false lower bound and true upper bound. Use the upper bound as the answer. Revalidate that it actually splits; floating-point tolerance must never be used to claim a split that the algorithm does not produce.
3. Choose `target = min(sMax, max(s0 * 1.5, splittingScale * 1.05))`. Re-evaluate `split(target)` before sending the command. The margin avoids landing exactly on a grouping threshold; it never exceeds the real maximum.
4. Center on the clicked badge's local map position, clamped to `[0,1]`; this is the visible part of a possibly long cluster. There is no requirement to fit all members simultaneously. Animate to the absolute target scale in approximately 300 ms through the controller described below.
5. Recompute presentation from actual camera snapshots throughout the move. On completion, recompute once immediately and verify against the actual resulting scale. If the camera did not reach a splitting scale despite a valid request, show the member list. Clear pending state on completion/cancellation/stale session.
6. If the user pans/pinches, presses another camera control, changes region/level/package, starts editing, or changes object visibility during expansion, cancel the pending automatic expansion and its verification. Do not fight the user with a corrective second zoom or open an obsolete list.

Do not repeatedly call `zoomIn()` until the circle happens to change. Do not assume a bounding-box fit splits a cluster. Coincident objects must have a deterministic usable fallback.

## 8. Camera controller implementation

Replace the controller's private `Channel<Double>` with one unified latest-command stream containing relative zoom and absolute viewport-move commands. Preserve public `zoomIn()` and `zoomOut()` behavior for constructor and viewer callers.

Add a command entry point equivalent to:

```kotlin
fun moveTo(sessionToken: Long, requestId: Long, viewport: MapViewport)
```

Use one `collectLatest` consumer inside the current render session so a new command cancels the preceding animation. Validate finite inputs and the expected session token before touching the engine. Convert relative target scale by multiplying by the session's fit scale and clamp to that session's existing effective limits. Call the inspected `scrollTo` API with a 300 ms tween; never instantiate another MapState or change raster content to zoom.

Expose completion/cancellation/rejection for request IDs through a dedicated reliable Channel-backed controller result Flow. Results contain request ID, session token, outcome (`Completed`, `Cancelled`, `Rejected`), and the actual final snapshot when available. Use an unlimited Channel with one terminal result per absolute-move request; relative zoom commands generate no results. This permits non-suspending terminal-result delivery from cancellation cleanup. Do not send these results through the renderer's drop-oldest tile-event channel. The feature must not stay pending forever after an interrupted animation. Consume results in one viewer collector and route them to the editor. Explicitly cancel any replaced pending command even if it has not yet begun execution; a conflated Channel alone silently drops such commands and is insufficient for terminal-result delivery.

Implement gesture interruption with a pointer-observation modifier on the map container in RasterMap. In `awaitEachGesture`, await the first down with `requireUnconsumed = false` and `PointerEventPass.Initial`, call a controller `cancelMove()` action, then observe until all pointers are released. Never consume pointer changes or replace the map's tap handler. This conservatively cancels on touch-down before a pan/pinch starts, while preserving normal map/marker gestures. Cancellation must invalidate a pending not-yet-started absolute move as well as cancel an active one. Session shutdown cancels the animation and invalidates its token. Stale queued commands are rejected by the next session, never applied to the next map. Cancel pending expansion when ViewerScreen is disposed; do not leave an invisible viewer animation running after navigation.

Do not leak MapComposeMP classes into the controller's public contracts. Relative zoom controls and cluster expansion must respect `config.interactions.zoom` consistently.

## 9. Overlay synchronization and visual design

Extend `MapMarker` with defaulted generic anchor and z-order fields. Suggested enum: `BOTTOM_CENTER`, `CENTER`. Existing markers keep their defaults; clusters use `CENTER`. In RasterMap translate these to MapComposeMP relative offsets `(-0.5f, -1f)` and `(-0.5f, -0.5f)`. Ordinary saved markers z=0, clusters z=1, selected/draft handles z=2.

Keep cluster member/count metadata in feature display models, not in icon names or the generic core marker contract. Viewer marker content resolves its typed display target and draws either the existing MarkerIcon or a new `AnnotationClusterBadge`.

Badge specification:

- 48 dp circular visible surface and at least 48 dp touch target; center the circle at its map anchor.
- `MaterialTheme.colorScheme.primary` background, `onPrimary` count, 2 dp `surface` border, 2 dp shadow. Use theme roles in light/dark modes.
- Exact decimal count, including `1000`; no `99+` because exact counts are available. Use `labelLarge` with tabular figures. At increased font scale, grow the circle enough to fit measured text plus 12 dp horizontal/vertical padding; retain 48 dp minimum. Do not clip or shrink accessibility text. Grouping remains based on 64 dp, independent of font size.
- One semantic button with localized description: “%1$d map objects. Zoom in or show objects. Some may be outside the visible map.” Hide the duplicate child-text semantics.
- No persistent badge count or membership stored on Annotation. No image asset or SVG required for the circle.

Use one feature-level click dispatcher for touch and accessibility. MapComposeMP `onMarkerClick` owns physical marker taps. Marker content supplies a semantic button role and semantic `onClick` calling the same dispatcher; do not also wrap it in `Modifier.clickable`. Remove the viewer's existing extra `clickable` from marker content when making this change, retaining its accessible activation through semantics. Native path taps continue through `onPathClick`. Test that a physical tap produces exactly one action and a screen-reader activation also produces one action. Never let a cluster tap append a draft vertex or select an arbitrary member. Draft vertex semantic activation dispatches the existing vertex selection action.

Replace the single all-overlays DisposableEffect with keyed per-overlay lifetime effects or an equivalent internal diff synchronizer. Unchanged paths/markers stay registered across viewport changes. Remove missing overlays, add new ones, and update only changed ones. Callback registrations belong to the engine lifetime, with `rememberUpdatedState` for feature callbacks/content. Clean up only this composable's overlays on disposal. Engine replacement reattaches current overlays and disposes old engine registrations. Do not call global remove-all APIs.

## 10. Member list and selection

Use an AlertDialog with a LazyColumn capped at 440 dp height and a Close button. Title: “%1$d map objects”. Subtitle explains that these objects cannot be separated further at the available zoom, and some may be outside the current view. Provide localized fallback names using the existing kind labels.

Rows are sorted by annotation ID for stable order, show kind and name, and are keyed by ID. The list must support all 1,000 members without composing them all at once. Opening it never queries the repository: resolve members from the complete current catalog.

Selecting a row closes the member list and opens the existing object-details flow by its real annotation ID. That selected object becomes exempt from clustering. Edit/Delete continue through existing actions. There is no automatic geometry edit or persistent mutation on cluster click.

Close or clear this list on package/region change, mutation success, start of editing, catalog invalidation, or visibility change affecting its members. Do not keep stale Annotation copies after deletion. A selected off-screen object's details remain usable without forcing a camera move; this matches panel selection behavior.

Add all wording to `feature/viewer/src/commonMain/composeResources/values/strings.xml`, including catalog-loading/failure/retry and large-package fallback messages. Do not hardcode English strings inside composables or ViewModels. Existing resources are the default locale; follow the project's existing localization conventions.

## 11. Scheduling and performance

- Geometry projection runs on `Dispatchers.Default` when catalog/pyramid changes.
- Grouping runs on `Dispatchers.Default` when scale, density, visibility, selected object, or catalog changes. Use cancellation checks in spatial-hash loops and expansion search. Panning only recalculates badge placement/culling, not group membership.
- During continuous camera changes, conflate updates and process at most one grouping computation per 50 ms, always taking the newest snapshot. Do not use a debounce that waits indefinitely until a gesture stops. The final state after a gesture must be processed; explicit expansion completion triggers an immediate update.
- Avoid collectLatest starvation under 60 Hz updates: throttle/conflate before launching grouping work. Cancel old work immediately for package/catalog/session changes. Validate the captured revision before publishing.
- Keep the last valid presentation while recomputing within the same snapshot/session. Clear it on package/region/session invalidation; show normal current objects until a valid clustered presentation is ready. Never show old-package overlays.
- Search for the next splitting scale only on a cluster click, not every frame. Do not cache a clicked result across changing metrics.
- Do not parse GeoJSON, read SQLite, or re-project all vertices inside composable marker content.

## 12. File-level implementation sequence

Paths below use `...` for the existing `src/commonMain/kotlin/bes/max/bmaps/<module-package>/` prefix; inspect the current files rather than assuming line numbers.

1. **Core camera contracts:** add `MapCameraSnapshot.kt`; extend `RasterMapConfig.kt` controller commands/results; implement snapshots, session tokens, bounded camera moves, cancellation, and completion in `RasterMapRenderer.kt`. Preserve all existing map clients.
2. **Pure cluster math:** create viewer `AnnotationClusterer.kt` with preparation, eligibility, spatial hash/union-find, placement, and split-scale search. Add deterministic common tests before connecting UI.
3. **Catalog and state integration:** extend `AnnotationEditorViewModel.kt` with the bounded full catalog loader, nested clustering state, revision guards, computation jobs, selection lookup, and member-list actions. Keep repository interfaces unchanged.
4. **Geometry/display mapping:** refactor `AnnotationLayers.kt` to share projection logic and emit normal overlays plus typed hit metadata. Never close a polygon twice or change saved vertex order. Retain the explicit domain `Annotation` import to avoid the Kotlin Annotation collision.
5. **Core overlay rendering:** extend `MapOverlays.kt` with default anchor/z-order fields and make `RasterMap.kt` synchronize overlays incrementally. Preserve existing trailing-lambda/positional call compatibility, or update every call site in viewer, constructor, fixtures, and tests.
6. **Viewer integration:** update ViewerScreen camera/density forwarding and the click dispatcher; add `AnnotationClusterBadge.kt`; add member-list content in `AnnotationEditorContent.kt`; add resources. Keep the renderer ViewModel-owned.
7. **Verification and docs:** complete the tests below, update `docs/9_MAP_ENGINE.md`, `docs/15_ANNOTATIONS_AND_GEOJSON.md`, `docs/4_STATE_AND_UI.md`, and the viewer ownership sentence in `docs/3_MODULES.md`. Record exactly what ran. Mark this plan implemented only when its approved behavior is delivered.

Do not invoke an OpenAI-specific skill merely because the implementation agent is named Luna. Do not change dependencies or Gradle convention plugins unless compilation demonstrates an actual missing requirement; existing coroutine test and Compose dependencies should suffice.

## 13. Required automated tests

### Pure clustering tests in viewer commonTest

1. Empty input and one object produce no clusters; two markers at 63 dp cluster; two at 65 dp do not; exactly 64 dp clusters.
2. Cross-cell neighbors cluster correctly. Input reordering produces identical membership and stable keys. Hash-colliding annotation IDs remain distinct.
3. A-B=50 dp, B-C=50 dp, A-C=100 dp produces one three-member component. Every eligible ID occurs exactly once across singleton and cluster outputs.
4. Density 1 and density 3 produce identical membership for the same dp layout and camera. Rectangular maps use independent X/Y factors; relative fit scale is converted correctly.
5. Hidden kinds and selected objects are excluded. Draft mode emits no clusters and retains vertex handles.
6. Small lines/polygons can cluster with markers. Shapes with diagonals 64 dp qualify, 65 dp do not. Region-crossing shapes stay drawn. A singleton polygon stays a filled polygon.
7. Increasing scale only separates components for unchanged inputs; shapes becoming large reappear as original paths. Panning changes badge placement/culling but never member sets/counts.
8. A long connected component with off-screen centroid still has a visible badge when a member intersects the viewport. Fully off-screen clusters are culled. Tiny viewport intersections produce finite in-bounds anchors.
9. Date-line regions do not cluster together; unsupported coordinates/projection levels never crash or corrupt geometry.
10. Expansion returns a strictly larger permitted scale at which the original component splits. Cover a split requiring much more than 2x, a shape exiting eligibility, threshold equality, and a maximum scale below the needed split.
11. Coincident markers return the member-list outcome; mixed coincident and distinct anchors may split into a smaller remaining cluster. No division by zero or infinite scale.

### Editor/state tests

1. Complete multi-page catalog loads once per open/mutation, not per pan. 1,000 complete objects cluster; 1,000 plus a next cursor disables clustering. Failed later page never publishes partial counts.
2. Catalog failure leaves successful viewport objects visible. Viewport success does not clear catalog failure. Retry reattempts both.
3. Switching packages or replacing the catalog discards delayed old load/computation results. Save/delete/import invalidates clusters; visibility and selection update counts.
4. A cluster member absent from the viewport page can be selected through the catalog. Deletion clears stale member-list/selection data.
5. Coincident cluster opens member list; row click selects exactly the requested object. Repeated taps while pending do not queue animations.
6. Gesture interruption, new camera control, resize, region/level change, and stale command completion clear pending state without a late dialog or zoom.

### Core tests and native interaction checks

1. Camera conversion and clamps: fit-relative to absolute scale, actual maximum, zoom-disabled config, invalid coordinates, stale session tokens, and cancellation. Test pure command-resolution math independently of native MapState.
2. Verify incremental synchronization with a fake overlay sink: unchanged overlays are not removed on viewport-only changes, changed/deleted ones are reconciled, and engine replacement/disposal cleans up exactly once.
3. Android/iOS native checks must verify actual cluster placement, single tap versus accessibility activation, 300 ms camera movement, touch interruption, count changes, and max-zoom member selection. Host math tests do not establish rendered behavior.

Suggested host commands after checking task availability:

```sh
./gradlew :feature:viewer:testAndroidHostTest :core:map-engine:testAndroidHostTest
./gradlew :domain:map-builder:testAndroidHostTest --tests '*AnnotationGeoJsonTest'
./gradlew :androidApp:assembleDebug :shared:compileKotlinIosArm64
```

The first command intentionally runs the existing viewer/core suites because controller and overlay changes affect callers beyond annotations. Do not suppress meaningful failures. Use existing device/simulator infrastructure for visual checks; report environmental blockers explicitly. Do not claim native checks passed when only host tests ran. Preserve the passing legacy polygon recovery tests.

## 14. Manual acceptance script

1. Create three close markers and one distant marker: see `3` and the distant original icon. Tap `3`: see a changed grouping at a closer camera scale.
2. Add a small polygon and a small line near the three markers: verify each adds one to the count and their paths disappear only while clustered. Zoom until the shapes are large: their original paths return.
3. Add a long route passing through the same area: it stays visible and is not counted just because its midpoint is nearby.
4. Hide markers/polygons/lines in turn: counts and paths change consistently. Enter drawing/editing: clusters disappear, every vertex can be tapped, and Undo/Cancel/Save retain existing behavior.
5. Create coincident markers: tapping their terminal cluster opens the member list; each is individually selectable/editable/deletable.
6. Pan around a cluster, rotate the device, change raster level/region, navigate away/back, and background/resume: no stale circles, duplicate paths, frozen clicks, or unrelated zooms.
7. Check dark/light theme and large font scale, screen-reader count and activation, one physical click/one action, and the offline/no-network behavior.
8. Test 1,000 versus more than 1,000 objects: exact counts for a complete snapshot; normal viewport rendering plus explicit fallback explanation for larger packages.

Completion means the approved product decisions, pure and state tests, camera command safeguards, and documented verification status are delivered. A visible circle alone is not sufficient.
