# Annotations and GeoJSON

Phase 8 implementation, 2026-09-19. Source and static checks only; builds, automated test execution, application launch, and native acceptance remain with the user.

## Ownership and editing

`domain:map-builder` owns stable annotation IDs, point/line/polygon models, validation rules, the GeoJSON codec, and `AnnotationRepository`. `LocalPackageRepository` implements the repository alongside its existing package interfaces, keeping file access, deletion, annotation mutations, and reconciliation under the same operation lock. The umbrella contributes the binding only.

`AnnotationEditorViewModel` owns editing independently of `ViewerViewModel`. `MarkersLayer`, `RouteLayer`, and `PolygonLayer` own kind-specific visibility and selection state. The raster wrapper exposes renderer-independent marker and path overlays; no MapComposeMP types escape `core:map-engine`. The viewer forwards geographic taps and viewport updates to the annotation editor. Overlays are recreated when the renderer changes source level or date-line region and never read tile-network sources.

Open **Map objects** to add a marker, line, or polygon, toggle kind visibility, or select an object in the current viewport. Tapping a rendered marker or path also opens its details. **Edit** changes properties or returns to map drawing. Tap the map to append vertices; markers replace their single coordinate. A draft vertex or its **Move** action chooses a coordinate to replace on the next map tap. The properties dialog also removes individual vertices. **Undo** restores the previous draft snapshot, including vertex additions, moves, removals, color, icon, and text changes; it retains up to 1,000 edits. **Cancel** discards the draft. No writes happen before **Save**. Deletion requires confirmation.

Presentation invokes geometry validation before saving: finite WGS-84 coordinates, one point for markers, at least two nonduplicate adjacent points for lines, and at least three distinct noncollinear vertices with no self-intersections for polygons. Polygon rings are represented without a repeated closing vertex internally. Each object has at most 1,000 vertices, a 120-character name, a 4,000-character description, a `#RRGGBB` color, and a stable icon key. Failed saves retain drafts. Drafts and Undo history survive ordinary ViewModel retention, but are not persisted across process death. Layer visibility and selection are viewer-session preferences.

## SVG marker catalog

`feature/viewer/src/commonMain/composeResources/files/markers/place.svg` contains the Material placemark path. `MarkerIcons.entries` maps the stable `place` key to this asset and a localized label. The icon picker is generated from the catalog. Each annotation stores its own color and icon key; color tints the vector at render time, independent of raster-layer opacity.

To add an icon, bundle another SVG and add a `MarkerIconDefinition` plus its label. The bundled SVG subset is deliberately small: a zero-origin numeric `viewBox` and one or more self-closing `<path d="..."/>` elements, using SVG path commands supported by Compose `PathParser`. Flatten transforms and strokes to filled paths before adding an asset. There are no rasterized icon assets, platform SVG libraries, or remote icon requests. Vectors are cached. Unknown saved icon keys render the placemark fallback while retaining the original key for export and future catalog versions.

## SQLite and package recovery

`core:storage` uses the existing version-catalog bundled SQLite driver, independently of `core:mbtiles`. Annotations never enter central Room tables. Each ready package lazily creates `annotations.db` on its first save, with:

- `PRAGMA user_version = 1`;
- `annotation_owner(package_id, updated_at)` to verify package identity and recover modification metadata;
- `annotations(id, feature, west, south, east, north)`, with a primary key and a bounding-box index;
- a complete GeoJSON Feature per row, including user properties, plus indexed geometry bounds.

Version 1 has no predecessor migration. Version 0 is initialized only during explicit creation; unknown versions are rejected without destructive fallback. Connections use no-follow paths, serialized repository access, DELETE journaling, FULL synchronization, and deterministic closure within each operation. Imports and edits use `BEGIN IMMEDIATE` and commit all rows together. Page-count checks occur before commit; failure rolls back the whole batch. The existing package byte limit includes annotations and reserves space for manifest replacement. Physical free-space checks allow for database journaling.

Initial database creation happens in `annotations.db.part`, followed by synchronization and atomic promotion. Recovery removes abandoned creation files and their journal. Subsequent updates commit SQLite first, then atomically replace `config.json` with the database size and modification time, then update Room metadata. A valid package-owned database is authoritative after interruption: opening or reconciliation recovers its hot journal, attaches a newly committed asset if needed, and repairs stale manifest metadata. SQLite integrity is checked when verifying the package; viewport reads do not scan the entire database. No annotation connection survives an operation, so deletion can remove the package without dangling annotation handles.

