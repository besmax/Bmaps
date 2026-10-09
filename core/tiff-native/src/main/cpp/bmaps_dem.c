/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

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
#include <stdio.h>
#include <stdarg.h>
#ifdef __ANDROID__
#include <android/log.h>
#endif

#define BLOCK_LIMIT (8 * 1024 * 1024)
#define LIBRARY_LIMIT (32 * 1024 * 1024)
#define CACHE_LIMIT (16 * 1024 * 1024)
#define CACHE_SLOTS 8
#define MODEL_SCALE 33550
#define MODEL_TIEPOINT 33922
#define MODEL_TRANSFORM 34264
#define GEO_KEYS 34735
#define GEO_DOUBLES 34736
#define GDAL_NODATA 42113
#define GDAL_METADATA 42112

struct cached_dem_block {
    unsigned char *data;
    uint32_t block;
    uint64_t used;
    int valid;
};

struct bmaps_dem {
    TIFF *tiff;
    uint32_t width, height, block_width, block_height;
    uint16_t bits, format, compression;
    int tiled, point, has_nodata, decode_error;
    double x, y, dx, dy, nodata;
    uint64_t block_bytes;
    struct cached_dem_block cache[CACHE_SLOTS];
    unsigned int cache_slots;
    uint64_t cache_clock, cache_hits, decoded_blocks, decoded_bytes, sampled_values, grid_calls;
    unsigned int logged_errors, logged_warnings;
};

static int custom_field(TIFF *tiff, uint32_t tag, TIFFDataType type, uint32_t *count, void **data) {
    const TIFFField *field = TIFFFindField(tiff, tag, TIFF_ANY);
    if (!field || TIFFFieldDataType(field) != type || !TIFFFieldPassCount(field) ||
        TIFFFieldReadCount(field) != TIFF_VARIABLE2) return 0;
    return TIFFGetField(tiff, tag, count, data);
}

static void diagnostic(bmaps_dem *r, int error, const char *message) {
    if (r) {
        unsigned int *count = error ? &r->logged_errors : &r->logged_warnings;
        if (*count > 16) return;
        if ((*count)++ == 16) message = "Further native diagnostics of this severity suppressed for this reader";
    }
#ifdef __ANDROID__
    __android_log_write(error ? ANDROID_LOG_ERROR : ANDROID_LOG_WARN, "BmapsElevation", message);
#else
    fprintf(stderr, "[BmapsElevation] %s %s\n", error ? "ERROR" : "WARN", message);
#endif
}

static int native_failure(bmaps_dem *r, int status, const char *stage, int line) {
    char message[512];
    snprintf(message, sizeof(message),
        "native_failure stage=%s line=%d status=%d width=%u height=%u bits=%u format=%u compression=%u blockBytes=%llu",
        stage, line, status, r->width, r->height, r->bits, r->format, r->compression,
        (unsigned long long)r->block_bytes);
    diagnostic(r, 1, message);
    return status;
}

static void tiff_diagnostic(bmaps_dem *r, int error, const char *module, const char *format, va_list args) {
    char detail[1024], message[1200];
    vsnprintf(detail, sizeof(detail), format, args);
    snprintf(message, sizeof(message), "libtiff module=%s: %s", module ? module : "unknown", detail);
    diagnostic(r, error, message);
}

static int error_handler(TIFF *tiff, void *user, const char *module, const char *format, va_list args) {
    (void)tiff;
    bmaps_dem *r = user;
    r->decode_error = 1;
    tiff_diagnostic(r, 1, module, format, args);
    return 1;
}

static int warning_handler(TIFF *tiff, void *user, const char *module, const char *format, va_list args) {
    (void)tiff;
    tiff_diagnostic(user, 0, module, format, args);
    return 1;
}

