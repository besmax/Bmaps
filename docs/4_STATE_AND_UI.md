---
type: "state_and_ui_guidelines"
project: "Bmaps"
purpose: "Define the presentation layer architecture (MVVM+), state management constraints, and map-specific UI patterns."
---

# Bmaps: State and UI Architecture (MVVM+)

## 1. MVVM+ Presentation Architecture
The project strictly utilizes an enhanced Model-View-ViewModel (MVVM+) pattern for the presentation layer, driven by Compose Multiplatform.

Feature source packages group capabilities before separating `ui` and `presentation`; neither role appears directly under a feature root. ViewModels, immutable state, events and presentation validation stay together in the capability's `presentation`; composables and UI effects live in its `ui`. The package map is maintained in `3_MODULES.md`. This organization does not change navigation/dialog scope or introduce additional state owners.

* **ViewModel Granularity:** A single screen is not restricted to a single ViewModel.
    * Complex screens must be decomposed.
    * Complex, standalone UI widgets should be backed by their own ViewModels.
    * Heavy dialogs must be backed by their own ViewModels, strictly bound to the specific lifecycle of that dialog.
* **No Reducer Classes:** Do not over-engineer UDF (Unidirectional Data Flow). There are no separate `Reducer` classes in this architecture. State reduction and mutation logic must be implemented directly within the ViewModel using standard Kotlin functions or coroutine flows.

### Composable decomposition

Split large composables into separate logical components during development and refactoring. Every list must be implemented in its own composable, and each list item must be rendered by a separate item composable; this also applies to non-lazy lists such as filter rows. Separate substantial controls, cards, recovery/actions sections and dialogs rather than embedding their complete UI in a screen or list builder. Keep small related screen-local components in the same file so the screen remains easy to read. A separate composable does not imply a separate file: extract a file only when the component is substantial in size or is used from multiple files. Keep extracted feature-local files in the owning feature's `ui` package.

Screen entry points resolve ViewModels, observe state, handle lifecycle/event collection and connect callbacks. Extracted display components accept data and callbacks instead of resolving ViewModels or accessing repositories. Decomposition must retain the existing state owners, lazy item keys, scrolling, interaction rules and lifecycle behavior; extracting visual components alone does not require additional ViewModels.

## 2. State & Event Management

* **Single Immutable State:** Each ViewModel exposes exactly one immutable state object (e.g., `data class ScreenState`), exposed to Compose via a `StateFlow`.
* **One-Off Events:** Side effects (e.g., showing a snackbar, triggering a navigation route, displaying a toast) must be handled using Kotlin `Channel` and exposed to the UI as a `Flow` (via `receiveAsFlow()`). Do not use state variables to model one-off events.

## 3. Map-Specific UI Architecture
Map screens (like `feature:viewer` and `feature:constructor`) contain complex internal states independent of the standard screen UI. To manage this, the map presentation layer must be decomposed into dedicated layer classes.
* **Dedicated Layer Managers:** Implement distinct presentation classes for specific map elements, such as `MarkersLayer` and `RouteLayer`.
* **Responsibilities:** These layer classes are responsible for managing the localized state of map annotations, including:
    * Determining current visibility (showing/hiding routes).
    * Managing interactive element states (e.g., tracking whether a detailed popup widget should be displayed above a specific tapped marker).
* These layer classes interface with the underlying `MapComposeMP` engine but encapsulate the presentation logic so the primary ViewModel does not become a god-class.

## 4. UI Performance & Compose Stability
To prevent UI jank and frame drops during heavy map rendering operations:
* **Screen-owned presentation models:** Always map Domain/Data result models into display models owned by the screen/capability's `presentation` package before exposing them in observed UI state or passing them to composables. Map at the ViewModel boundary; do not rebuild display models inside composables. Related dialogs may reuse the owning capability's display models. Domain objects remain internal to repository/use-case interaction and presentation calculations; UI actions carry stable identifiers and values, converted to Domain commands at the boundary.
* **Strict immutability:** Presentation/state models use `val` fields and structurally immutable nested values. Prefer primitives, strings, stable enums, resources and other immutable presentation models. Collections must use `kotlinx.collections.immutable` types such as `ImmutableList`, `ImmutableSet`, `ImmutableMap` or their persistent variants. Copy incoming collections when mapping; Kotlin read-only `List`, `Set` and `Map` do not guarantee immutability. `app.feature` supplies the catalog-pinned collection dependency.
* **Truthful stability contracts:** Declare `@Immutable` for immutable display/state classes, or `@Stable` only when its observable mutation contract is actually satisfied. These annotations do not repair mutable fields, ordinary collections or nested unstable Domain models. Never annotate Domain classes merely to silence UI stability warnings. Use Compose Compiler Metrics when available to verify inferred stability and skippability.
* **Narrow child parameters:** A stable model still changes when one of its fields changes. Pass each child only the fields or immutable submodel it needs, and keep action callbacks tied to stable IDs rather than an entire changing item. A status change may recompose the card container and status-dependent controls while unchanged header/metadata children are skipped. Stable lazy keys retain item identity; they do not guarantee skipped recomposition.

