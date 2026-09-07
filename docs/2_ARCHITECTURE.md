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
* **Framework:** Compose Multiplatform strictly drives the UI.
* **State Management:** Screens must follow Unidirectional Data Flow (UDF) utilizing Model-View-Intent (MVI) or Model-View-ViewModel (MVVM) patterns. The UI observes a single immutable state stream and dispatches intents/events. For the most screens it's MVVM+.
* **Validation:** Data validation rules must be executed independently inside this presentation layer (e.g., within the ViewModel/Intent handler) before passing sanitized data downwards.

## 4. Domain Layer (Use Cases)
* **Responsibilities:** Encapsulate pure business logic (e.g., calculating required tile matrices for a bounding box).
* **Constraints:** Structural business use cases must maintain single responsibility. Do not pollute use cases with input validation logic; assume data passed from the presentation layer is already validated.

## 5. Data & Pers## 5. Data & Persistence Layer
* **Custom Pagination:** Do not use standardized pagination libraries (e.g., Paging 3). Pagination and historical list filtering must be implemented organically from the data layer upwards. This guarantees architectural flexibility when complex, multi-parameter queries are required for saved map projects.
* **Data Sources:** `core:database` acts as the single source of truth for app metadata. `core:mbtiles` acts as a dynamic source for map rendering.
* **Spatial Data & Annotations:** User-generated map data (markers, routes, polygons) is intentionally excluded from the central `core:database`. Instead, it is persisted in a localized `annotations.db` SQLite file inside the specific map's folder. This database acts as the spatial source of truth, enabling performant viewport querying while maintaining a schema that seamlessly serializes to/from the GeoJSON standard for cross-platform sharing.

## 6. Hardware & Transport Abstractions
Given the offline-first nature and the need for peer-to-peer export/import of heavy map packages:
* **Protocols:** The architecture must abstract data transport protocols. Interfaces in the `domain` layer will define generic package transfer operations.
* **Implementations:** Platform-specific implementations (e.g., managing byte streams over Bluetooth or local Wi-Fi (UDP) environments) will reside in the infrastructure layer (`core:network` or a dedicated `core:transport` module).

## 7. Build System
The configuration is completely abstracted into a `build-logic` composite build. Versioning is strictly controlled via Gradle Version Catalogs. Modules apply behavior via custom convention plugins (e.g., `app.kmp.library`, `app.compose.multiplatform`).