static int read_geography(bmaps_dem *r) {
    uint32_t count = 0;
    void *key_data = NULL;
    if (!custom_field(r->tiff, GEO_KEYS, TIFF_SHORT, &count, &key_data)) return native_failure(r, BMAPS_DEM_UNSUPPORTED, __func__, __LINE__);
    const uint16_t *keys = key_data;
    if (count < 4 ||
        keys[0] != 1 || keys[1] != 1 || keys[2] > 1 || count != 4 + 4u * keys[3])
        return native_failure(r, BMAPS_DEM_UNSUPPORTED, __func__, __LINE__);
    int model = 0, crs = 0, raster = 0;
    uint16_t previous = 0;
    for (uint32_t i = 4; i < count; i += 4) {
        uint16_t key = keys[i], location = keys[i + 1], length = keys[i + 2], value = keys[i + 3];
        if (key <= previous) return native_failure(r, BMAPS_DEM_INVALID, __func__, __LINE__);
        previous = key;
        if (key == 1024 || key == 1025 || key == 2048 || key == 2054) {
            if (location != 0 || length != 1) return native_failure(r, BMAPS_DEM_UNSUPPORTED, __func__, __LINE__);
            if (key == 1024) model = value;
            if (key == 1025) raster = value;
            if (key == 2048) crs = value;
            if (key == 2054 && value != 9102) return native_failure(r, BMAPS_DEM_UNSUPPORTED, __func__, __LINE__);
        } else if (key == 2057 || key == 2059) {
            uint32_t double_count = 0;
            void *parameter_data = NULL;
            if (location != GEO_DOUBLES || length != 1 ||
                !custom_field(r->tiff, GEO_DOUBLES, TIFF_DOUBLE, &double_count, &parameter_data) || value >= double_count)
                return native_failure(r, BMAPS_DEM_UNSUPPORTED, __func__, __LINE__);
            const double *parameters = parameter_data;
            double expected = key == 2057 ? 6378137.0 : 298.257223563;
            if (!isfinite(parameters[value]) || fabs(parameters[value] - expected) > 1e-9)
                return native_failure(r, BMAPS_DEM_UNSUPPORTED, __func__, __LINE__);
        } else if (key != 1026 && key != 2049) {
            /* Do not silently ignore custom datum, projection or vertical definitions. */
            return native_failure(r, BMAPS_DEM_UNSUPPORTED, __func__, __LINE__);
        }
    }
    if (model != 2 || crs != 4326 || (raster != 1 && raster != 2)) return native_failure(r, BMAPS_DEM_UNSUPPORTED, __func__, __LINE__);
    r->point = raster == 2;
    void *scale_data = NULL, *tie_data = NULL;
    uint32_t scale_count = 0, tie_count = 0;
    if (TIFFFindField(r->tiff, MODEL_TRANSFORM, TIFF_ANY) ||
        !custom_field(r->tiff, MODEL_SCALE, TIFF_DOUBLE, &scale_count, &scale_data) || scale_count != 3 ||
        !custom_field(r->tiff, MODEL_TIEPOINT, TIFF_DOUBLE, &tie_count, &tie_data) || tie_count != 6)
        return native_failure(r, BMAPS_DEM_UNSUPPORTED, __func__, __LINE__);
    const double *scale = scale_data, *tie = tie_data;
    for (int i = 0; i < 3; i++) if (!isfinite(scale[i])) return native_failure(r, BMAPS_DEM_INVALID, __func__, __LINE__);
    for (int i = 0; i < 6; i++) if (!isfinite(tie[i])) return native_failure(r, BMAPS_DEM_INVALID, __func__, __LINE__);
    if (scale[0] <= 0 || scale[1] <= 0 || scale[2] != 0 || tie[2] != 0 || tie[5] != 0)
        return native_failure(r, BMAPS_DEM_UNSUPPORTED, __func__, __LINE__);
    r->dx = scale[0];
    r->dy = -scale[1];
    r->x = tie[3] - tie[0] * r->dx;
    r->y = tie[4] - tie[1] * r->dy;
    double west = r->x - (r->point ? 0.5 * r->dx : 0);
    double north = r->y - (r->point ? 0.5 * r->dy : 0);
    double east = west + r->width * r->dx;
    double south = north + r->height * r->dy;
    if (!isfinite(west) || !isfinite(north) || !isfinite(east) || !isfinite(south) ||
        west < -180 || east > 180 || south < -90 || north > 90) return native_failure(r, BMAPS_DEM_UNSUPPORTED, __func__, __LINE__);
    return BMAPS_DEM_OK;
}

