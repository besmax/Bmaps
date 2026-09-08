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
    * The initial total package limit is 300 MB, defined as 300,000,000 bytes across all package files. Size estimates are advisory; future writers must enforce actual storage limits and account for database/manifest overhead. This limit must not silently truncate selected zoom levels or produce incomplete maps labeled as ready.
* **Layer Composition:** Support for overlaying multiple raster map files on top of each other (initially restricted to identical geographic bounding boxes without offsets).
* **Opacity Control:** Independent transparency/opacity sliders for each rendered tile layer.
* **Annotations & Overlays:** Users can place markers (pins), draw routes, and highlight areas (polygons) on top of the map.
* **Elevation Data (DEM):** Integration of Digital Elevation Models to extract and display altitude information, parsing data from `.tiff` or `.geotiff` files.

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
* **WGS-84** (Standard GPS coordinates).
* **SK-91** (СК-91).
* Interface contracts for adding new local coordinate reference systems (CRS) as needed.

## 6. Import, Export, and Sharing
The application acts as a hub for geospatial data and must support seamless peer-to-peer package transfers.
* **Standard OS Sharing:** Integration with native intents (Quick Share on Android, AirDrop on iOS).
* **Direct Hardware Transport:** The architecture should anticipate custom data transport protocols for direct device-to-device synchronization in fully offline environments, including Bluetooth Low Energy (GATT) payload chunking and local Wi-Fi (UDP/TCP) file transfers.
