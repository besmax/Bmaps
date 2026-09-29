# Bmaps

An offline-first map constructor and viewer for Android and iOS, built with Kotlin and Compose Multiplatform.

## License and attribution

Copyright © 2026 Maksim Bespalov. Canonical project: <https://github.com/besmax/Bmaps>.

Original Bmaps material is available under [PolyForm Noncommercial 1.0.0](LICENSE). When distributing any part of the software, preserve the [required notices](NOTICE) and provide the license text or URL. Include the applicable notices when reusing source fragments. Third-party material retains its [own licenses](THIRD-PARTY-NOTICES.md).

For uses outside the public license's permissions, contact **bespalov.m.9@gmail.com**. Additional permissions and fees are agreed individually in a separate written agreement; see [commercial licensing requests](COMMERCIAL-LICENSE.md). Uses already permitted by the public license require no separate agreement. This is source-available software with noncommercial restrictions.

See [contribution requirements](CONTRIBUTING.md) and [provenance and release-signing guidance](docs/18_LICENSING_AND_PROVENANCE.md). Run `python3 scripts/license_headers.py` to check source notices and packaged license documents.

The current implementation includes provider/package contracts, feature navigation, Metro dependency injection, persisted appearance preferences, provider tile networking, and encrypted credential storage. The constructor now displays an online OSM map with attribution, persistent HTTP caching, source selection, and recoverable errors. Thunderforest supports secure API-key entry; other providers remain gated by the prerequisites documented below. Downloads and package management are not implemented.

## Project structure

- `androidApp` and `iosApp`: native application hosts.
- `shared`: Metro graph assembly, feature navigation composition, and platform entry points. Only this module generates the static iOS framework.
- `feature`: shell, constructor, library, and viewer presentation. Features do not depend on other features.
- `domain`: provider registry/online tile adapters and package/build contracts.
- `core`: infrastructure and renderer-independent contracts.
- `build-logic`: Gradle convention plugins, with dependencies managed by `gradle/libs.versions.toml`.

Read [AGENTS.md](AGENTS.md) and the [documentation](docs/5_IMPLEMENTATION_PLAN.md) before extending the architecture. See [feature shell details](docs/7_FEATURE_SHELL.md) for graph lifetimes and preferences behavior, and [provider networking details](docs/8_PROVIDER_NETWORKING_AND_CREDENTIALS.md) for networking, caching, online constructor behavior, provider availability, and verification. [Map engine documentation](docs/9_MAP_ENGINE.md) covers coordinates, renderer limits, ownership, and Phase 3B checks.

## Build and run

Use Android Studio with the configured JDK/Android SDK to run `androidApp`, or build its APK:

```sh
./gradlew :androidApp:assembleDebug
```

On macOS, open `iosApp/iosApp.xcodeproj` in Xcode, choose a simulator or provisioned device, and run the `iosApp` scheme. Its build phase compiles and embeds the shared framework. To verify framework linking directly:

```sh
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64
```

## Verify the shell

```sh
./gradlew :core:datastore:testAndroidHostTest :feature:shell:testAndroidHostTest :androidApp:testDebugUnitTest
./gradlew :androidApp:connectedDebugAndroidTest
```

The first command runs persistence, ViewModel, and Robolectric UI checks without an emulator. The second runs the shared navigation/recreation scenario on a connected Android device or emulator.
