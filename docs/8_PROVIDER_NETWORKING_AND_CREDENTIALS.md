# Provider Networking and Credentials

Phase 3A implements provider registration, URL construction, bounded tile HTTP, and encrypted credential persistence. Phase 3B adds the MapComposeMP renderer wrapper. Phase 3C connects OSM, ArcGIS, OsmAndHd, Thunderforest, and Yandex to the constructor; credentialed sources use runtime secure key storage. No map request starts when the application shell opens.

## Ownership and composition

- `domain:providers` owns `ProviderRegistry`, provider-specific URL builders, `TileRequestPolicy`, `OnlineTileSourceFactory`, and the adapter from raw HTTP results to `TileReadResult`.
- `core:network` owns the application-scoped Ktor client and `HttpTransport`. Android uses OkHttp; iOS uses Darwin. It imports no provider or map types.
- `core:datastore` owns `ProviderCredentials`, ciphertext persistence, and platform encryption. Its existing `core:di` dependency supplies Metro scopes and bindings.
- `core:map-engine` owns `TileSource`, the KMP `TileStreamProvider`, and `TileSourceStreamProvider`. No renderer types enter provider APIs.
- `shared` assembles/exposes these services through the Metro graph. The constructor injects `ProviderRepository` and `OnlineSourceOpener`; its separate credentials dialog injects `ProviderCredentials`.

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

The Phase 3C dialog supports saving, replacing, and removing a key. Its masked draft is never saved in navigation arguments or saved-instance state; only the credential identifier is routed. Validation occurs in the dialog ViewModel. Storage failures keep the dialog open with a retryable error. Success clears the draft and emits a Channel event to dismiss; retrying the map opens a fresh source using the new key. Lost or invalidated keys produce an explicit unavailable result.

## Online constructor and provider availability

Opening Build loads OSM; the default Library destination does not request map tiles. The selector lists all registered styles. The presentation layer validates projection, square tile dimensions, source levels, geographic coverage support, initial viewport, and scale limits before creating a raster session. Unsupported matrices fail explicitly rather than truncating levels. The current online presentation supports global Web Mercator pyramids representable by the renderer; regional rebasing is still future work.

`OnlineMapViewModel` exposes one immutable state stream. Source factories create independently owned sources only when rendering begins. Switching or Retry creates a new generation; callbacks from retired generations are ignored. Retry retains the last observed viewport, clears the error, and reopens the source. Missing tiles and typed network/authentication failures are visible; the first error remains until Retry or source selection. Successful tile validation ends the initial loading indicator. Renderer disposal owns cancellation and source closure. Durable viewport restoration and the full background/network recovery matrix remain Phase 3D/6 work.

| Source | Current availability | Remaining requirements |
| --- | --- | --- |
| OSM / WorldStreetMap | Enabled; source levels 0–19, visible linked attribution, identifying User-Agent, persistent native HTTP cache | Public OSM bulk/offline package downloads remain prohibited. |
| Thunderforest / Atlas | Configured at `api.thunderforest.com`; levels 0–22; linked Thunderforest and OSM attribution; masked key entry | User account/key required. Authenticated native rendering has not been verified. Offline entitlement must be verified separately. |
| OsmAnd / OsmAndHd | Configured and selectable; no embedded credential | Establish permission for third-party use of the endpoint. OSM's standard-tile policy does not cover it. |
| ArcGIS / World Imagery | Configured and selectable; JPEG source levels 0–23 and service attribution | Verify licensed access, effective coverage, and regional rendering beyond the current global engine limits. |
| Yandex / Map | Configured and selectable; masked runtime API-key entry and Yandex attribution/logo | Verify account requirements, signing where required, branding obligations, and effective projection/image scale. |

The ArcGIS metadata retrieved on 2026-09-09 advertises JPEG, 256 × 256 tiles, EPSG:3857, and levels 0–23. Its projected extent is approximately ±20,037,507.23 east/west and ±19,971,868.88 north/south. Copyright text names Esri, Vantor, Earthstar Geographics, and the GIS User Community. The current source definition uses this metadata and attribution; runtime metadata resolution, licensing verification, and regional rebasing remain future work.

`OnlineMapAvailability` records integration readiness in the provider domain. Adding a catalog definition does not automatically approve a live source. Provider policy and account requirements must be reviewed alongside its rendering configuration.

## Public HTTP cache

`HttpResourceRequest.cachePublicResponse` defaults to false. The provider request policy opts OSM into caching. `HttpClients` supplies separate uncached and public-cache clients through Metro. Cache-enabled requests with query parameters are rejected so query credentials cannot enter the public cache; credential-bearing providers use the uncached client. Both paths retain the existing response bounds and request gate.

- Android uses OkHttp's 64 MiB disk cache under the app cache directory.
- iOS uses a dedicated NSURLCache with 8 MiB memory and 64 MiB disk capacity and the native protocol cache policy. The uncached session has no URL cache.

The engines honor HTTP freshness and conditional validation. No default no-cache headers or bulk prefetch are added. Only viewport requests are made. Cache files are disposable OS cache data, separate from encrypted credentials and future offline packages; they do not make a downloaded package or count toward its 300,000,000-byte limit. No Ktor `HttpCache` body-buffering plugin is installed.

The Android native-cache host test uses a local server to verify fresh responses survive client recreation, stale responses send ETag conditional requests and reuse the body after 304, and uncached requests continue to reach the server. Common transport tests verify opt-in routing and rejection of cached query URLs. iOS runtime inspection found 55 persisted OSM responses in its dedicated cache; iOS conditional-revalidation automation remains future verification.

Debug smoke entry points: iOS `--online-map` starts on Build; `--preview-map` retains the offline fixture. Android's debug-only `fixture-map` intent extra selects the fixture for deterministic UI tests without contacting providers. Release entry points ignore these debug switches.

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
- [Thunderforest tile API](https://www.thunderforest.com/docs/map-tiles-api/) and [terms](https://www.thunderforest.com/terms/), checked 2026-09-09.
- [ArcGIS service metadata](https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer?f=pjson) and [Esri basemap licensing/attribution requirements](https://developers.arcgis.com/javascript/latest/references/core/layers/WebTileLayer/), checked 2026-09-09.
- [Apple URLCache](https://developer.apple.com/documentation/foundation/urlcache).
