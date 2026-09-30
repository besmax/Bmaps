# Package transfer and native documents

Phase 10 source implementation, 2026-09-30. Android/iOS builds, automated test execution, and cross-device acceptance remain assigned to the project owner. Phase 10 acceptance is not yet complete.

## Ownership and entry points

`PackageTransfer` in `domain:map-builder` accepts caller-owned `RawSource`/`RawSink` streams, independently of pickers, share sheets, or future network transports. `LocalPackageRepository` implements it under the existing package-operation mutex. `core:storage` owns bounded copying, SHA-256, SQLite snapshots, and private workspaces; all filesystem and stream IO uses kotlinx-io. Catalog-pinned Okio supplies only the incremental hash primitive. `core:sharing` owns native launchers and temporary outbound files. `:shared` contributes bindings only.

The library offers **Import map file** and **Share map** for ready packages. A separate `PackageTransferViewModel` owns busy/cancel state and Channel-backed share/error events. Sharing first creates a private `.bmaps` file, then presents the platform share sheet. Import always creates a new copy; it never replaces an existing package. Names, original creation/modification timestamps, source identities, attribution, layer order/opacity/visibility, annotations, elevation identity, and auxiliary assets are preserved. Device-local favourites/avatar preferences and credentials are not exported.

In an open map, **Map objects → Import GeoJSON file** reads and validates a bounded file, then opens the existing import dialog. **Import** commits a batch using new annotation IDs; cancelling the dialog leaves the package unchanged. **Export GeoJSON → Share GeoJSON file** shares the existing complete export snapshot. Existing pasted/copied GeoJSON controls remain available. The supported geometry, property, feature-count, and 4 MB limits are unchanged from document 15.

## Version 1 `.bmaps` wire format

This is an uncompressed framed stream, not ZIP. PNG/JPEG tiles and compressed DEMs are already encoded; an explicit frame avoids decompression expansion and platform-specific ZIP behavior.

| Field | Encoding |
| --- | --- |
| Magic | Seven ASCII bytes `BMAPS\r\n` |
| Transfer version | Signed 32-bit big-endian integer, exactly `1` |
| Manifest length | Signed 32-bit big-endian integer, 1–1,048,576 |
| Manifest | Exactly that many UTF-8 bytes, using package manifest schema 1 |
| Manifest digest | 64 lowercase ASCII hex characters, SHA-256 of those exact manifest bytes |
| Payload | Assets concatenated in manifest order: layer tile databases, annotations if present, elevation if present, then auxiliary assets |
| End | EOF immediately after the last asset; trailing bytes are rejected |

Every asset carries its exact snapshot `sizeBytes` and mandatory SHA-256 in the transfer manifest. Each payload consumes exactly its declared length. The manifest is the only path/size table; there are no additional entry names to disagree with it. Checksums detect corruption, not sender authenticity.

Both directions cap transfers at 1,024 assets and 32,000,000,000 payload bytes, with a 1 MiB header. These are transfer resource limits, not a new aggregate limit on locally built packages. Raster layers retain the existing effective 300,000,000-byte maximum, including SQLite overhead. Imports preflight space for payloads plus database rewriting, then check free space during copies. Snapshot creation and outbound copying also require free space; failures discard unfinished files. Whole assets and whole archives are never buffered in memory. Copy/hash chunks are at most 65,536 bytes; tile validation is bounded by the existing 2 MB tile maximum.

## Snapshot and integrity protocol

Export holds the repository operation lock across manifest recovery, validation, snapshot creation, hashing, and streaming. Annotation commits and layer-presentation updates use the same lock. Raster assets and `annotations.db` are copied with SQLite `VACUUM INTO`, which includes committed WAL data in a consistent standalone database. Immutable elevation/auxiliary assets are streamed as bytes. Snapshot sizes and hashes replace manifest asset metadata only in the exported snapshot; the live package is unchanged. Private snapshot directories are removed on success, failure, and cancellation, and cleared during repository startup reconciliation.

Import validates version, manifest structure, paths, limits, and declared raster sizes before extracting into `staging/import-<UUID>`. Paths use the existing safe-component/no-symlink boundary, with at most eight components. Absolute paths, traversal, case-folded duplicate paths, reserved manifest names, journal/sidecar names, missing bytes, checksum mismatches, and unexpected trailing data are rejected. Asset files cannot be links because the format has only raw byte payloads.

After checksum verification, imports validate tile format/dimensions, SQLite integrity, unique tile identities, exact selected-level coverage, annotation ownership, annotation geometry and stored bounds, and elevation file structure. Package-owned SQLite triggers/views are rejected. Each layer and the annotation database are rebound to the new package ID. Since rebinding changes database bytes, local manifests record new sizes and clear the verified transfer digests; future exports compute new digests. Final verification precedes atomic directory promotion and catalog insertion. All layers must have completeness evidence; unknown manifest/transfer versions are rejected without migration.

The commit boundary is deliberately non-cancellable: cancellation before it deletes staging; once promotion begins, a verified package may finish committing. Process death before promotion leaves no ready package. Unregistered `import-*` staging directories are deleted on reconciliation. A fully verified directory promoted before catalog insertion is reindexed through the existing orphan-package verification path. There is no resumable import job or background transfer service; interrupted work is discarded and the user can retry.

## Standalone MBTiles subset

