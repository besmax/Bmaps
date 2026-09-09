# Provider Networking and Credentials

Phase 3A implements provider registration, URL construction, bounded tile HTTP, and encrypted credential persistence. Phase 3B adds the MapComposeMP renderer wrapper; Phase 3C connects live sources and credential input to the constructor. No map request starts when the application shell opens.

## Ownership and composition

- `domain:providers` owns `ProviderRegistry`, provider-specific URL builders, `TileRequestPolicy`, `OnlineTileSourceFactory`, and the adapter from raw HTTP results to `TileReadResult`.
- `core:network` owns the application-scoped Ktor client and `HttpTransport`. Android uses OkHttp; iOS uses Darwin. It imports no provider or map types.
- `core:datastore` owns `ProviderCredentials`, ciphertext persistence, and platform encryption. Its existing `core:di` dependency supplies Metro scopes and bindings.
- `core:map-engine` owns `TileSource`, the KMP `TileStreamProvider`, and `TileSourceStreamProvider`. No renderer types enter provider APIs.
- `shared` assembles/exposes these services through the Metro graph. Feature code will inject services when online presentation is implemented.

The registry is fixed for the graph lifetime. Add a `ProviderRegistration` to its composition to register another provider and its styles. A registration accepts a custom `UrlTileBuilderFactory`; the default uses declarative endpoint templates. No core switch on provider IDs is needed. Duplicate provider/style IDs and invalid request capabilities are rejected during registration.

## URL builders and configuration

```kotlin
fun interface UrlTileBuilder {
    fun build(level: Int, row: Long, col: Long): String
}
```

`OsmUrlTileBuilder`, `ArcGisUrlTileBuilder`, `YandexUrlTileBuilder`, and `ThunderforestUrlTileBuilder` use the common `EndpointUrlTileBuilder`. OSM styles retain their separate endpoints. ArcGIS uses z/row/column path order; XYZ templates use z/column/row. Yandex uses query coordinates and declared parameters/defaults. Ktor encodes query values exactly once, including credentials containing reserved or Unicode characters.

Rows/columns remain `Long`, consistent with Phase 1. This avoids narrowing source contracts to the renderer's `Int` indices. Phase 3B must explicitly handle the renderer's representable range, rather than imposing a global zoom-18 cap. Source requests enforce declared level limits and representable XYZ indices; they convert bottom-origin endpoint rows when configured. Geographic coverage and projection mathematics remain in 3B.

Only declared endpoint parameters are accepted. Required missing parameters and credentials fail before HTTP. Credential values are resolved when a source opens, remain in that session, and are never added to serializable provider definitions or package snapshots. Reopen a source after changing its credentials. URL-builder and raw-request string representations redact their contents; networking has no request/body logging and disables automatic redirects to avoid forwarding credential-bearing URLs to another endpoint.

`TileProvider.requestPolicy` is independently configurable for each registered provider, including copies of built-in definitions:

| Setting | Default |
| --- | --- |
| Maximum attempts, including the first | 3 |
| Initial / maximum exponential retry delay | 500 / 10,000 ms |
| Concurrent requests per provider | 4 |
| Minimum interval between request starts | 100 ms |
| Request / connection / socket timeout | 15,000 / 5,000 / 10,000 ms |
| Maximum response size per tile | 2,000,000 bytes |

Provider capability limits can tighten concurrency and pacing. All styles/sessions of a provider share its gate; creating another source does not reset the limit. The generic transport also limits total active requests to 16. These are conservative application defaults, not claims about a provider account's quota.

Transient network/timeouts, HTTP 408/429, and 500/502/503/504 retry up to the configured attempt count. Backoff is cancellable and exponential. `Retry-After` seconds and HTTP dates are supported; a server delay above the configured maximum returns the failure instead of retrying earlier than requested. Missing tiles, authentication failures, other HTTP errors, oversized responses, and invalid image responses do not retry. OkHttp connection retries are disabled so the provider loop controls application attempts. No generic Ktor retry plugin is installed.

## Tile bytes, streams, and lifetime

HTTP is consumed inside Ktor's scoped streaming `execute` block, in chunks with an enforced byte cap even if Content-Length is absent. The complete bounded tile becomes immutable bytes only after the response finishes. Thus retries happen before a renderer receives data and no network connection escapes to a synchronous decoder.

`TileSource.read` preserves typed failures. It recognizes PNG/JPEG signatures and checks declared formats and response MIME type (missing MIME or `application/octet-stream` permits signature detection). This is format screening, not a complete image decode or dimension/decompression check; those checks belong to the renderer integration. Unsupported or corrupt payloads are not presented as missing tiles.

`TileSourceStreamProvider` converts available bytes into a fresh `kotlinx.io.RawSource`. Missing tiles return null; failures invoke the supplied failure callback before returning null. Cancellation propagates. The consumer owns and closes each returned source. Closing an online source cancels and waits for active reads, is idempotent, and makes later reads return CLOSED. Completed streams are independent of source closure. The application-scoped HTTP client remains alive for other sources.

