#include "bmaps_dem.h"
#include <tiffio.h>
#include <errno.h>
#include <limits.h>
#include <locale.h>
#ifdef __APPLE__
#include <xlocale.h>
#endif
#include <math.h>
#include <stdlib.h>
#include <string.h>

#define BLOCK_LIMIT (8 * 1024 * 1024)
#define LIBRARY_LIMIT (32 * 1024 * 1024)
#define MODEL_SCALE 33550
#define MODEL_TIEPOINT 33922
#define MODEL_TRANSFORM 34264
#define GEO_KEYS 34735
#define GEO_DOUBLES 34736
#define GDAL_NODATA 42113

struct bmaps_dem {
    TIFF *tiff;
    uint32_t width, height, block_width, block_height, cached_block;
    uint16_t bits, format, compression;
    int tiled, point, has_nodata, decode_error, cached;
    double x, y, dx, dy, nodata;
    uint64_t block_bytes;
    unsigned char *buffer;
};

static int custom_field(TIFF *tiff, uint32_t tag, TIFFDataType type, uint32_t *count, void **data) {
    const TIFFField *field = TIFFFindField(tiff, tag, TIFF_ANY);
    if (!field || TIFFFieldDataType(field) != type || !TIFFFieldPassCount(field) ||
        TIFFFieldReadCount(field) != TIFF_VARIABLE2) return 0;
    return TIFFGetField(tiff, tag, count, data);
}

static int error_handler(TIFF *tiff, void *user, const char *module, const char *format, va_list args) {
    (void)tiff; (void)module; (void)format; (void)args;
    ((bmaps_dem *)user)->decode_error = 1;
    return 1;
}

static int warning_handler(TIFF *tiff, void *user, const char *module, const char *format, va_list args) {
    (void)tiff; (void)user; (void)module; (void)format; (void)args;
    return 1;
}

static int read_geography(bmaps_dem *r) {
    uint32_t count = 0;
    void *key_data = NULL;
    if (!custom_field(r->tiff, GEO_KEYS, TIFF_SHORT, &count, &key_data)) return BMAPS_DEM_UNSUPPORTED;
    const uint16_t *keys = key_data;
    if (count < 4 ||
        keys[0] != 1 || keys[1] != 1 || keys[2] > 1 || count != 4 + 4u * keys[3])
        return BMAPS_DEM_UNSUPPORTED;
    int model = 0, crs = 0, raster = 0;
    uint16_t previous = 0;
    for (uint32_t i = 4; i < count; i += 4) {
        uint16_t key = keys[i], location = keys[i + 1], length = keys[i + 2], value = keys[i + 3];
        if (key <= previous) return BMAPS_DEM_INVALID;
        previous = key;
        if (key == 1024 || key == 1025 || key == 2048 || key == 2054) {
            if (location != 0 || length != 1) return BMAPS_DEM_UNSUPPORTED;
            if (key == 1024) model = value;
            if (key == 1025) raster = value;
            if (key == 2048) crs = value;
            if (key == 2054 && value != 9102) return BMAPS_DEM_UNSUPPORTED;
        } else if (key == 2057 || key == 2059) {
            uint32_t double_count = 0;
            void *parameter_data = NULL;
            if (location != GEO_DOUBLES || length != 1 ||
                !custom_field(r->tiff, GEO_DOUBLES, TIFF_DOUBLE, &double_count, &parameter_data) || value >= double_count)
                return BMAPS_DEM_UNSUPPORTED;
            const double *parameters = parameter_data;
            double expected = key == 2057 ? 6378137.0 : 298.257223563;
            if (!isfinite(parameters[value]) || fabs(parameters[value] - expected) > 1e-9)
                return BMAPS_DEM_UNSUPPORTED;
        } else if (key != 1026 && key != 2049) {
            /* Do not silently ignore custom datum, projection or vertical definitions. */
            return BMAPS_DEM_UNSUPPORTED;
        }
    }
    if (model != 2 || crs != 4326 || (raster != 1 && raster != 2)) return BMAPS_DEM_UNSUPPORTED;
    r->point = raster == 2;
    void *scale_data = NULL, *tie_data = NULL;
    uint32_t scale_count = 0, tie_count = 0;
    if (TIFFFindField(r->tiff, MODEL_TRANSFORM, TIFF_ANY) ||
        !custom_field(r->tiff, MODEL_SCALE, TIFF_DOUBLE, &scale_count, &scale_data) || scale_count != 3 ||
        !custom_field(r->tiff, MODEL_TIEPOINT, TIFF_DOUBLE, &tie_count, &tie_data) || tie_count != 6)
        return BMAPS_DEM_UNSUPPORTED;
    const double *scale = scale_data, *tie = tie_data;
    for (int i = 0; i < 3; i++) if (!isfinite(scale[i])) return BMAPS_DEM_INVALID;
    for (int i = 0; i < 6; i++) if (!isfinite(tie[i])) return BMAPS_DEM_INVALID;
    if (scale[0] <= 0 || scale[1] <= 0 || scale[2] != 0 || tie[2] != 0 || tie[5] != 0)
        return BMAPS_DEM_UNSUPPORTED;
    r->dx = scale[0];
    r->dy = -scale[1];
    r->x = tie[3] - tie[0] * r->dx;
    r->y = tie[4] - tie[1] * r->dy;
    double west = r->x - (r->point ? 0.5 * r->dx : 0);
    double north = r->y - (r->point ? 0.5 * r->dy : 0);
    double east = west + r->width * r->dx;
    double south = north + r->height * r->dy;
    if (!isfinite(west) || !isfinite(north) || !isfinite(east) || !isfinite(south) ||
        west < -180 || east > 180 || south < -90 || north > 90) return BMAPS_DEM_UNSUPPORTED;
    return BMAPS_DEM_OK;
}