static int read_metadata(bmaps_dem *r) {
    uint16_t samples = 0, orientation = 0, planar = 0, photometric = 0, extras = 0;
    uint16_t *extra_info = NULL;
    uint32_t depth = 1;
    if (!TIFFGetField(r->tiff, TIFFTAG_IMAGEWIDTH, &r->width) ||
        !TIFFGetField(r->tiff, TIFFTAG_IMAGELENGTH, &r->height) ||
        r->width == 0 || r->height == 0 || r->width > INT_MAX || r->height > INT_MAX)
        return native_failure(r, BMAPS_DEM_INVALID, __func__, __LINE__);
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
        return native_failure(r, BMAPS_DEM_UNSUPPORTED, __func__, __LINE__);
    if (!((r->format == SAMPLEFORMAT_INT || r->format == SAMPLEFORMAT_UINT) &&
          (r->bits == 8 || r->bits == 16 || r->bits == 32)) &&
        !(r->format == SAMPLEFORMAT_IEEEFP && (r->bits == 32 || r->bits == 64)))
        return native_failure(r, BMAPS_DEM_UNSUPPORTED, __func__, __LINE__);
    if (r->compression != COMPRESSION_NONE && r->compression != COMPRESSION_LZW &&
        r->compression != COMPRESSION_ADOBE_DEFLATE && r->compression != COMPRESSION_DEFLATE &&
        r->compression != COMPRESSION_PACKBITS) return native_failure(r, BMAPS_DEM_UNSUPPORTED, __func__, __LINE__);
    if (!TIFFIsCODECConfigured(r->compression)) return native_failure(r, BMAPS_DEM_UNSUPPORTED, __func__, __LINE__);
    if (TIFFFindField(r->tiff, GDAL_NODATA, TIFF_ANY)) {
        uint32_t count = 0;
        void *data = NULL;
        if (!custom_field(r->tiff, GDAL_NODATA, TIFF_ASCII, &count, &data) || count < 2)
            return native_failure(r, BMAPS_DEM_INVALID, __func__, __LINE__);
        const char *nodata = data;
        if (memchr(nodata, 0, count) != nodata + count - 1) return native_failure(r, BMAPS_DEM_INVALID, __func__, __LINE__);
        char *end = NULL;
        locale_t locale = newlocale(LC_NUMERIC_MASK, "C", NULL);
        if (!locale) return native_failure(r, BMAPS_DEM_INVALID, __func__, __LINE__);
        errno = 0;
        r->nodata = strtod_l(nodata, &end, locale);
        int range_error = errno == ERANGE;
        freelocale(locale);
        if (end == nodata || *end != '\0' || range_error || isinf(r->nodata)) return native_failure(r, BMAPS_DEM_INVALID, __func__, __LINE__);
        if (r->format == SAMPLEFORMAT_IEEEFP && r->bits == 32) r->nodata = (float)r->nodata;
        r->has_nodata = 1;
    }
    int geography = read_geography(r);
    if (geography != BMAPS_DEM_OK) return geography;
    r->tiled = TIFFIsTiled(r->tiff);
    if (r->tiled) {
        depth = 1;
        TIFFGetField(r->tiff, TIFFTAG_TILEDEPTH, &depth);
        if (depth != 1) return native_failure(r, BMAPS_DEM_UNSUPPORTED, __func__, __LINE__);
        TIFFGetField(r->tiff, TIFFTAG_TILEWIDTH, &r->block_width);
        TIFFGetField(r->tiff, TIFFTAG_TILELENGTH, &r->block_height);
        r->block_bytes = TIFFTileSize64(r->tiff);
    } else {
        r->block_width = r->width;
        TIFFGetFieldDefaulted(r->tiff, TIFFTAG_ROWSPERSTRIP, &r->block_height);
        if (r->block_height > r->height) r->block_height = r->height;
        r->block_bytes = TIFFStripSize64(r->tiff);
    }
    if (!r->block_width || !r->block_height || !r->block_bytes) return native_failure(r, BMAPS_DEM_INVALID, __func__, __LINE__);
    if ((uint64_t)r->block_width * r->block_height > BLOCK_LIMIT / (r->bits / 8)) return native_failure(r, BMAPS_DEM_LIMIT, __func__, __LINE__);
    if (r->block_bytes > BLOCK_LIMIT) return native_failure(r, BMAPS_DEM_LIMIT, __func__, __LINE__);
    if (r->block_bytes != (uint64_t)r->block_width * r->block_height * (r->bits / 8))
        return native_failure(r, BMAPS_DEM_INVALID, __func__, __LINE__);
    return BMAPS_DEM_OK;
}

