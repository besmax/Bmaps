---
type: "vision_and_product"
project: "Bmaps"
purpose: "Define the core product features, storage architecture, and future-proofing requirements for AI agents and developers."
---

# Bmaps: Vision and Product Definition

## 1. Product Overview
Bmaps is an offline-first, privacy-focused map constructor and viewer. It allows users to browse online maps, select specific regions, and compile them into localized offline map packages. The application targets both standard users and professionals requiring advanced geospatial tools, multi-layer rendering, and robust offline capabilities.

## 2. Core Features (MVP)
* **Tile Provider Selection:** Users can select from multiple raster tile providers (e.g., OSM, Satellite). The system must be designed to easily register new providers.
    * A provider has stable identity and one or more separately identified styles. Initial definitions: OSM (WorldStreetMap and OsmAndHd), ArcGIS (World Imagery satellite), Yandex (Map), and Thunderforest (Atlas).
    * Raster content supports PNG and JPEG. Provider/style configuration includes geographic boundaries, initial scale/scroll, scale limits, source level limits, tile matrix details, and endpoint parameters. Credentials are runtime inputs, not package contents.
* **Offline Constructor:** Users define a bounding box and zoom levels to download map segments for offline use.
    * There is no application-wide zoom cap of 18. Rendering must accommodate all available source/package levels, including level 0, with display scaling separate from source availability. Provider-specific limits still apply to tile requests.
    * Each raster tile layer is limited to 300 MB (300,000,000 bytes), including its MBTiles database overhead. Multiple layers have independent allowances; elevation, annotations, and package metadata do not consume them. There is no aggregate package-size cap. Size estimates are advisory; writers enforce actual layer sizes and device free-space requirements. This limit must not silently truncate selected zoom levels or produce incomplete maps labeled as ready.
* **Layer Composition:** Support for overlaying multiple raster map files on top of each other (initially restricted to identical geographic bounding boxes without offsets).
* **Opacity Control:** Independent transparency/opacity sliders for each rendered tile layer.
* **Annotations & Overlays:** Users can place markers (pins), draw routes, and highlight areas (polygons) on top of the map.
* **Elevation Data (DEM):** Integration of Digital Elevation Models to extract and display altitude information, parsing data from `.tiff` or `.geotiff` files.
    * Phase 9's libtiff feasibility reader supports a bounded, single-band, north-up WGS 84 subset. The offline viewer now shows a black crosshair with white outline, center coordinates in the saved format, and optional altitude rounded to tenths of a meter. Known OpenTopography datasets supply the meter/vertical-reference contract; unknown or unsupported metadata is not interpreted as altitude. Actual provider-file and native acceptance remain pending. Supported encodings, rejected variants, pixel semantics and memory limits are specified in `17_ELEVATION_READER.md`.

## 3. Storage Architecture
Maps are stored entirely locally to maintain offline capabilities. Each downloaded map is packaged into an isolated folder within the device's internal storage.
**Folder Structure Standard:**
* `/{package_id}/` (stable storage identity, independent of the display name)
  * `map_data.mbtiles`: The core SQLite database containing the raster tiles.
  * `layers/{layer_id}.mbtiles`: Additional raster layers with identical geographic bounds, listed in rendering order in the versioned manifest.
  * `annotations.db`: A localized SQLite database storing user markers, routes, and polygons. The schema is specifically designed to map directly to GeoJSON geometries and properties. This ensures high-performance spatial queries (viewport bounding box filtering) while allowing frictionless serialization to standard `.geojson` files during package export.
  * `config.json`: Master configuration file defining map boundaries, zoom levels, layers, and metadata.
  * `elevation.geotiff` (or `.tiff`): The DEM matrix for altitude parsing.
  * `*.*`: Any additional required auxiliary files.

The Phase 1 contracts and package lifecycle rules are specified in `6_CONTRACTS_AND_PACKAGE_FORMAT.md`. This defines the format; actual IO and size-limit enforcement are implemented in later phases.

## 4. Architectural Future-Proofing
The architecture must remain highly modular to accommodate the following future expansions without requiring fundamental rewrites:
* **Vector Tiles:** Current implementation is strictly raster, but data contracts and storage models must anticipate vector tile `.mbtiles` support.
* **Render Engine Agnosticism:** While `MapComposeMP` is the current renderer, the core map engine wrappers (`core:map-engine`) must abstract the rendering logic so the engine can be swapped or upgraded seamlessly.
* **Professional Grids:** Future UI/UX will include geospatial grid overlays for professional navigation.

## 5. Coordinate Systems
The application handles complex geospatial mathematics and must not be hardcoded to a single projection. The architecture must natively support transformations between multiple coordinate systems, explicitly including:
* **WGS 84 — EPSG:4326** (geographic 2D).
* **SK-42 / Pulkovo 1942 — EPSG:4284** (geographic 2D).
* **PZ-90.11 — EPSG:9475** (geographic 2D).
* Interface contracts for adding new local coordinate reference systems (CRS) as needed.

The September 29 scope revision replaces SK-91 with these three exact CRS identifiers. EPSG:4284 does not select a Gauss–Krüger projected zone; zoned easting/northing systems require separate definitions and are outside this increment. Coordinate system and angular display format remain separate preferences. Package geometry and DEM lookup remain WGS 84; switching the display CRS does not reproject raster tiles or change the DEM vertical reference.

PROJ is the transformation engine behind the renderer-independent contract. Its matching `proj.db` is embedded for offline use; grid-dependent operations are rejected because no grids are bundled. Select operations by area of use, report operation identity and accuracy, reject ballpark fallback, and report unavailable operations explicitly. Source implementation is present; independent reference fixtures and Android/iOS acceptance remain required before declaring the additional systems verified. See `9_MAP_ENGINE.md`.

## 6. Import, Export, and Sharing
The application acts as a hub for geospatial data and must support seamless peer-to-peer package transfers.
* **Standard OS Sharing:** Integration with native intents (Quick Share on Android, AirDrop on iOS).
* **Direct Hardware Transport:** The architecture should anticipate custom data transport protocols for direct device-to-device synchronization in fully offline environments, including Bluetooth Low Energy (GATT) payload chunking and local Wi-Fi (UDP/TCP) file transfers.
