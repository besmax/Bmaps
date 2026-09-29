#include "bmaps_dem.h"
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define CHECK(expression) do { if (!(expression)) { \
    fprintf(stderr, "Failed: %s (%s:%d)\n", #expression, __FILE__, __LINE__); exit(1); \
} } while (0)

static bmaps_dem *open_fixture(const char *root, const char *name, int expected) {
    char path[4096];
    snprintf(path, sizeof(path), "%s/%s", root, name);
    int status = -1;
    bmaps_dem *reader = bmaps_dem_open(path, &status);
    if (status != expected) fprintf(stderr, "%s: expected status %d, got %d\n", name, expected, status);
    CHECK(status == expected);
    CHECK((reader != NULL) == (expected == BMAPS_DEM_OK));
    return reader;
}

static void value(bmaps_dem *r, int column, int row, double expected) {
    double actual = 0;
    CHECK(bmaps_dem_sample(r, column, row, &actual) == BMAPS_DEM_OK);
    CHECK(fabs(actual - expected) < 1e-10);
}

int main(int argc, char **argv) {
    if (argc == 3 && strcmp(argv[1], "--large") == 0) {
        int status = -1;
        bmaps_dem *r = bmaps_dem_open(argv[2], &status);
        CHECK(r && status == BMAPS_DEM_OK);
        value(r, 0, 0, -123);
        value(r, 8192, 8192, 456);
        value(r, 16383, 16383, 789);
        for (int i = 0; i < 1000; i++) value(r, 16383, 16383, 789);
        bmaps_dem_close(r);
        puts("512 MiB DEM random-access checks passed");
        return 0;
    }
    CHECK(argc == 2);
    const char *orders[] = {"le", "be"};
    const char *codecs[] = {"none", "lzw", "deflate", "packbits"};
    double sample = 0, metadata[BMAPS_DEM_METADATA_COUNT];
    for (int order = 0; order < 2; order++) for (int codec = 0; codec < 4; codec++) {
        char name[128];
        snprintf(name, sizeof(name), "int16-%s-%s.tif", orders[order], codecs[codec]);
        bmaps_dem *r = open_fixture(argv[1], name, BMAPS_DEM_OK);
        bmaps_dem_metadata(r, metadata);
        CHECK(metadata[0] == 7 && metadata[1] == 5 && metadata[2] == 10 && metadata[3] == 50);
        CHECK(metadata[4] == 0.25 && metadata[5] == -0.25 && metadata[6] == 0);
        for (int row = 4; row >= 0; row--) for (int col = 0; col < 7; col++) {
            if (row == 2 && col == 3) CHECK(bmaps_dem_sample(r, col, row, &sample) == BMAPS_DEM_NO_DATA);
            else value(r, col, row, row * 7 + col - 10);
        }
        CHECK(bmaps_dem_sample(r, 7, 0, &sample) == BMAPS_DEM_OUTSIDE);
        CHECK(bmaps_dem_sample(r, 0, 5, &sample) == BMAPS_DEM_OUTSIDE);
        bmaps_dem_close(r);
    }
    bmaps_dem *r = open_fixture(argv[1], "float32-tiled-bigtiff.tif", BMAPS_DEM_OK);
    bmaps_dem_metadata(r, metadata);
    CHECK(metadata[6] == 1 && metadata[10] == 1 && metadata[11] == 1024);
    value(r, 0, 0, -25);
    value(r, 18, 16, 55.5);
    value(r, 16, 0, -21);
    value(r, 0, 16, 51);
    CHECK(bmaps_dem_sample(r, 4, 3, &sample) == BMAPS_DEM_NO_DATA);
    bmaps_dem_close(r);
    r = open_fixture(argv[1], "float32-numeric-nodata.tif", BMAPS_DEM_OK);
    CHECK(bmaps_dem_sample(r, 0, 0, &sample) == BMAPS_DEM_NO_DATA);
    value(r, 1, 0, 0);
    bmaps_dem_close(r);
    r = open_fixture(argv[1], "float64-be.tif", BMAPS_DEM_OK);
    value(r, 0, 0, -0.125); value(r, 1, 0, 1.5);
    CHECK(bmaps_dem_sample(r, 2, 0, &sample) == BMAPS_DEM_NO_DATA);
    bmaps_dem_close(r);
    r = open_fixture(argv[1], "uint32.tif", BMAPS_DEM_OK);
    value(r, 0, 0, 0); value(r, 1, 0, 4294967295.0);
    bmaps_dem_close(r);
    const char *unsupported[] = {"unsupported-crs.tif", "missing-georeference.tif", "unsupported-orientation.tif",
        "unsupported-int64.tif", "unsupported-transform.tif", "unsupported-multiband.tif"};
    for (size_t i = 0; i < sizeof(unsupported) / sizeof(unsupported[0]); i++)
        open_fixture(argv[1], unsupported[i], BMAPS_DEM_UNSUPPORTED);
    open_fixture(argv[1], "oversized-strip.tif", BMAPS_DEM_LIMIT);
    open_fixture(argv[1], "invalid.tif", BMAPS_DEM_INVALID);
    open_fixture(argv[1], "does-not-exist.tif", BMAPS_DEM_INVALID);
    r = open_fixture(argv[1], "truncated-strip.tif", BMAPS_DEM_OK);
    CHECK(bmaps_dem_sample(r, 6, 4, &sample) == BMAPS_DEM_INVALID);
    bmaps_dem_close(r);
    r = open_fixture(argv[1], "gdal-n43.tif", BMAPS_DEM_OK);
    value(r, 0, 0, 294); value(r, 10, 10, 384); value(r, 100, 100, 111); value(r, 119, 119, 190);
    bmaps_dem_close(r);
    puts("DEM fixture checks passed");
    return 0;
}