bmaps_dem *bmaps_dem_open(const char *path, int *status) {
    *status = BMAPS_DEM_INVALID;
    bmaps_dem *r = calloc(1, sizeof(*r));
    if (!r) { *status = BMAPS_DEM_LIMIT; diagnostic(NULL, 1, "dem_open: reader allocation failed"); return NULL; }
    TIFFOpenOptions *options = TIFFOpenOptionsAlloc();
    if (!options) { diagnostic(r, 1, "dem_open: options allocation failed"); free(r); *status = BMAPS_DEM_LIMIT; return NULL; }
    TIFFOpenOptionsSetMaxSingleMemAlloc(options, BLOCK_LIMIT);
    TIFFOpenOptionsSetMaxCumulatedMemAlloc(options, LIBRARY_LIMIT);
    TIFFOpenOptionsSetErrorHandlerExtR(options, error_handler, r);
    TIFFOpenOptionsSetWarningHandlerExtR(options, warning_handler, r);
    /* Unknown GeoTIFF tags use libtiff's per-directory anonymous fields.
       Disable mmap and strip chopping to enforce the actual block limit. */
    r->tiff = TIFFOpenExt(path, "rmc", options);
    TIFFOpenOptionsFree(options);
    if (!r->tiff || r->decode_error) {
        native_failure(r, *status, "TIFFOpenExt", __LINE__);
        bmaps_dem_close(r);
        return NULL;
    }
    *status = read_metadata(r);
    if (*status != BMAPS_DEM_OK || r->decode_error) {
        if (*status == BMAPS_DEM_OK) *status = BMAPS_DEM_INVALID;
        bmaps_dem_close(r);
        return NULL;
    }
    r->cache_slots = 1;
    return r;
}

void bmaps_dem_metadata(const bmaps_dem *r, double *v) {
    v[0] = r->width; v[1] = r->height; v[2] = r->x; v[3] = r->y;
    v[4] = r->dx; v[5] = r->dy; v[6] = r->point;
    v[7] = r->bits; v[8] = r->format; v[9] = r->compression;
    v[10] = r->tiled; v[11] = (double)r->block_bytes;
    v[12] = TIFFFindField(r->tiff, GDAL_METADATA, TIFF_ANY) != NULL;
}

static int read_block(bmaps_dem *r, uint32_t block, const unsigned char **data) {
    uint32_t blocks = r->tiled ? TIFFNumberOfTiles(r->tiff) : TIFFNumberOfStrips(r->tiff);
    if (block >= blocks) return native_failure(r, BMAPS_DEM_INVALID, __func__, __LINE__);
    struct cached_dem_block *entry = NULL;
    for (unsigned int i = 0; i < r->cache_slots; ++i) {
        if (r->cache[i].valid && r->cache[i].block == block) {
            r->cache[i].used = ++r->cache_clock;
            r->cache_hits++;
            *data = r->cache[i].data;
            return BMAPS_DEM_OK;
        }
        if (!entry || (!r->cache[i].valid && entry->valid) ||
            (r->cache[i].valid == entry->valid && r->cache[i].used < entry->used)) entry = &r->cache[i];
    }
    entry->valid = 0;
    r->decode_error = 0;
    uint64_t *byte_counts = NULL;
    if (!TIFFGetField(r->tiff, r->tiled ? TIFFTAG_TILEBYTECOUNTS : TIFFTAG_STRIPBYTECOUNTS, &byte_counts) ||
        !byte_counts || !byte_counts[block]) return native_failure(r, BMAPS_DEM_INVALID, __func__, __LINE__);
    if (byte_counts[block] > BLOCK_LIMIT) return native_failure(r, BMAPS_DEM_LIMIT, __func__, __LINE__);
    if (!entry->data) {
        entry->data = malloc((size_t)r->block_bytes);
        if (!entry->data) return native_failure(r, BMAPS_DEM_LIMIT, __func__, __LINE__);
    }
    uint64_t expected = r->block_bytes;
    if (!r->tiled) {
        uint32_t first_row = block * r->block_height;
        uint32_t remaining = r->height - first_row;
        if (remaining < r->block_height) expected = (uint64_t)remaining * r->width * (r->bits / 8);
    }
    tmsize_t read = r->tiled ? TIFFReadEncodedTile(r->tiff, block, entry->data, (tmsize_t)expected) :
        TIFFReadEncodedStrip(r->tiff, block, entry->data, (tmsize_t)expected);
    if (read < 0 || (uint64_t)read != expected || r->decode_error) return native_failure(r, BMAPS_DEM_INVALID, __func__, __LINE__);
    entry->valid = 1;
    entry->block = block;
    entry->used = ++r->cache_clock;
    r->decoded_blocks++;
    r->decoded_bytes += expected;
    *data = entry->data;
    return BMAPS_DEM_OK;
}