The library accepts `.mbtiles` with standard `metadata` and `tiles` columns (including readable normalized-schema views), declared WGS-84 `bounds`, PNG or JPEG content, TMS rows, Web Mercator tiles, 256- or 512-pixel square images, and actual zoom levels 0–30. A declared `scheme`, `crs`, or `srs` must agree with those conventions. Actual selected levels are discovered from the database; noncontiguous levels are preserved. Every selected level must completely cover the declared rectangle/date-line regions. Duplicate tile identities, gaps, invalid images, and inconsistent dimensions are rejected.

Import streams the input to a bounded private file, then rewrites tiles into Bmaps' canonical MBTiles schema, preserving attribution and assigning a new package identity. The temporary source is removed before package verification. Both source and normalized layer are bounded by the raster layer limit. Vector/PBF, WebP, arbitrary projections, XYZ-row databases, sparse tile sets, missing bounds, and legacy directories without a transfer header are not supported by this initial flow. This explicit subset retains the current package completeness invariant; broader standalone MBTiles support requires a separate format/coverage decision.

## Native access and cleanup

Android uses the Storage Access Framework document picker and opens the returned content URI while importing. It does not request broad storage permission or persist access grants. Outbound files use a non-exported `FileProvider` restricted to `cache/bmaps-share/`, with read-only URI grants and `ClipData`. Recipients never receive raw filesystem paths.

On iOS, `UIDocumentPickerViewController` requests a copy, including materialization of provider-backed files. The source balances any acquired security-scoped access around its open stream and disposes the picker-created copy afterward. `UIActivityViewController` shares the private file and supplies a popover anchor for iPad. These operations require native-device verification, particularly provider downloads, cancellation, foreground/background transitions, and picker/share presentation from Compose dialogs.

Outbound files are retained so receivers can finish reading after the share sheet returns. `SharedDocumentStore` serializes creation, removes partial outputs on failure/cancellation, and cleans abandoned `.part` files plus completed files older than 24 hours when first used in a process. Completed share files are private cache data and may also be purged by the OS. The repository's snapshot workspace is separate from this recipient-facing cache.

## Verification handoff

Authored, not executed:

- `TransferStreamsTest`: known SHA-256 vector/chunking, header corruption, future transfer versions, truncation, exact payload boundaries, cancellation propagation.
- `ManifestContractsTest.transferPathsAndDigestAreValidatedBeforeExtraction`: traversal, absolute/reserved/case-colliding paths, invalid digests, and excessive declared size.
- `SqliteSnapshotTest` on Android/iOS: snapshot includes a committed row still in WAL while excluding a later write.
- `PackageStorageScenarios.transferRoundTripAndInterruptedImport`, exposed on both native targets: two layers, rendering settings, freshly committed annotations, elevation bytes/identity, auxiliary bytes, import-as-copy collisions, truncated/tampered/trailing streams, cancellation cleanup, interrupted staging/snapshot cleanup, reopen, and standalone PNG MBTiles normalization. Its minimal TIFF checks byte preservation only; it does not establish real DEM sampling correctness.

Static checks: Kotlin PSI syntax parsing, resource XML/reference checks, license/provenance checks, and `git diff --check`. These do not establish Kotlin type correctness, successful native compilation, or runtime behavior.

Android compilation follow-up: the reported Metro error in `AndroidSharingBindings.location` was corrected by explicitly declaring its `SharingLocation` return type. The iOS provider and shared transfer bindings already declare their return types. Recompilation remains pending with the project owner.

Metro scope follow-up: generated contribution hints for the transfer ViewModels and Android sharing bindings targeted `dev.zacsweers.metro.AppScope`, while the application graph uses `bes.max.bmaps.core.di.AppScope`. This excluded the ViewModels from the factory and the `SharingLocation` provider from the graph. Replace Metro wildcard imports with explicit annotation imports in both platform sharing bindings and `AnnotationFileViewModel`; `PackageTransferViewModel` already has explicit imports in the current source. Keep the explicit Bmaps scope import. Generated hints, rather than class visibility or reflection support, identified the mismatch. Rebuild and runtime verification remain pending.

Suggested owner-run checks:

```sh
./gradlew :core:storage:testAndroidHostTest :domain:map-builder:testAndroidHostTest
./gradlew :androidApp:assembleDebug :shared:linkDebugFrameworkIosSimulatorArm64
./gradlew :core:storage:connectedAndroidDeviceTest :domain:map-builder:connectedAndroidDeviceTest
./gradlew :core:storage:iosSimulatorArm64Test :domain:map-builder:iosSimulatorArm64Test
```

Native acceptance still required: export Android → import iOS and the reverse, reopen offline, compare tile samples and every layer setting, edit/read annotations, and compare known valid DEM samples. Exercise real JPEG and normalized-view MBTiles, unsupported files, low storage, cancellation during extraction/validation, process termination before/after promotion, picker cancellation, cancelled shares, repeat import/delete, and 24-hour temporary-file cleanup. Confirm iPad popovers, Android URI grants, large-file memory behavior, and actual Gradle task names in the installed toolchain.

Implementation references: [SQLite snapshot semantics](https://www.sqlite.org/lang_vacuum.html), [Android file URI grants](https://developer.android.com/training/secure-file-sharing/share-file), and [Apple document picker](https://developer.apple.com/documentation/uikit/uidocumentpickerviewcontroller).
