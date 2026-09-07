---
type: "agent_conventions"
project: "Bmaps"
purpose: "Strict architectural and coding rules for AI agents and human developers."
---

# Project Documentation
Before proposing any architectural changes, creating new modules, or refactoring the application, you MUST read and analyze the detailed context files located in the `docs/` directory:
- `docs/1_VISION_AND_PRODUCT.md`
- `docs/2_ARCHITECTURE.md`
- `docs/3_MODULES.md`
- `docs/4_STATE_AND_UI.md`

# Bmaps: AI Agent Core Conventions

## 1. Commenting and Documentation
* **Minimal Comments:** Write comments *only* when absolutely necessary to explain complex, non-obvious logic or the "why" behind a specific architectural decision. If the code is self-explanatory, do not leave any comments.
* **English Only:** All comments, commit messages, and documentation must be written strictly in English.

## 2. Architectural Boundaries
* **Presentation Layer Validation:** Data validation rules must be invoked independently inside the presentation layer (UI/ViewModel). Do not embed data validation logic inside structural business use cases. Use cases must maintain single responsibility.
* **Data Layer Pagination:** Pagination and filtering must be custom-built directly within the data layer (`core:database`). This ensures maximum architectural flexibility for complex filters. Do not use standard generic pagination libraries (e.g., Paging 3).
* **Umbrella Module:** The `:shared` module acts exclusively as an umbrella module to assemble the Metro DI graph and expose platform entry points. It must not contain business logic.
* **Framework Generation:** Do not configure iOS static `.framework` generation in base convention plugins. Framework compilation is strictly reserved for the umbrella `:shared` module to prevent duplicate artifacts and build conflicts[cite: 1].

## 3. Modularity and Build System
* **Build-Logic:** All Gradle configuration must be managed through the `build-logic` composite build using Kotlin DSL convention plugins. Feature and core modules must apply these plugins rather than declaring manual configurations.
* **Dependency Management:** Dependencies must be resolved exclusively via the central Gradle Version Catalog.
* **Module Hierarchy:**
    * `app` depends on `feature`, `domain`, `core`.
    * `feature` depends on `domain`, `core`. Feature modules cannot depend on other feature modules.
    * `domain` depends on `core`.
    * `core` modules are isolated infrastructure. Cross-imports within `core` should be strictly minimized.

## 4. Tech Stack Constraints
* **UI:** Compose Multiplatform for all UI rendering[cite: 3].
* **Map Engine:** MapComposeMP for tile rendering[cite: 3].
* **Network:** Ktor Client with platform-specific engines, specifically OkHttp for Android and Darwin for iOS[cite: 1].
* **Storage:** Room KMP for primary application metadata; custom SQLite driver for `.mbtiles` read/write operations.
* **DI:** Metro DI (`dev.zacsweers.metro`). Use global scopes and avoid manual dependency passing[cite: 1].
* **File IO:** `kotlinx-io-core` for byte stream manipulation and cross-platform file handling[cite: 3].