static int read_metadata(bmaps_dem *r) {
    uint16_t samples = 0, orientation = 0, planar = 0, photometric = 0, extras = 0;
    uint16_t *extra_info = NULL;
    uint32_t depth = 1;
    if (!TIFFGetField(r->tiff, TIFFTAG_IMAGEWIDTH, &r->width) ||
        !TIFFGetField(r->tiff, TIFFTAG_IMAGELENGTH, &r->height) ||
        r->width == 0 || r->height == 0 || r->width > INT_MAX || r->height > INT_MAX)
        return BMAPS_DEM_INVALID;
    TIFFGetFieldDefaulted(r->tiff, TIFFTAG_SAMPLESPERPIXEL, &samples);
    TIFFGetFieldDefaulted(r->tiff, TIFFTAG_BITSPERSAMPLE, &r->bits);
    TIFFGetFieldDefaulted(r->tiff, TIFFTAG_SAMPLEFORMAT, &r->format);
    TIFFGetFieldDefaulted(r->tiff, TIFFTAG_COMPRESSION, &r->compression);
    TIFFGetFieldDefaulted(r->tiff, TIFFTAG_ORIENTATION, &orientation);
    TIFFGetFieldDefaulted(r->tiff, TIFFTAG_PLANARCONFIG, &planar);
    TIFFGetField(r->tiff, TIFFTAG_IMAGEDEPTH, &depth);
    TIFFGetField(r->tiff, TIFFTAG_EXTRASAMPLES, &extras, &extra_info);
    if (!TIFFGetField(r->tiff, TIFFTAG_PHOTOMETRIC, &photometric) ||
        samples != 1 || extras != 0 || orientation != ORIENTATION_TOPLEFT ||
        planar != PLANARCONFIG_CONTIG || photometric != PHOTOMETRIC_MINISBLACK || depth != 1)
        return BMAPS_DEM_UNSUPPORTED;
    if (!((r->format == SAMPLEFORMAT_INT || r->format == SAMPLEFORMAT_UINT) &&
          (r->bits == 8 || r->bits == 16 || r->bits == 32)) &&
        !(r->format == SAMPLEFORMAT_IEEEFP && (r->bits == 32 || r->bits == 64)))
        return BMAPS_DEM_UNSUPPORTED;
    if (r->compression != COMPRESSION_NONE && r->compression != COMPRESSION_LZW &&
        r->compression != COMPRESSION_ADOBE_DEFLATE && r->compression != COMPRESSION_DEFLATE &&
        r->compression != COMPRESSION_PACKBITS) return BMAPS_DEM_UNSUPPORTED;
    if (!TIFFIsCODECConfigured(r->compression)) return BMAPS_DEM_UNSUPPORTED;
    if (TIFFFindField(r->tiff, GDAL_NODATA, TIFF_ANY)) {
        uint32_t count = 0;
        void *data = NULL;
        if (!custom_field(r->tiff, GDAL_NODATA, TIFF_ASCII, &count, &data) || count < 2)
            return BMAPS_DEM_INVALID;
        const char *nodata = data;
        if (memchr(nodata, 0, count) != nodata + count - 1) return BMAPS_DEM_INVALID;
        char *end = NULL;
        locale_t locale = newlocale(LC_NUMERIC_MASK, "C", NULL);
        if (!locale) return BMAPS_DEM_INVALID;
        errno = 0;
        r->nodata = strtod_l(nodata, &end, locale);
        int range_error = errno == ERANGE;
        freelocale(locale);
        if (end == nodata || *end != '\0' || range_error || isinf(r->nodata)) return BMAPS_DEM_INVALID;
        if (r->format == SAMPLEFORMAT_IEEEFP && r->bits == 32) r->nodata = (float)r->nodata;
        r->has_nodata = 1;
    }
    int geography = read_geography(r);
    if (geography != BMAPS_DEM_OK) return geography;
    r->tiled = TIFFIsTiled(r->tiff);
    if (r->tiled) {
        depth = 1;
        TIFFGetField(r->tiff, TIFFTAG_TILEDEPTH, &depth);
        if (depth != 1) return BMAPS_DEM_UNSUPPORTED;
        TIFFGetField(r->tiff, TIFFTAG_TILEWIDTH, &r->block_width);
        TIFFGetField(r->tiff, TIFFTAG_TILELENGTH, &r->block_height);
        r->block_bytes = TIFFTileSize64(r->tiff);
    } else {
        r->block_width = r->width;
        TIFFGetFieldDefaulted(r->tiff, TIFFTAG_ROWSPERSTRIP, &r->block_height);
        if (r->block_height > r->height) r->block_height = r->height;
        r->block_bytes = TIFFStripSize64(r->tiff);
    }
    if (!r->block_width || !r->block_height || !r->block_bytes) return BMAPS_DEM_INVALID;
    if ((uint64_t)r->block_width * r->block_height > BLOCK_LIMIT / (r->bits / 8)) return BMAPS_DEM_LIMIT;
    if (r->block_bytes > BLOCK_LIMIT) return BMAPS_DEM_LIMIT;
    if (r->block_bytes != (uint64_t)r->block_width * r->block_height * (r->bits / 8))
        return BMAPS_DEM_INVALID;
    return BMAPS_DEM_OK;
}

