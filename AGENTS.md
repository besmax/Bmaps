---
type: "agent_conventions"
project: "Bmaps"
purpose: "Strict architectural and coding rules for AI agents and human developers."
---

# Project Documentation
Read only the documentation relevant to the current task before making changes. Do not read every file in `docs/` by default. Use targeted searches and relevant sections to establish context; expand reading only when the task affects another area or a referenced contract is needed.

Use this guide to select documentation, not as a mandatory reading checklist:

| Task area | Relevant documentation |
| --- | --- |
| Product scope and requirements | `docs/1_VISION_AND_PRODUCT.md` |
| Architecture, dependencies, module creation or ownership | `docs/2_ARCHITECTURE.md`, `docs/3_MODULES.md` |
| UI state, ViewModels and events | `docs/4_STATE_AND_UI.md` |
| Phase work, current status and acceptance criteria | `docs/5_IMPLEMENTATION_PLAN.md` |
| Domain contracts and package format | `docs/6_CONTRACTS_AND_PACKAGE_FORMAT.md` |
| Feature shell, navigation and application composition | `docs/7_FEATURE_SHELL.md` |
| Providers, networking and credentials | `docs/8_PROVIDER_NETWORKING_AND_CREDENTIALS.md` |
| Map rendering and coordinates | `docs/9_MAP_ENGINE.md` |
| Visual design and styling | `docs/10_BMAPS_DESIGN.md` |
| Package storage, MBTiles and reconciliation | `docs/11_PACKAGE_STORAGE.md` |
| Download execution, scheduling, progress and restore | `docs/12_DOWNLOAD_PIPELINE.md` |

For tasks spanning multiple areas, read the relevant sections from each affected area. Discover additional documents with targeted filename or text searches when needed. Reuse context already read in the conversation unless it has changed or needs verification. Update affected documentation when behavior, contracts, or implementation status changes.

# Bmaps: AI Agent Core Conventions

## 1. Commenting and Documentation
* **Minimal Comments:** Write comments *only* when absolutely necessary to explain complex, non-obvious logic or the "why" behind a specific architectural decision. If the code is self-explanatory, do not leave any comments.
* **English Only:** All comments, commit messages, and documentation must be written strictly in English.

## 2. Architectural Boundaries
* **Presentation Layer Validation:** Data validation rules must be invoked independently inside the presentation layer (UI/ViewModel). Do not embed data validation logic inside structural business use cases. Use cases must maintain single responsibility.
* **Data Layer Pagination:** Pagination and filtering must be custom-built directly within the data layer (`core:database`). This ensures maximum architectural flexibility for complex filters. Do not use standard generic pagination libraries (e.g., Paging 3).
* **Umbrella Module:** The `:shared` module acts exclusively as an umbrella module to assemble the Metro DI graph and expose platform entry points. It must not contain business logic.
* **Framework Generation:** Do not configure iOS static `.framework` generation in base convention plugins. Framework compilation is strictly reserved for the umbrella `:shared` module to prevent duplicate artifacts and build conflicts.

## 3. Modularity and Build System
* **Build-Logic:** All Gradle configuration must be managed through the `build-logic` composite build using Kotlin DSL convention plugins. Feature and core modules must apply these plugins rather than declaring manual configurations.
* **Dependency Management:** Dependencies must be resolved exclusively via the central Gradle Version Catalog.
* **Module Hierarchy:**
    * `app` depends on `feature`, `domain`, `core`.
    * `feature` depends on `domain`, `core`. Feature modules cannot depend on other feature modules.
    * `domain` depends on `core`.
    * `core` modules are isolated infrastructure. Cross-imports within `core` should be strictly minimized.

## 4. Tech Stack Constraints
* **UI:** Compose Multiplatform for all UI rendering.
* **Map Engine:** MapComposeMP for tile rendering.
* **Network:** Ktor Client with platform-specific engines, specifically OkHttp for Android and Darwin for iOS.
* **Storage:** Room KMP for primary application metadata; custom SQLite driver for `.mbtiles` read/write operations.
* **DI:** Metro DI (`dev.zacsweers.metro`). Use global scopes and avoid manual dependency passing.
* **File IO:** `kotlinx-io-core` for byte stream manipulation and cross-platform file handling.
