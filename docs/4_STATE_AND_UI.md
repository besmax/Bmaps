---
type: "state_and_ui_guidelines"
project: "Bmaps"
purpose: "Define the presentation layer architecture (MVVM+), state management constraints, and map-specific UI patterns."
---

# Bmaps: State and UI Architecture (MVVM+)

## 1. MVVM+ Presentation Architecture
The project strictly utilizes an enhanced Model-View-ViewModel (MVVM+) pattern for the presentation layer, driven by Compose Multiplatform.

* **ViewModel Granularity:** A single screen is not restricted to a single ViewModel.
    * Complex screens must be decomposed.
    * Complex, standalone UI widgets should be backed by their own ViewModels.
    * Heavy dialogs must be backed by their own ViewModels, strictly bound to the specific lifecycle of that dialog.
* **No Reducer Classes:** Do not over-engineer UDF (Unidirectional Data Flow). There are no separate `Reducer` classes in this architecture. State reduction and mutation logic must be implemented directly within the ViewModel using standard Kotlin functions or coroutine flows.

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
* **Strict Immutability:** Ensure all data classes used in the State are structurally immutable. Use `val` for all properties.
* **Stability:** Utilize Compose Compiler Metrics to evaluate class stability. If a data class comes from a separate module (e.g., `domain`) and is inferred as unstable, wrap it or annotate it appropriately in the presentation layer to prevent unnecessary recompositions of the map or UI overlays.

## 5. Strict Agent Rules (Anti-Patterns)
* **DO NOT** create `Reducer`, `Store`, or `Middleware` classes. Keep the UDF loop simple within the ViewModel.
* **DO NOT** use `StateFlow` or `MutableState` for one-off events. Always use a `Channel` for events that should only be consumed once.
* **DO NOT** leak Domain or Data layer validation into business use cases. Input validation (e.g., checking if a user-defined map name is empty) must happen inside the ViewModel before calling down to the Domain layer.

## 6. Resources and map selection

User-facing labels, accessibility descriptions, and error messages belong in each feature's `commonMain/composeResources/values/strings.xml`. ViewModel error state carries a `StringResource` reference; composables resolve it with `stringResource` so locale changes do not require recreating error state. Parameterized messages use resource placeholders. Provider identities, attribution data, protocol values, and diagnostic exceptions remain data rather than UI translations.

Map renderer state, tile events, and zoom controls belong to the ViewModel-owned `RasterMapRenderer`, not composable `remember` blocks. Source menu visibility and link failures belong to `OnlineMapState`. The ViewModel runs the renderer in its own scope; observing UI lifecycle changes must not destroy the engine or decoded tiles. Compose retains only framework UI mechanisms such as scroll position and updated effect callbacks.

`AreaSelectionViewModel` owns a normalized selection rectangle and computes bounds from that rectangle and the visible world window. Dragging inside the rectangle moves it; dragging a corner resizes it. Movement stays within the viewport and resizing enforces a minimum extent. Areas outside the frame pass gestures to the map. Each corner has a 48 dp touch target and directional accessibility actions. Accepted bounds are a snapshot, independent of subsequent drags and map movement.