Viewport queries are inclusive envelope intersections, using SQL filtering and ID-keyset pagination, 200 rows per page. West greater than east denotes a date-line-crossing query and searches both longitude intervals. Degenerate point envelopes are included at boundaries. Results are candidates: an envelope can intersect the viewport even if a line segment or polygon does not. The viewer debounces queries and displays at most 1,000 candidates, with an explicit truncation message. Zooming narrows the query. Both saved geometry and GeoJSON use literal longitude coordinates; segments do not silently wrap to the shortest route across the date line. Create separate objects on each side for a short crossing; multipart cutting is outside this supported subset. Coordinates up to the poles remain stored/exportable; the raster viewport is limited to Web Mercator and clips path latitude to its limit, while points outside that projection are not rendered.

## GeoJSON mapping

The supported interchange subset follows [RFC 7946](https://www.rfc-editor.org/rfc/rfc7946): `Feature` or `FeatureCollection`, with two-dimensional `Point`, `LineString`, and single-ring `Polygon` geometry. Positions use `[longitude, latitude]` in WGS 84 degrees. Closed polygon rings are required on import and supplied on export, with exterior winding normalized counterclockwise (vertex order may reverse while geometry is preserved). Holes, multipart geometry, geometry collections, null geometry, elevation coordinates, and custom CRS declarations are rejected explicitly. Foreign top-level members such as input `bbox` are not retained; bounds are recomputed from geometry.

Feature string IDs map to annotation IDs. Missing IDs are generated. Duplicate IDs within an import are rejected. The UI imports copies with new IDs to avoid overwriting existing objects; a failed import retry reuses its pending IDs. Export preserves saved IDs. Reserved properties are `name`, `description`, `marker-color`, and `marker-symbol`; all other JSON properties are retained, including nested values. Unknown icon keys are valid. Colors are RGB hex strings. Input is limited to 4 MB, 1,000 features, and 1,000 vertices per feature; SQLite also caps each serialized feature at 256 KB. Invalid input aborts the complete import.

The Map objects panel provides pasted-GeoJSON import and selectable/copyable GeoJSON export. Export reads all package annotations, independently of viewport and visibility; exceeding the 1,000-feature/4 MB export limit produces an error rather than truncating. Native file picking, sharing, and complete-package transfer remain Phase 10.

## Verification handoff

Authored, not executed:

- `AnnotationGeoJsonTest`: geometry/property/color/icon/ID round trips, coordinate order, closed rings, rejected geometries and dimensions, invalid polygons, literal date-line longitudes, and polar coordinate preservation.
- `AnnotationEditorTest`: drawing Undo, invalid geometry blocking writes, move/remove/cancel behavior, failed-save draft retention, fresh import IDs, and SVG path loading.
- `PackageStorageScenarios.annotationsSurviveRestartAndStayIsolated`, exposed on Android and iOS: two-package isolation, inclusive/overlapping date-line queries, pagination, restart, SQLite-before-manifest interruption, rollback under size limits, deletion, and icon/color persistence.

Native acceptance: create all three object types, pan and zoom, switch downloaded levels and date-line halves, recolor markers independently, move/remove vertices and Undo repeatedly, cancel edits, then save and relaunch offline. Verify selections and filled polygons on both platforms. Import/export all supported geometries and nested properties; reject unsupported files without partial writes. Exercise backgrounding, process death during initial creation and update, low storage, future schema rejection, and deleting a package after editing. Rendering, compilation, lifecycle behavior, and real crash recovery remain unverified until these checks run.

Static verification completed: changed Kotlin sources parsed without syntax errors using the cached Kotlin PSI parser; viewer string references, XML, and the bundled SVG structure were checked; `git diff --check` passed. These checks do not establish type correctness, compilation, or native behavior.

Android compilation follow-up: the viewer editor, layer managers, and editor tests now explicitly import the domain `Annotation` model. Wildcard imports had resolved type positions to `kotlin.Annotation`, causing cascading missing-property and incompatible-list errors. Recompilation remains pending; syntax parsing alone cannot detect this type-resolution issue.

Metro registration follow-up: `AnnotationEditorViewModel` is public so its contribution is visible to the app graph assembled in `shared`; its editor state remains internal to the feature. Inspection of the failing Android build showed the editor factory in the feature output but no editor entry in the generated app graph, unlike the public `ViewerViewModel`. Rebuild and offline-map opening verification remain with the user.
