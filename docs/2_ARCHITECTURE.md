---
type: "architecture_definition"
project: "Bmaps"
purpose: "Define the macro-level multi-module architecture, dependency flow, and state management rules."
---

# Bmaps: Architectural Guidelines

## 1. Macro Architecture & Dependency Flow
The project follows a strict multi-module Unidirectional Dependency Rule:
`App (Native) -> Umbrella (:shared) -> Feature -> Domain -> Core`

* **Higher layers** can depend on any lower layers.
* **Lower layers** (like `core`) cannot depend on or know about higher layers.
* **Sibling modules** within the `feature` layer cannot depend on each other. Navigation and data sharing between features must be routed through the `domain` layer or the root Umbrella graph.

## 2. The Umbrella Pattern (`:shared`)
The `:shared` module is not a standard library module. It is the **Umbrella Module**.
* **Responsibilities:** Assembles the global Metro DI graph, connects feature interfaces to their implementations, and exposes the root Compose Multiplatform UI nodes (`MainViewController` for iOS, root `@Composable` for Android).
* **Constraints:** Must contain ZERO business logic. Static iOS `.framework` compilation is exclusively configured here to prevent artifact duplication.

## 3. Presentation Layer (Feature Modules)
* **Framework:** Compose Multiplatform drives all UI except the Apple Watch companion's watchOS UI, for which the project owner approved SwiftUI on 2026-10-12. This exception does not authorize native phone screens. Shared Compose visual components and theme definitions belong in `core:ui`; screens, ViewModels, and feature-specific components remain in their owning feature. `core:ui` is UI infrastructure with no domain or feature dependencies. Production watch integration is the final track-mode implementation phase; see document 22.
* **Display Models:** Every screen/capability maps Domain/Data result models into structurally immutable, Compose-stable presentation models before exposing display state. Nested collections use immutable collection types; annotations alone do not make unstable fields safe. Child components receive only the display values they need. See document 4.
* **State Management:** Stateful presentation follows MVVM+ as specified in document 4. Each ViewModel exposes one immutable state stream and a Channel-backed Flow for one-off events. Stateless placeholder screens may expose callbacks without an otherwise unnecessary ViewModel.
* **Validation:** Data validation rules must be executed independently inside this presentation layer (e.g., within the ViewModel/Intent handler) before passing sanitized data downwards.

## 4. Domain Layer (Use Cases)
* **Responsibilities:** Encapsulate pure business logic (e.g., calculating required tile matrices for a bounding box).
* **Constraints:** Structural business use cases must maintain single responsibility. Do not pollute use cases with input validation logic; assume data passed from the presentation layer is already validated.

## 5. Data & Persistence Layer
* **Custom Pagination:** Do not use standardized pagination libraries (e.g., Paging 3). Pagination and historical list filtering must be implemented organically from the data layer upwards. This guarantees architectural flexibility when complex, multi-parameter queries are required for saved map projects.
* **Data Sources:** `core:database` acts as the single source of truth for app metadata. `core:mbtiles` acts as a dynamic source for map rendering.
* **Spatial Data & Annotations:** User-generated map data (markers, routes, polygons) is intentionally excluded from the central `core:database`. Instead, it is persisted in a localized `annotations.db` SQLite file inside the specific map's folder. This database acts as the spatial source of truth, enabling performant viewport querying while maintaining a schema that seamlessly serializes to/from the GeoJSON standard for cross-platform sharing.

### Activity storage and recording boundary — T0–T3, 2026-10-12

`domain:activity` owns route/activity/telemetry contracts, pure lifecycle policy, independent validation, detailed GPX import, preview geometry, and repository adapters. Existing package annotations keep their ownership above. Application-owned reusable routes, activity route snapshots, and recorded samples occupy independent private `activities/activity.sqlite` and `routes/routes.sqlite` stores through `core:storage`. Room schema 4 adds catalog/history metadata as a repairable projection. Activity state, events, samples, the active-owner slot, command deduplication, and checkpoints commit together in the activity database; snapshots are stored separately from frequently updated headers. Core adapters never import domain types. T1 storage, T2 recording, T3 navigation, T4 viewer integration and T5 history/review/export source are present; native builds/tests and migration/background acceptance remain unrun. `domain:activity` owns the single phone session, control/flush/statistics policy and platform foreground-service bridges. `core:location` owns shared native subscription distribution and background sensor configuration without domain imports. The umbrella binds the session/clock and exposes native service graph entry points; it contains no recording business policy. T3 route indexing/matching, navigation controls/alert policy and recorded-return preparation stay in `domain:activity`. The initial activity SQLite schema 1 privately owns immutable return snapshots with atomic header/event/control commits; routes remain schema 1 and Room remains schema 4. Render previews never substitute for full navigation geometry. T4 viewer-owned activity presentation maps domain state into immutable models and owns controls, route preparation/import, transient foreground alerts and follow/free camera state. Clearing that navigation entry does not stop the application session. T5 history/review remains viewer-owned, maps source telemetry into bounded immutable chart/path models, and composes the same viewer without package context. The domain exporter streams immutable route geometry and revision-checked activity data; core sharing owns private temporary files and native sheets. The umbrella only wires destinations. See [22_TRACK_MODE_AND_TELEMETRY.md](22_TRACK_MODE_AND_TELEMETRY.md) for contracts and reconciliation rules.

## 6. Hardware & Transport Abstractions
Given the offline-first nature and the need for peer-to-peer export/import of heavy map packages:
* **Protocols:** The architecture must abstract data transport protocols. Interfaces in the `domain` layer will define generic package transfer operations.
* **Implementations:** Platform-specific implementations (e.g., managing byte streams over Bluetooth or local Wi-Fi (UDP) environments) will reside in the infrastructure layer (`core:network` or a dedicated `core:transport` module).

## 7. Build System
The configuration is completely abstracted into a `build-logic` composite build. Versioning is strictly controlled via Gradle Version Catalogs. Modules apply behavior via custom convention plugins (e.g., `app.kmp.library`, `app.compose.multiplatform`).

Unified routes (2026-10-13): manually drawn package lines and application-owned reusable routes share the Map objects route list and activity preparation actions. Inbound GPX/GeoJSON parsing/confirmation belongs to viewer `annotations.presentation.MapObjectsViewModel`; the activity codec returns waypoint DTOs without introducing a sibling domain dependency. Mixed imports explicitly coordinate two transactional stores with partial-result/retry state rather than cross-database atomicity. Full route geometry stays independent of bounded display previews. Schema versions, package ownership and frozen activity snapshots are unchanged.

Phone tracking/map ownership update (2026-10-16): the current contract supersedes the earlier app-owned user-route design. Detailed routes use the registered map `routes.json` asset through a core `PackageRouteStore` interface implemented under package locks; Room filters package-prefixed summaries without a schema change. Independent activity records persist their source map and frozen route snapshots; a shared Metro reservation/durable metadata guard protects active-map deletion. `viewer/{packageId}/track` reuses the map with dedicated controls, foreground location during pause, navigator arrow, free-area follow and explicit pan/recenter handling. Legacy unassigned routes can be copied into a selected map. Activity/route spatial versions stay 1, Room stays 4, Watch stays T7. The source increment has static verification only; builds/tests/native/performance acceptance remain with the owner. Current detailed contracts, limits, evidence and handoff are in `22_TRACK_MODE_AND_TELEMETRY.md` section 8.12.
