# Feature Shell and DI Composition

Phase 2 introduced feature composition and preferences. Phase 6 replaces tab navigation with a home library, a constructor FAB, and package-specific offline viewer entries; see `13_LIBRARY_AND_OFFLINE_VIEWER.md`. Settings now uses a full-screen navigation destination with immediate, independent persistence instead of the original draft dialog.

## Module ownership

- `feature:shell` owns application chrome, theme state, navigation events, and the Settings screen. It depends on core infrastructure and accepts navigation callbacks; it never imports another feature. Its platform presentation adapters read the installed app version code.
- `feature:library`, `feature:constructor`, and `feature:viewer` own their screen UI, ViewModels, and navigation callbacks.
- `shared` composes a Navigation Compose host and connects feature callbacks. It contains no screen ViewModels, preference mutation, or business logic.
- `core:di` owns `AppScope` and the Metro ViewModel factory binding.
- `core:datastore` owns the preference contract, DataStore implementation, and platform DataStore construction.

All four features apply `app.feature`. Dependencies come from the version catalog. Navigation Compose 2.9.2 is used with the existing Compose stack; MetroX artifacts match the Metro plugin version. The Android UI test convention configures both host and device tests using the same navigation scenario.

Shell composition is in `bes.max.bmaps.feature.shell.navigation.ui`, shell state and events in `shell.navigation.presentation`, settings in `shell.settings.ui` / `shell.settings.presentation`, and licensing/version presentation in `shell.about.ui`. Common and platform app-version declarations use the same package. `shared` imports the UI entry points directly; Metro ViewModel contributions retain their existing visibility, annotations and scope. See `3_MODULES.md` for all feature packages.

## Graph and object lifetimes

Android creates one `AndroidAppGraph` lazily on `BmapsApplication`, registered in the application manifest. Its factory receives application context, never activity context. Recreating an activity reuses the graph and the DataStore instance.

iOS creates one `IosAppGraph` lazily for the application process and reuses it across `MainViewController` instances. It uses platform contributions from `core:datastore`.

Application-scoped repository, DataStore, and ViewModel factory bindings use `SingleIn(AppScope::class)`. Contributed ViewModel classes must be public so the umbrella graph can discover them across module boundaries. Feature-only state may remain internal. ViewModels are unscoped Metro map contributions; `metroViewModel()` resolves them through the current `ViewModelStoreOwner`. The root native owner retains `ShellViewModel`; the Settings navigation entry owns `PreferencesViewModel` and clears it when popped.

The DataStore artifact is an API dependency of `core:datastore` because its types appear in generated Metro factories used by the umbrella graph. The iOS adapter uses only Okio's path conversion required by DataStore's factory API; this is not an alternate application file-IO implementation. Custom application file IO remains assigned to `kotlinx-io-core`.

## Navigation and settings layout

The shell top bar displays the current destination label: **Your maps** for Library and **Build a map** for the constructor. These screens do not repeat their titles in the content below the bar. Settings owns its existing **Settings** top bar; full-screen maps retain their floating controls. Source/static verification only; builds and device/emulator acceptance remain with the user.

The root starts at Library. Its FAB action menu opens the constructor or native package import; ready package rows open a viewer carrying the package ID. There is no bottom navigation or saved tab stack. The top-bar Activities icon uses a root callback; the gear opens Settings at route `settings`. Settings is a navigation-scoped screen with immediate persistence, as described below.

`SettingsScreen` observes one immutable preference state. Portrait/narrow windows show a centered, scrollable stack of Appearance, map settings, and About cards. Landscape windows at least 600 dp wide use two independent scrolling columns: Appearance and About on the left, map settings on the right. Insets and a fixed top bar remain outside the scrolling content. Recreating or rotating the activity retains the navigation entry and ViewModel.

The appearance selector is a single-choice segmented control with **System**, **Light**, and **Dark**. Its buttons have a minimum 48 dp touch height. Existing map-object clustering, coordinate-system selection, and coordinate-format selection remain available. About is available even if settings fail to load.

## Independent persistence and failures

There is no Save or Cancel action and no draft-dismissal event. Each control immediately updates its field and starts its own DataStore edit through `setTheme`, `setClusterMapObjects`, `setDefaultCoordinateSystem`, or `setCoordinateFormat`. Each edit changes only its own key. The existing bulk `setDisplayPreferences` API remains available, but the Settings screen does not use it.

Only the setting currently being written is disabled; unrelated controls remain usable. A per-setting saving indicator reflects pending persistence. A failed write restores that field's latest persisted value and displays a message beside it; selecting the desired value again retries. Other successful changes remain applied. Failed reads show a retry action and prevent mutations until preferences are available. The ViewModel continuously observes persisted preferences, including a write completing after a previous Settings entry was closed.

Accepted writes begin immediately and finish in a non-cancellable persistence block, so an immediate Back action does not discard an already accepted selection. Loading/observation still follows the ViewModel lifetime. Process death before a write commits cannot promise persistence. There are no save-completion navigation effects to replay after rotation or backgrounding.

