# Feature Shell and DI Composition

Phase 2 replaces the template UI with library, constructor, and viewer destinations and a working preferences dialog. The map destinations remain placeholders; this phase does not implement map rendering, library queries, or downloads.

## Module ownership

- `feature:shell` owns application chrome, theme state, navigation events, and preferences presentation. It depends on core infrastructure and accepts content callbacks; it never imports another feature.
- `feature:library`, `feature:constructor`, and `feature:viewer` own their placeholder UI and expose navigation callbacks. They do not need ViewModels until they acquire screen behavior/state.
- `shared` composes a Navigation Compose host and connects the feature callbacks. It contains no screen ViewModels, preference mutation, or business logic.
- `core:di` owns `AppScope` and the Metro ViewModel factory binding.
- `core:datastore` owns the preference contract, DataStore implementation, and platform DataStore construction.

All four features apply `app.feature`. Dependencies come from the version catalog. Navigation Compose 2.9.2 is used with the existing Compose stack; MetroX artifacts match the Metro plugin version. The Android UI test convention configures both host and device tests using the same navigation scenario.

## Graph and object lifetimes

Android creates one `AndroidAppGraph` lazily on `BmapsApplication`, registered in the application manifest. Its factory receives application context, never activity context. Recreating an activity reuses the graph and the DataStore instance.

iOS creates one `IosAppGraph` lazily for the application process and reuses it across `MainViewController` instances. The graph uses the platform contributions from `core:datastore`.

Application-scoped repository, DataStore, and ViewModel factory bindings use `SingleIn(AppScope::class)`. ViewModels are unscoped Metro map contributions; `metroViewModel()` resolves them through the current `ViewModelStoreOwner`. The root native owner retains `ShellViewModel`, while the preferences navigation dialog owns `PreferencesViewModel` and clears it when popped.

The DataStore artifact is an API dependency of `core:datastore` because its types appear in generated Metro factories used by the umbrella graph. The iOS adapter uses only Okio's path conversion required by DataStore's factory API; this is not an alternate application file-IO implementation. Custom application file IO remains assigned to `kotlinx-io-core`.

## Navigation, state, and effects

The root starts at Library. Bottom navigation uses single-top destinations with saved/restored back stacks. Feature callbacks are converted into `ShellEvent` values by `ShellViewModel`; the shell consumes them while RESUMED and the umbrella performs the corresponding navigation operation. This keeps navigation events out of durable screen state.

Preferences is a navigation dialog destination, not a Boolean attached to the shell's ViewModel. Its lifetime therefore follows dismissal, back navigation, and restoration. A draft survives ordinary activity recreation through its retained ViewModel. Dismissing without Save discards the draft; reopening reads persisted preferences. Uncommitted drafts are not promised to survive process death.

Each ViewModel exposes a single immutable StateFlow. Navigation and save-completion effects use buffered Channels exposed by `receiveAsFlow()`. Each UI effect stream has one collector, scoped with `repeatOnLifecycle(RESUMED)`. Recomposition updates callbacks without recreating event state. Collector suspension does not replay already consumed events; pending events can be received on resume. These are in-process effects, not durable delivery guarantees across process death.

Save disables repeat submission, persists the selected theme, and then emits one completion event to dismiss the dialog. Failed reads/writes remain visible and retryable. Cancellation propagates normally; clearing a dialog owner cancels its ViewModel work. As with any durable write, cancelling the UI after a write commits does not undo that commit.

## Preferences

The supported appearance choices are device setting, light, and dark. The shell observes successful persisted changes and applies the selected color scheme. Unknown stored theme values fall back to device setting without erasing other keys.

`defaultCoordinateSystem` defaults to `EPSG:4326`. It has a persistence API and appears as WGS 84 in the dialog. Selection of other coordinate systems is deferred until Phase 9 supplies verified transformations. Theme updates preserve the coordinate identifier, including future/custom values.

Android stores the file in the application's DataStore directory. iOS uses Application Support. Only one DataStore is created per file and process. Preference failures are surfaced rather than silently replacing stored preferences with defaults.

## Verification commands

```sh
./gradlew :core:datastore:testAndroidHostTest :feature:shell:testAndroidHostTest
./gradlew :androidApp:testDebugUnitTest --tests '*ShellNavigationHostTest'
./gradlew :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest
./gradlew :shared:compileKotlinIosArm64 :shared:linkDebugFrameworkIosSimulatorArm64
./gradlew :feature:shell:iosSimulatorArm64Test
./gradlew :androidApp:connectedDebugAndroidTest
```

Host coverage includes real DataStore file reopening, preservation of unrelated keys, load/save failures and retry, cancellation on ViewModel clearing, discarded dialog drafts, live theme observation, and single delivery across event-collector restart. The shared Android UI scenario checks navigation, activity recreation, dialog draft retention, background/foreground transitions, save completion, reopening, and Cancel behavior. It is runnable under Robolectric or on an Android device.

Native smoke checks should open each destination, save a theme, dismiss/reopen preferences, background/foreground the app, and relaunch to verify persisted appearance. Runtime/device results are tracked in the implementation plan separately from compilation.
