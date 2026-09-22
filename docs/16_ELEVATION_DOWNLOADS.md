# Elevation downloads

Implemented 2026-09-22. This increment downloads and packages elevation assets; it does not parse elevations, transform coordinates, or display terrain. Manual API and device testing remains with the user.

## Selection and credentials

Map download settings offer None (default), SRTM15+, NASADEM, Copernicus GLO-30, Copernicus GLO-90, and Europe DTM, with localized descriptions. Protocol IDs are `SRTM15Plus`, `NASADEM`, `COP30`, `COP90`, and `EU_DTM`. Selection survives the key dialog and is persisted in `BuildRequest.elevationDataset`. Legacy requests deserialize to `NONE`.

The presentation layer invokes `ElevationDataset.supportsRequest` before submission. This initial single-file implementation rejects date-line crossings, invalid/empty bounds, and selections exceeding the documented API area limit: 125,000,000 km² for SRTM15+, 4,050,000 km² for COP90, and 450,000 km² for the other offered products. Geographic area uses a spherical approximation. Dataset coverage is explained in the selector and enforced by the provider response; regional coverage is not guessed from a rectangular envelope. Selecting None preserves existing tile-only behavior.

An absent or unavailable `opentopography` credential opens the existing navigation-scoped credential dialog. Users obtain their own key through the OpenTopography portal. API/account URLs live in `domain:providers` (`OpenTopographyEndpoints`); the credential ViewModel exposes the account URL for the UI to open. Saving uses `ProviderCredentials`, backed by encrypted DataStore and Android Keystore/iOS Keychain; keys are never persisted in jobs, manifests, navigation state, or diagnostics. After saving, the user taps Download again. The settings dialog also offers key management before submission.

The downloader resolves the credential afresh for each attempt. HTTP 401 produces `ElevationCredentialsRequired`; the library explains replacement of an expired/revoked key and offers a direct replacement action followed by Retry. There is no assumed expiration date. HTTP 403 remains a distinct access-denied failure with account/dataset guidance and a key replacement option. HTTP 429 retains Retry-After and does not ask for another key. HTTP 204/404 means no data; 400/413/422 indicates an unsupported request. Other HTTP errors and transport failures remain retryable network failures. Response text and credential-bearing URLs are not shown or logged.

## Download and persistence

`domain:providers` owns `ElevationDataset`, `ElevationSource`, and `OpenTopographySource`. It constructs an HTTPS request to `/API/globaldem` using WGS 84 bounds and `outputFormat=GTiff`. Raw streaming belongs to `core:network`; platform engines remain OkHttp and Darwin. The uncached client disables redirects, uses a 10-minute total timeout and a 2-minute socket timeout, and delivers at most 64 KiB per callback. The streaming request uses 64-bit byte counters and no configured DEM size cap. Tile downloads retain their separate buffered-response bounds; DEM writes check available disk space incrementally. Storage exceptions and coroutine cancellation propagate without being recategorized as network errors.

`DownloadRunner` downloads elevation after all requested raster layers. Zero missing tiles is no longer sufficient to bypass asset downloading. Layer-specific Android workers only start elevation after the final layer. A failed DEM leaves completed tiles intact and the package incomplete. Restore skips committed tiles and a committed elevation asset. Interrupted DEM transfers restart from byte zero; HTTP Range resume is not assumed. Pause/cancel clean up the partial DEM. Process-start reconciliation also removes `.part` files.

`PackageBuildStorage` exposes begin/append/finish/discard operations. `core:storage` writes `elevation.geotiff.part` in the staging package, taking filesystem/repository locks only for individual writes rather than waiting on the network while holding them. Every chunk checks actual free space. Elevation does not consume the independent 300 MB tile-layer allowances, and no aggregate package-size cap is applied. Manifest writes retain their own format bound and capacity checks. Completion screens classic TIFF/BigTIFF byte order, magic, and first-directory offset, synchronizes the file, and atomically renames it to `elevation.geotiff` before recording its size. This is header screening, not full GeoTIFF/CRS/raster validation; deeper validation belongs to the future elevation reader.

A crash between asset rename and database checkpoint leaves an uncommitted file that the next attempt replaces. A crash after the checkpoint allows the valid committed asset to be reused. Finalization requires the requested elevation asset, includes it in file-inventory/size checks, and promotes the complete package atomically as before.

The version-1 manifest adds optional/defaulted `elevationDataset`; successful downloads set `elevation` to `{relativePath: "elevation.geotiff", sizeBytes: ...}` and the Room summary's `hasElevationData` to true. None leaves `elevation: null`. No key or request URL is stored. Legacy manifests remain readable. The file participates in existing package deletion and future export/import inventory.

Estimates include a four-byte sample grid at the dataset's nominal angular resolution plus a small header allowance. They are advisory: compression, latitude-dependent product grids, and provider output can change actual size. The 300 MB limit applies separately to each MBTiles layer. Elevation and annotations are excluded. The combined estimate still drives free-space validation for the entire download. Library progress labels distinguish the remaining asset/finalization stage when tile downloading is complete; DEM byte counts enter durable telemetry at asset commit.

## Provider references

- [OpenTopography OpenAPI definition](https://portal.opentopography.org/apidocs/openapi.json): endpoint, exact identifiers, bounds, output format, and statuses.
- [Developer documentation](https://opentopography.org/developers): dataset availability, daily quotas and per-request limits.
- [Terms of use](https://opentopography.org/usageterms): personal keys, API acknowledgement, and Enterprise requirements for commercial integration. Personal-key support does not establish commercial entitlement.

This work is based on API services provided by the OpenTopography Facility with support from the National Science Foundation under NSF Award Numbers 2410799, 2410800 & 2410801.

## Verification

Automated coverage includes encoded runtime credentials, missing/replaced keys, HTTP failure categories, bounded streaming, redirect refusal, storage error propagation, TIFF header screening, DEM retries without tile downloads, cancellation cleanup, dataset selection validation, and native package persistence/recovery. Verification results are recorded in the implementation plan.

Manual checks for the user:

1. Download with None and confirm the existing tile-only behavior and `elevation: null`.
2. Select each dataset for a small area within its coverage. Confirm titles/descriptions, combined estimate, `elevation.geotiff`, dataset identity, file size, and elevation availability in the library/config.
3. With no saved key, confirm the secure entry dialog opens and saving allows a second Download attempt. Restart the app and verify key reuse.
4. Use a rejected/revoked key. Confirm the library offers replacement; save a working key and Retry without redownloading completed tiles. Check 403 and quota errors remain distinct from expired-key guidance.
5. Pause, cancel, interrupt networking, and terminate the app during DEM transfer. Verify partial files are cleaned, restored transfers restart safely, and no incomplete package appears ready.
6. Exercise insufficient space and the per-layer limit. Verify that multiple valid layers can exceed 300 MB together, and elevation does not reduce any layer allowance. Check unsupported coverage and a date-line selection with elevation versus None.
7. Verify actual downloaded files independently (for example, with a GeoTIFF inspection tool), including bounds and dataset. Elevation sampling/display is not part of this increment.