#define READ_VALUE(type) do { type sample; memcpy(&sample, p, sizeof(sample)); *value = sample; } while (0)

static int read_value(bmaps_dem *r, const unsigned char *p, double *value) {
    if (r->format == SAMPLEFORMAT_IEEEFP) {
        if (r->bits == 32) READ_VALUE(float); else READ_VALUE(double);
    } else if (r->format == SAMPLEFORMAT_INT) {
        if (r->bits == 8) READ_VALUE(int8_t);
        else if (r->bits == 16) READ_VALUE(int16_t); else READ_VALUE(int32_t);
    } else {
        if (r->bits == 8) READ_VALUE(uint8_t);
        else if (r->bits == 16) READ_VALUE(uint16_t); else READ_VALUE(uint32_t);
    }
    r->sampled_values++;
    if (!isfinite(*value) || (r->has_nodata && *value == r->nodata)) return BMAPS_DEM_NO_DATA;
    return BMAPS_DEM_OK;
}

int bmaps_dem_sample(bmaps_dem *r, uint32_t column, uint32_t row, double *value) {
    if (!r || !value || column >= r->width || row >= r->height) return BMAPS_DEM_OUTSIDE;
    uint32_t block = r->tiled ? TIFFComputeTile(r->tiff, column, row, 0, 0) : TIFFComputeStrip(r->tiff, row, 0);
    const unsigned char *data = NULL;
    int status = read_block(r, block, &data);
    if (status != BMAPS_DEM_OK) return status;
    uint64_t index = (uint64_t)(row % r->block_height) * r->block_width + column % r->block_width;
    return read_value(r, data + index * (r->bits / 8), value);
}

void bmaps_dem_enable_cache(bmaps_dem *r) {
    if (!r) return;
    uint64_t slots = CACHE_LIMIT / r->block_bytes;
    r->cache_slots = (unsigned int)(slots < CACHE_SLOTS ? slots : CACHE_SLOTS);
}

void bmaps_dem_metrics(const bmaps_dem *r, double *values) {
    values[0] = (double)r->cache_hits;
    values[1] = (double)r->decoded_blocks;
    values[2] = (double)r->decoded_bytes;
    values[3] = (double)r->sampled_values;
    values[4] = (double)r->grid_calls;
}

struct grid_sample {
    uint32_t block;
    uint32_t output;
    uint64_t offset;
};

static int compare_grid_samples(const void *left, const void *right) {
    const struct grid_sample *a = left, *b = right;
    return (a->block > b->block) - (a->block < b->block);
}