bmaps_dem *bmaps_dem_open(const char *path, int *status) {
    *status = BMAPS_DEM_INVALID;
    bmaps_dem *r = calloc(1, sizeof(*r));
    if (!r) { *status = BMAPS_DEM_LIMIT; return NULL; }
    TIFFOpenOptions *options = TIFFOpenOptionsAlloc();
    if (!options) { free(r); *status = BMAPS_DEM_LIMIT; return NULL; }
    TIFFOpenOptionsSetMaxSingleMemAlloc(options, BLOCK_LIMIT);
    TIFFOpenOptionsSetMaxCumulatedMemAlloc(options, LIBRARY_LIMIT);
    TIFFOpenOptionsSetErrorHandlerExtR(options, error_handler, r);
    TIFFOpenOptionsSetWarningHandlerExtR(options, warning_handler, r);
    /* Unknown GeoTIFF tags use libtiff's per-directory anonymous fields.
       Disable mmap and strip chopping to enforce the actual block limit. */
    r->tiff = TIFFOpenExt(path, "rmc", options);
    TIFFOpenOptionsFree(options);
    if (!r->tiff || r->decode_error) { bmaps_dem_close(r); return NULL; }
    *status = read_metadata(r);
    if (*status != BMAPS_DEM_OK || r->decode_error) {
        if (*status == BMAPS_DEM_OK) *status = BMAPS_DEM_INVALID;
        bmaps_dem_close(r);
        return NULL;
    }
    r->buffer = malloc((size_t)r->block_bytes);
    if (!r->buffer) { *status = BMAPS_DEM_LIMIT; bmaps_dem_close(r); return NULL; }
    return r;
}