MapComposeMP 1.1.3's source confirms its provider signature is `suspend getTileStream(row: Int, col: Int, zoomLvl: Int): RawSource?`; the renderer closes the source after decoding and does not recover from arbitrary provider exceptions. The thin renderer bridge and user-visible failure handling are therefore part of 3B/3C.

The per-tile response bound is separate from the 300,000,000-byte total offline-package limit. Package budgeting, storage overhead, and download finalization remain in Phases 4–5.

## Credential storage

`ProviderCredentials` exposes read, write, and remove operations. Reads distinguish missing credentials from unavailable/corrupt storage or keys. Writes report failure without substituting plaintext or erasing existing ciphertext. Coroutine cancellation propagates. The encrypted payload envelope is versioned; unknown versions and failed authentication require credential re-entry, not silent regeneration on read.

- Android encrypts with AES-256-GCM, a fresh platform-generated IV, and a key generated/stored in Android Keystore. The credential identifier is authenticated additional data. Ciphertext is stored in a separate Preferences DataStore under `noBackupFilesDir`.
- iOS uses Security framework ECIES with X9.63 SHA-256 and AES-GCM. Its P-256 private key is persisted in Keychain with `AfterFirstUnlockThisDeviceOnly` accessibility. The encrypted payload binds the credential identifier. Ciphertext is stored in a separate Application Support directory explicitly excluded from backup.

Keys never enter DataStore. This protects stored credentials, not an already-compromised running application; plaintext necessarily exists briefly when constructing authenticated requests. Hardware-backed key availability is platform/device dependent; no Secure Enclave or StrongBox requirement is imposed. Credentials are excluded from ordinary preferences, app backup, package manifests, and diagnostics. Tests use synthetic values. API keys are supplied by callers; there is no embedded production credential.

Theme/coordinate preference persistence and backup behavior remain unchanged. Credential entry UI is deferred to 3C. A lost or invalidated key is an explicit unavailable result; the future UI must offer removal/re-entry.

## Live-source prerequisites

Built-in endpoint definitions are implemented and tested with fake responses. This does not approve live access for every account. Before enabling live sources in 3C:

- OSM needs visible attribution and an identifying User-Agent (the client sends `Bmaps/0.1 (bes.max.bmaps)`), plus policy-compliant HTTP caching. Public OSM offline bulk download remains prohibited. Persistent caching is not implemented in 3A.
- Resolve ArcGIS levels, content, coverage, and contributor attribution from service metadata.
- Verify OsmAnd and Thunderforest endpoint/account policies and the requested Thunderforest `tile.thunderforest.com` host; its current documentation uses `api.thunderforest.com`.
- Supply Yandex/Thunderforest keys. Account-required Yandex signing is not implemented. Validate Yandex projection/scale against the effective tile matrix before constructing the map.

## Verification

```sh
./gradlew :core:network:testAndroidHostTest :domain:providers:testAndroidHostTest :core:datastore:testAndroidHostTest
./gradlew :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest
./gradlew :shared:compileKotlinIosArm64 :shared:linkDebugFrameworkIosSimulatorArm64
```

For native Keychain tests, boot one iOS simulator before running the task. The `app.ios.keychain-test` convention embeds simulator-only test entitlements and disables Kotlin's standalone simulator mode. Set `-Pbmaps.test.iosDevice=<simulator-UUID>` if multiple simulators are running.

```sh
./gradlew :core:datastore:iosSimulatorArm64Test
./gradlew :androidApp:connectedDebugAndroidTest
```

The native cipher scenarios verify fresh ciphertext, key reuse, tampering rejection, and rejection when moving ciphertext to another credential identifier. Common repository tests use a deterministic cipher stand-in to isolate persistence/error behavior. Host networking/provider tests use MockEngine or fake transport; they make no real provider requests. On 2026-09-09, 32 host tests, five native iOS credential tests, Android APK builds, and iOS compilation/framework linking passed. The native iOS tests include the real encrypted DataStore path. Android Keystore instrumentation execution remains unverified due to the UTP dependency and emulator storage issues recorded in the implementation plan.

If the configured AGP cannot resolve its UTP runner, the already-built test APK can be run directly on a device with sufficient space:

```sh
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
adb install -r androidApp/build/outputs/apk/androidTest/debug/androidApp-debug-androidTest.apk
adb shell am instrument -w -e class bes.max.bmaps.AndroidCredentialCipherTest bes.max.bmaps.test/androidx.test.runner.AndroidJUnitRunner
```

## Reference sources

- [Ktor response streaming](https://ktor.io/docs/client-responses.html).
- [MapComposeMP source](https://github.com/p-lr/MapComposeMP), verified against the cached 1.1.3 source artifact.
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore).
- [Apple key encryption](https://developer.apple.com/documentation/security/using-keys-for-encryption).
- [Kotlin native Keychain test issue](https://youtrack.jetbrains.com/issue/KT-61470).
- [OSM tile usage policy](https://operations.osmfoundation.org/policies/tiles/).
- [Yandex request parameters](https://yandex.ru/maps-api/docs/tiles-api/request.html).
- [Thunderforest tile API](https://www.thunderforest.com/docs/map-tiles-api/).
