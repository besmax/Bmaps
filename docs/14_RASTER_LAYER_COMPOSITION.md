# Raster layer composition

Phase 7 implementation, updated 2026-09-19. Source review and static checks only. Builds, automated test execution, and manual acceptance remain with the user.

## Constructor and alignment

After selecting an area, map settings offer **Add provider layer**. Choose another catalog provider/style, for example OSM as the root and ArcGIS satellite as an additional layer where the existing provider configuration permits downloading. Each additional layer receives a stable UUID. The root retains its `base` ID. The dialog supports removal, ordering of additional layers, and visibility; the viewer can also reorder the root. Download settings do not expose opacity: every downloaded layer starts at full opacity, and independent opacity is adjusted only in the offline viewer.

A two-thumb, integer-stepped range slider selects minimum and maximum zoom, including every intermediate level. A provider with only one level shows that fixed level. Restored sparse drafts are expanded to a continuous range within provider limits; submission rejects gaps. Existing sparse packages remain readable.

Every layer inherits exactly the selected geographic bounds and zoom levels. Presentation validates each provider’s declared coverage, Web Mercator projection, square tile dimensions, supported zoom levels, raster content, and configured download permission before submission. Tile dimensions must match across providers. Changing zoom selection triggers validation again on submission. Credential resolution and current provider capability checks remain in the existing provider/download infrastructure. No reprojection, offsets, tile resizing, or zoom substitution is attempted. Reopening an incompatible package reports an alignment error before renderer configuration.

Tile count and size estimates include all requested layers regardless of visibility. All layer assets share the existing 300,000,000-byte package limit. At most 32 layers are supported. Layers can be hidden without excluding them from the download.

## Configuration and rendering

The root remains `map_data.mbtiles`; additional assets remain `layers/<layer-id>.mbtiles`. Each manifest layer carries its source, bounds, exact zoom levels, matrix metadata, attribution, visibility, opacity, and `renderOrder`. Manifest list order stays the root-first download sequence. Render order is independent of filenames; older manifests with absent order use stable list order. This is an additive version 1 field and requires no Room migration.

The offline viewer opens local tile sources for all visible layers with nonzero opacity. All share one validated pyramid. Contiguous levels support automatic zoom when engine dimensions allow it; sparse or oversized pyramids retain explicit downloaded-level selection. Date-line regions remain separate canonical tile windows. The Layers panel exposes both selections and opens through the shared `MapIconButton` with a layers icon.

MapComposeMP 1.1.3 ignores bottom-layer opacity and skips overlays if the bottom tile is absent. The adapter supplies a correctly sized transparent PNG backing tile, generated once per renderer session using the native image encoder. Every package layer becomes an overlay, allowing independent root opacity and visibility. The backing tile has no provider or package file and emits no tile events. All-hidden maps are valid and display the map background. This adapter behavior was reviewed against the locally cached pinned source; native pixel verification remains pending.

Layers are listed bottom to top. Visibility, order, and completed opacity gestures preview while the dialog is open. Save atomically replaces `config.json`; Cancel restores the previously committed settings. Source attribution is shown in the controls, and the info dialog includes distinct attribution from all package layers. Rendering uses only local MBTiles; provider links open only on explicit user action.

## Download and recovery

Android builds a sequential unique WorkManager chain: root worker, then one worker per additional layer. iOS runs the same layer-scoped operations in sequence within its existing foreground/background opportunity. Each worker opens only its own incomplete source. A predecessor with missing tiles prevents further layers from starting, and any typed failure leaves the package incomplete and restorable.

Layer completion is determined from committed MBTiles counts. Restore skips finished layers, including their provider access, then downloads only missing tiles in the remaining layer. A boundary between layers persists QUEUED, so interrupted work can resume. Pause/cancel continue to address the whole package. The shared progress counter includes every layer/tile identity, and final promotion requires all layers to be complete.

Ready-manifest updates preserve the root and additional file paths. Room metadata is updated after the atomic file replacement. On restart, reconciliation discards `.part` files and indexes the committed manifest, preserving the previous or new complete configuration after interruption.

## Verification handoff

Authored but not executed:

- `DownloadRunnerTest`: separate-provider layering, root completion barrier, per-layer worker boundaries, and skipping a completed root on restore.
- `DownloadValidationTest`: per-provider coverage, continuous selected zooms, download policy, and tile dimensions.
- `MapSaveSettingsTest`: inclusive zoom ranges, updated estimates, invalid endpoints, sparse draft normalization, and single-level providers.
- `OfflinePyramidTest`: mismatched bounds, projection, dimensions, and exact zoom selections; compatible sparse selections.
- `ViewerViewModelTest`: preview/save/cancel, restored render order and opacity, and stable root asset identity.
- Android/iOS `PackageStorageScenarios.layerConfigurationSurvivesReopening`: two MBTiles files, settings after database recreation, and interrupted manifest replacement cleanup.

Manual acceptance:

1. Create a small OSM map with an additional configured ArcGIS satellite layer. Verify the combined estimate and provider attribution. Move both zoom thumbs and verify all intermediate levels are included; verify download settings have no opacity control.
2. Verify the root downloads first, followed by the additional worker; interrupt the root and confirm that the overlay has not started. Restore and check that committed tiles are not fetched again.
3. Interrupt during the overlay and between workers. Restore after app/process restart; verify no network access to the completed root provider.
4. Open offline and change each opacity independently, hide the root, hide all layers, and reorder the root above/below the satellite layer. Confirm alignment and expected blending.
5. Save, close, and reopen to verify settings. Preview a second edit and cancel to verify rollback. Exercise existing sparse packages and date-line halves.
6. Reject mismatched provider coverage, matrix dimensions, CRS, or selected levels before composition. Verify atomic settings recovery and package limits under storage pressure.