## 5. Strict Agent Rules (Anti-Patterns)
* **DO NOT** create `Reducer`, `Store`, or `Middleware` classes. Keep the UDF loop simple within the ViewModel.
* **DO NOT** use `StateFlow` or `MutableState` for one-off events. Always use a `Channel` for events that should only be consumed once.
* **DO NOT** leak Domain or Data layer validation into business use cases. Input validation (e.g., checking if a user-defined map name is empty) must happen inside the ViewModel before calling down to the Domain layer.

## 6. Resources and map selection

Feature-specific labels, accessibility descriptions, and error messages belong in each feature's `commonMain/composeResources/values/strings.xml`. ViewModel error state carries a `StringResource` reference; composables resolve it with `stringResource` so locale changes do not require recreating error state. Parameterized messages use resource placeholders. Provider identities, attribution data, protocol values, and diagnostic exceptions remain data rather than UI translations.

Map renderer state, tile events, and zoom controls belong to the ViewModel-owned `RasterMapRenderer`, not composable `remember` blocks. Source menu visibility and link failures belong to `OnlineMapState`. The ViewModel runs the renderer in its own scope; observing UI lifecycle changes must not destroy the engine or decoded tiles. Compose retains only framework UI mechanisms such as scroll position and updated effect callbacks.

`AreaSelectionViewModel` owns the freehand trace and its normalized bounding rectangle. In selection mode, a single-finger stroke captures all sampled positions and shows the trace; releasing the finger produces an axis-aligned rectangle from the extrema. A new stroke replaces the selection. Cancelled strokes and zero-area selections cannot be accepted. The drawing overlay consumes map gestures, and zoom controls are hidden during selection. Geographic bounds are computed from the rectangle and visible world window. Accepted bounds are a snapshot, independent of subsequent drawing and map movement.

## 7. Shared visual components

`core:ui` owns reusable composables, theme definitions, fonts, and shared icons. Components accept immutable display values, localized accessibility descriptions, modifiers, and callbacks. They do not resolve ViewModels or access repositories; feature ViewModels retain interaction state and validation. Extract components when reuse is concrete, keeping feature-specific UI local. Shared component-owned resources belong in `core:ui`; feature wording remains in feature resources.

`MapIconButton` provides a themed translucent surface, a 20 dp icon, a configurable icon tint, and a minimum 48 dp layout size. `MapIcons` includes normal and crossed-out location symbols for availability feedback. Online and offline maps use it with `MapIcons` for Back, zoom, and offline layer controls; the constructor also supplies its own area-selection icon. `MapIcons` exposes shared drawable resources without exposing the generated resource class.

## Phase 8 annotation editing

`AnnotationEditorViewModel` is scoped to the viewer navigation entry independently of `ViewerViewModel`. It owns one immutable editor state and Channel-backed error events, with dedicated marker/route/polygon managers for visibility and selection. The viewer forwards renderer tap/viewport events without owning annotation persistence or edit history. Geometry validation runs in the editor before saves; drafts and Undo snapshots remain presentation state until a transactional repository mutation succeeds. Single-marker placement advances a session-only version in editor state; composition owns the growth animation, and draw-phase progress does not update the ViewModel or persisted annotation. See `15_ANNOTATIONS_AND_GEOJSON.md`.

`viewer.map.ui.ViewerScreen` resolves the navigation-scoped ViewModels, observes their state, and composes the screen. Feature-local blocks in `viewer.map.ui` are `ViewerScreenEffects.kt` (opening, lifecycle collectors, and renderer events), `ViewerControls.kt` (floating controls, bottom content, and retry), `ViewerDialogs.kt` (cluster selection, attribution, and package details), and `ViewerLayersDialog.kt` (zoom selection and layer appearance). `viewer.map.ui.ViewerMapContent` composes overlays, marker content, and object callouts using components from `annotations.ui` and `annotations.ui.icons`. These blocks reuse the existing ViewModels; extraction does not introduce another state owner.

Each layer snapshot contains an immutable map from annotation ID to `AnnotationObjectUiState`: callout visibility, the geographic click coordinate, marker movement, and delete confirmation. Layer managers own these interaction updates and clear them when hidden; catalog reconciliation prunes IDs that no longer exist. The editor stores no separate selected annotation or global movement/delete flags. Active-object data and rendering selection IDs are derived from layer interactions and the current catalog, with viewport items as the fallback. Opening a callout clears previous interactions across layers as an explicit single-callout policy. Edit, move, and delete actions target an object ID. Compose retains a display snapshot only while animating a departing card. This UI state is session-only and is never written to annotation storage.