The shell observes persisted theme changes and applies the color scheme. Unknown stored theme values fall back to System without erasing other keys. Supported coordinate systems are WGS 84 (`EPSG:4326`), SK-42 / Pulkovo 1942 (`EPSG:4284`), and PZ-90.11 (`EPSG:9475`). Coordinate format applies independently: decimal degrees, degrees/minutes, or degrees/minutes/seconds. An unrelated settings change preserves future/custom persisted CRS identifiers. The viewer also supports existing EPSG:3857 data as labeled X/Y meters; native transformation acceptance is tracked separately.

Android stores preferences in the application's DataStore directory; iOS uses Application Support. Only one DataStore is created per file and process.

## About and visual system

The selectable **About Bmaps · Licensing** card retains the copyright, canonical repository URL, PolyForm Noncommercial license summary and URL, and commercial contact. It adds **Version code**, read from Android's installed `PackageInfo.longVersionCode` or iOS `CFBundleVersion`; it is not a hardcoded marketing version. Unavailable metadata gets an explicit fallback label. All of this works offline.

`feature:shell` bundles copies of `LICENSE`, `NOTICE`, and the commercial request policy in Compose resources; `scripts/license_headers.py` checks they match the root documents. This section identifies Bmaps itself, not a complete dependency-license inventory. See `18_LICENSING_AND_PROVENANCE.md`.

`core:ui/theme/BmapsTheme.kt` owns the palette, Inter font family, responsive typography, and shapes applied by AppShell. All destinations inherit the theme without feature-to-feature imports. See `10_BMAPS_DESIGN.md`.

## Verification handoff

Updated tests cover individual setting writes, independent pending writes, duplicate-submit prevention, failed-field rollback and retry, load failure, unknown-CRS preservation, persistence after leaving Settings, and continuous observation after reopening. The DataStore reopening test now exercises concurrent clustering and coordinate-format edits. The Android navigation scenario checks immediate theme changes, recreation, background/foreground, Back/reopen, and the absence of Save/Cancel.

Static syntax, resources, and notice checks do not establish compilation or runtime behavior. Follow the [verification policy](../AGENTS.md#verification-policy): agents select focused host tests; project builds and all device/emulator/simulator runs belong to the owner. The commands below include both host tests and owner-run build/native tasks; they are not a single agent checklist. Historical unrun results remain unverified.

Current focused host result: `./gradlew :androidApp:testDebugUnitTest --tests '*ShellNavigationHostTest'` compiles library/shared/application sources as test prerequisites, but the navigation scenario fails during activity-session initialization with `UnsatisfiedLinkError: no sqliteJni in java.library.path`. The host SQLite setup must be resolved before this scenario establishes navigation or FAB acceptance; no device/emulator/simulator result is implied.

```sh
./gradlew :core:datastore:testAndroidHostTest :feature:shell:testAndroidHostTest
./gradlew :androidApp:testDebugUnitTest --tests '*ShellNavigationHostTest'
./gradlew :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest
./gradlew :shared:compileKotlinIosArm64 :shared:linkDebugFrameworkIosSimulatorArm64
./gradlew :feature:shell:iosSimulatorArm64Test
./gradlew :androidApp:connectedDebugAndroidTest
```

Native acceptance: change all settings independently; verify live theme, coordinate display, and clustering after Back and relaunch. Rotate while writing, test portrait/landscape and narrow split windows, scroll both landscape columns, check large text/touch targets and system Back, and compare the About version code against the installed APK/IPA metadata. Confirm read/write failure recovery and that all licensing content remains visible offline.

## Activity navigation

The library top-bar Activities icon and track menu use callbacks wired by the umbrella to viewer-owned destinations. No feature imports another feature. Activity screens are fullscreen with their own controls/top bars. Navigation entries own presentation only; opening/closing lists, maps or details never starts or stops recording.

| Destination | Entry point / responsibility |
| --- | --- |
| `activities` | ActivityListScreen: completed pages and a separate current-owner card; new recording returns to map selection |
| `viewer/{packageId}/track` | ViewerScreen with dedicated tracking presentation after successful Start; ordinary map Track icons return to the active source map |
| `track-map` | Active-owner recovery entry and Android activity-notification target, resolving persisted source-map context |
| `activity/{activityId}` | ActivityDetailsScreen with source-map lookup/vector fallback |
| `viewer/{packageId}/activity/{activityId}` | ActivityDetailsScreen with supplied package context; successful foreground Finish opens the same details UI |

The Android host forwards a one-off activity-notification open request to the umbrella navigation wiring. Both initial and new intents select `track-map`; consumption clears the request, while an unconsumed request survives Activity recreation. Notification content/control contracts belong to [phone recording](22_TRACK_MODE_AND_TELEMETRY.md#88-phone-recording-and-recovery).

Activity policy, rendering fallback and mutation/export behavior stay in viewer/domain code. [Document 22](22_TRACK_MODE_AND_TELEMETRY.md#810-map-preparation-and-tracking-ui) owns the detailed workflow; its [status section](22_TRACK_MODE_AND_TELEMETRY.md#9-current-state-and-evidence) owns build/test/native acceptance state.