int bmaps_dem_grid(bmaps_dem *r, const int32_t *columns, uint32_t column_count,
        const int32_t *rows, uint32_t row_count, double *values) {
    if (!r || !columns || !rows || !values || !column_count || !row_count || column_count > 8192 ||
        row_count > 1024 || (uint64_t)column_count * row_count > 131072) return BMAPS_DEM_INVALID;
    uint32_t size = column_count * row_count, count = 0;
    struct grid_sample *samples = malloc((size_t)size * sizeof(*samples));
    if (!samples) return BMAPS_DEM_LIMIT;
    r->grid_calls++;
    uint64_t across = ((uint64_t)r->width + r->block_width - 1) / r->block_width;
    for (uint32_t row = 0; row < row_count; ++row) for (uint32_t column = 0; column < column_count; ++column) {
        uint32_t output = row * column_count + column;
        uint32_t x = (uint32_t)columns[column], y = (uint32_t)rows[row];
        values[output] = NAN;
        if (x >= r->width || y >= r->height) continue;
        samples[count].block = r->tiled ? (uint32_t)((y / r->block_height) * across + x / r->block_width) : y / r->block_height;
        samples[count].output = output;
        samples[count].offset = ((uint64_t)(y % r->block_height) * r->block_width + x % r->block_width) * (r->bits / 8);
        count++;
    }
    qsort(samples, count, sizeof(*samples), compare_grid_samples);
    const unsigned char *data = NULL;
    uint32_t block = UINT32_MAX;
    int status = BMAPS_DEM_OK;
    for (uint32_t index = 0; index < count; ++index) {
        if (!data || block != samples[index].block) {
            block = samples[index].block;
            status = read_block(r, block, &data);
            if (status != BMAPS_DEM_OK) break;
        }
        double sample = NAN;
        if (read_value(r, data + samples[index].offset, &sample) == BMAPS_DEM_OK) values[samples[index].output] = sample;
    }
    free(samples);
    return status;
}

int bmaps_dem_range(bmaps_dem *r, uint32_t first_block, double *values) {
    if (!r || !values) return BMAPS_DEM_INVALID;
    uint32_t blocks = r->tiled ? TIFFNumberOfTiles(r->tiff) : TIFFNumberOfStrips(r->tiff);
    if (first_block >= blocks) return BMAPS_DEM_INVALID;
    uint64_t count = 0;
    double minimum = INFINITY, maximum = -INFINITY;
    uint32_t cursor = first_block;
    uint64_t work_bytes = 0;
    while (cursor < blocks && work_bytes + r->block_bytes <= CACHE_LIMIT && cursor - first_block < 8) {
        const unsigned char *data = NULL;
        int status = read_block(r, cursor, &data);
        if (status != BMAPS_DEM_OK) return status;
        uint64_t across = ((uint64_t)r->width + r->block_width - 1) / r->block_width;
        uint32_t first_column = r->tiled ? (uint32_t)((cursor % across) * r->block_width) : 0;
        uint32_t first_row = r->tiled ? (uint32_t)((cursor / across) * r->block_height) : cursor * r->block_height;
        uint32_t columns = r->width - first_column < r->block_width ? r->width - first_column : r->block_width;
        uint32_t rows = r->height - first_row < r->block_height ? r->height - first_row : r->block_height;
        for (uint32_t row = 0; row < rows; ++row) for (uint32_t column = 0; column < columns; ++column) {
            uint64_t index = (uint64_t)row * r->block_width + column;
            double sample = NAN;
            if (read_value(r, data + index * (r->bits / 8), &sample) == BMAPS_DEM_OK) {
                if (sample < minimum) minimum = sample;
                if (sample > maximum) maximum = sample;
                count++;
            }
        }
        work_bytes += r->block_bytes;
        cursor++;
    }
    values[0] = cursor; values[1] = blocks; values[2] = minimum; values[3] = maximum; values[4] = (double)count;
    return BMAPS_DEM_OK;
}

int bmaps_dem_samples(bmaps_dem *r, const int32_t *columns, uint32_t count, int32_t row, double *values) {
    if (!r || !columns || !values || !count || count > 8192) return BMAPS_DEM_INVALID;
    for (uint32_t i = 0; i < count; ++i) {
        double value = NAN;
        int status = bmaps_dem_sample(r, (uint32_t)columns[i], (uint32_t)row, &value);
        if (status != BMAPS_DEM_OK && status != BMAPS_DEM_NO_DATA && status != BMAPS_DEM_OUTSIDE) return status;
        values[i] = status == BMAPS_DEM_OK ? value : NAN;
    }
    return BMAPS_DEM_OK;
}

void bmaps_dem_close(bmaps_dem *r) {
    if (!r) return;
    if (r->tiff) TIFFClose(r->tiff);
    for (unsigned int i = 0; i < CACHE_SLOTS; ++i) free(r->cache[i].data);
    free(r);
}
