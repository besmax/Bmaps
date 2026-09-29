/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

#ifndef BMAPS_DEM_H
#define BMAPS_DEM_H

#include <stdint.h>

typedef struct bmaps_dem bmaps_dem;

enum bmaps_dem_status {
    BMAPS_DEM_OK = 0,
    BMAPS_DEM_NO_DATA = 1,
    BMAPS_DEM_OUTSIDE = 2,
    BMAPS_DEM_UNSUPPORTED = 3,
    BMAPS_DEM_INVALID = 4,
    BMAPS_DEM_LIMIT = 5
};

/* Metadata ABI: width, height, originX, originY, stepX, stepY,
   pixelIsPoint, bits, sampleFormat, compression, tiled, decodedBlockBytes, hasGdalMetadata. */
#define BMAPS_DEM_METADATA_COUNT 13
bmaps_dem *bmaps_dem_open(const char *path, int *status);
void bmaps_dem_metadata(const bmaps_dem *reader, double *values);
int bmaps_dem_sample(bmaps_dem *reader, uint32_t column, uint32_t row, double *value);
void bmaps_dem_close(bmaps_dem *reader);

#endif