The viewer clusters nearby markers, small lines, and small polygons from a complete package snapshot of up to 1,000 objects. Large or region-crossing shapes remain visible. Cluster badges carry exact member counts and open the member selection dialog; a selected object and active draft remain unclustered. Clustering is presentation state and is recomputed from camera snapshots; it is never persisted.

## Current-location widget

`core:location` provides an unscoped location widget ViewModel with one immutable state stream and Channel-backed events. Native updates attach only while the map entry is resumed. Map features consume Center events and own coverage checks, viewport changes, and the builder's single initial focus. Fresh fixes update overlays without moving the camera. The widget independently validates coordinates, reported accuracy, freshness, and replacement ordering; it permits an older measurement only within three seconds and only when accuracy strictly improves, supporting near-simultaneous multi-source corrections. Source fixture screens do not request permission or start sensors. See `20_CURRENT_LOCATION.md`.

## Elevation generation presentation — 2026-10-09

`ElevationProgressViewModel` is scoped to the viewer navigation entry and observes durable generation jobs independently of rendering. `ElevationReliefDialogViewModel` uses a separate dialog ViewModelStore, one immutable settings/submission state, and a Channel-backed successful-submission event. It independently validates manual meter ranges and finite palette positions in [0, 1]. Two independent palette handles select minimum/maximum shades, may cross or coincide, and preserve positions when changing presets. The dialog previews the selected gradient and loads legacy reversal as endpoint positions; new submissions persist explicit positions. Closing its store does not cancel an accepted Generate operation or already scheduled background generation. `ViewerViewModel` retains dialog visibility and reloads raster sessions on completion, using existing camera retention. See `21_ELEVATION_COLOR_LAYERS.md`; source changes are not runtime verified.


Annotation callout location (2026-10-10): `CalloutLocationViewModel` owns one immutable state for WGS-84 point, optional sampled height and saved angular format. The viewer supplies the marker coordinate or path callout anchor; a cancellable lookup samples the package DEM and closes its session. No package/network work occurs inside the callout composable. Dismissal animation retains a display snapshot. See `15_ANNOTATIONS_AND_GEOJSON.md` and `17_ELEVATION_READER.md`; runtime validation remains with the owner.


Saved annotation heights (2026-10-10): the editor independently validates geometry/height structure, then invokes the injected domain `AnnotationElevationResolver` before repository commits. Changed vertices invalidate stored heights; Undo restores the previous geometry/height pair. Callout height is taken from saved data at matching vertices, with package-local sampling for unknown/arbitrary click points. See `15_ANNOTATIONS_AND_GEOJSON.md`; new regression scenarios are authored but not executed.


## Library package verification presentation — 2026-10-11

`catalog.ui.PackageDetailsDialog` owns a separate ViewModelStore for `catalog.presentation.PackageVerificationViewModel`. The ViewModel exposes one immutable state containing the observed package summary, current verification stage/counters, running/stopping flags and a persistent localized result. Closing the store cancels verification and observation. Duplicate starts are ignored while running or stopping. The library retains the selected summary independently of its filtered page so a verification-induced readiness change does not remove the result dialog. This operation is foreground and dialog-owned; it does not become a durable background job. See `13_LIBRARY_AND_OFFLINE_VIEWER.md`.


Callout coordinate preferences (2026-10-11): `CalloutLocationViewModel` owns the display transformation independently of elevation sampling, combines point selection with CRS/format preferences, clears stale display results before cancellation/join and rejects superseded results by request version and point identity. `MapObjectCallout` receives the transformed point and localized loading/error state; it no longer constructs a WGS-84 display coordinate from the original anchor. See document 15. Added regression tests are not executed; builds and native testing remain with the user.

Library action menu: `LibraryViewModel` owns `LibraryState.actionsExpanded` and the screen wires Build/Import/dismiss callbacks. `LibraryActionFab` owns only transition progress, eased opening/closing motion, delayed content opacity and drawing paths. Its focusable popup closes on outside presses, Back or the root FAB, and selecting an action clears the menu state before navigation or document picking. The root FAB stays composed while its popup opens/closes; the popup owns only branches/actions and a transparent root hit target, with root accessibility supplied by the active surface. No new state owner or dependency is introduced.

Library catalog presentation: `LibraryViewModel` maps package summaries and build progress into `LibraryMapUi`/`LibraryDownloadUi` before updating `LibraryState`; `PackageVerificationViewModel` maps the observed summary and verification counters into the same capability-owned immutable display types. Lists, zoom levels and busy IDs use immutable/persistent collections. The card header receives only `LibraryMapHeaderUi`, while status, metadata, recovery and actions receive their own display values. Domain timestamps and unused download fields do not invalidate display equality. Favourite writes use a separate persistent `savingFavourites` set, affecting only the star button; the card's enabled state follows the general `busy` set and remains unchanged by favourite writes. Build/test execution and device recomposition acceptance remain with the user.