void bmaps_dem_metadata(const bmaps_dem *r, double *v) {
    v[0] = r->width; v[1] = r->height; v[2] = r->x; v[3] = r->y;
    v[4] = r->dx; v[5] = r->dy; v[6] = r->point;
    v[7] = r->bits; v[8] = r->format; v[9] = r->compression;
    v[10] = r->tiled; v[11] = (double)r->block_bytes;
}

#define READ_VALUE(type) do { type sample; memcpy(&sample, p, sizeof(sample)); *value = sample; } while (0)

int bmaps_dem_sample(bmaps_dem *r, uint32_t column, uint32_t row, double *value) {
    if (!r || column >= r->width || row >= r->height) return BMAPS_DEM_OUTSIDE;
    uint32_t block = r->tiled ? TIFFComputeTile(r->tiff, column, row, 0, 0) : TIFFComputeStrip(r->tiff, row, 0);
    uint32_t blocks = r->tiled ? TIFFNumberOfTiles(r->tiff) : TIFFNumberOfStrips(r->tiff);
    if (block >= blocks) return BMAPS_DEM_INVALID;
    if (!r->cached || r->cached_block != block) {
        uint64_t *byte_counts = NULL;
        r->cached = 0;
        r->decode_error = 0;
        if (!TIFFGetField(r->tiff, r->tiled ? TIFFTAG_TILEBYTECOUNTS : TIFFTAG_STRIPBYTECOUNTS, &byte_counts) ||
            !byte_counts || !byte_counts[block]) return BMAPS_DEM_INVALID;
        if (byte_counts[block] > BLOCK_LIMIT) return BMAPS_DEM_LIMIT;
        uint64_t expected = r->block_bytes;
        if (!r->tiled) {
            uint32_t remaining = r->height - (row / r->block_height) * r->block_height;
            if (remaining < r->block_height) expected = (uint64_t)remaining * r->width * (r->bits / 8);
        }
        tmsize_t read = r->tiled ? TIFFReadEncodedTile(r->tiff, block, r->buffer, (tmsize_t)expected) :
            TIFFReadEncodedStrip(r->tiff, block, r->buffer, (tmsize_t)expected);
        if (read < 0 || (uint64_t)read != expected || r->decode_error) return BMAPS_DEM_INVALID;
        r->cached = 1;
        r->cached_block = block;
    }
    uint64_t index = (uint64_t)(row % r->block_height) * r->block_width + column % r->block_width;
    const unsigned char *p = r->buffer + index * (r->bits / 8);
    if (r->format == SAMPLEFORMAT_IEEEFP) {
        if (r->bits == 32) READ_VALUE(float); else READ_VALUE(double);
    } else if (r->format == SAMPLEFORMAT_INT) {
        if (r->bits == 8) READ_VALUE(int8_t);
        else if (r->bits == 16) READ_VALUE(int16_t); else READ_VALUE(int32_t);
    } else {
        if (r->bits == 8) READ_VALUE(uint8_t);
        else if (r->bits == 16) READ_VALUE(uint16_t); else READ_VALUE(uint32_t);
    }
    if (!isfinite(*value) || (r->has_nodata && *value == r->nodata)) return BMAPS_DEM_NO_DATA;
    return BMAPS_DEM_OK;
}

void bmaps_dem_close(bmaps_dem *r) {
    if (!r) return;
    if (r->tiff) TIFFClose(r->tiff);
    free(r->buffer);
    free(r);
}
