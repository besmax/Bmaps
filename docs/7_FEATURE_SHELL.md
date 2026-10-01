# Feature Shell and DI Composition

Phase 2 introduced feature composition and preferences. Phase 6 replaces tab navigation with a home library, a constructor FAB, and package-specific offline viewer entries; see `13_LIBRARY_AND_OFFLINE_VIEWER.md`. Settings now uses a full-screen navigation destination with immediate, independent persistence instead of the original draft dialog.

## Module ownership

- `feature:shell` owns application chrome, theme state, navigation events, and the Settings screen. It depends on core infrastructure and accepts navigation callbacks; it never imports another feature. Its platform presentation adapters read the installed app version code.
- `feature:library`, `feature:constructor`, and `feature:viewer` own their screen UI, ViewModels, and navigation callbacks.
- `shared` composes a Navigation Compose host and connects feature callbacks. It contains no screen ViewModels, preference mutation, or business logic.
- `core:di` owns `AppScope` and the Metro ViewModel factory binding.
- `core:datastore` owns the preference contract, DataStore implementation, and platform DataStore construction.

All four features apply `app.feature`. Dependencies come from the version catalog. Navigation Compose 2.9.2 is used with the existing Compose stack; MetroX artifacts match the Metro plugin version. The Android UI test convention configures both host and device tests using the same navigation scenario.

## Graph and object lifetimes

Android creates one `AndroidAppGraph` lazily on `BmapsApplication`, registered in the application manifest. Its factory receives application context, never activity context. Recreating an activity reuses the graph and the DataStore instance.

iOS creates one `IosAppGraph` lazily for the application process and reuses it across `MainViewController` instances. It uses platform contributions from `core:datastore`.

Application-scoped repository, DataStore, and ViewModel factory bindings use `SingleIn(AppScope::class)`. Contributed ViewModel classes must be public so the umbrella graph can discover them across module boundaries. Feature-only state may remain internal. ViewModels are unscoped Metro map contributions; `metroViewModel()` resolves them through the current `ViewModelStoreOwner`. The root native owner retains `ShellViewModel`; the Settings navigation entry owns `PreferencesViewModel` and clears it when popped.

The DataStore artifact is an API dependency of `core:datastore` because its types appear in generated Metro factories used by the umbrella graph. The iOS adapter uses only Okio's path conversion required by DataStore's factory API; this is not an alternate application file-IO implementation. Custom application file IO remains assigned to `kotlinx-io-core`.

## Navigation and settings layout

The root starts at Library. Its FAB opens the constructor, and ready package rows open a viewer entry carrying the package ID. There is no bottom navigation or saved tab stack. The top-bar gear opens **Settings** at route `settings`. It is a normal composable destination with its own top app bar and Back button; the outer shell hides its chrome and insets for this destination. Back returns to the previous entry. Feature callbacks become Channel-backed `ShellEvent` values; the shell consumes them while RESUMED and the umbrella performs navigation.

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

Static syntax, resources, and notice checks do not establish compilation or runtime behavior. Builds, automated test execution, and visual/device acceptance remain assigned to the project owner.

```sh
./gradlew :core:datastore:testAndroidHostTest :feature:shell:testAndroidHostTest
./gradlew :androidApp:testDebugUnitTest --tests '*ShellNavigationHostTest'
./gradlew :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest
./gradlew :shared:compileKotlinIosArm64 :shared:linkDebugFrameworkIosSimulatorArm64
./gradlew :feature:shell:iosSimulatorArm64Test
./gradlew :androidApp:connectedDebugAndroidTest
```

Native acceptance: change all settings independently; verify live theme, coordinate display, and clustering after Back and relaunch. Rotate while writing, test portrait/landscape and narrow split windows, scroll both landscape columns, check large text/touch targets and system Back, and compare the About version code against the installed APK/IPA metadata. Confirm read/write failure recovery and that all licensing content remains visible offline.
