/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

#include "bmaps_proj.h"
#include <proj.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#ifdef __ANDROID__
#include <android/log.h>
#endif

typedef struct {
    PJ *operation;
    PJ *normalized;
    double west, south, east, north, accuracy;
    int requires_epoch;
} candidate;

struct bmaps_projection {
    PJ_CONTEXT *context;
    candidate *candidates;
    int count, source, target, selected;
    double source_area[4], target_area[4];
};

static void log_error(void *user, int level, const char *message) {
    (void)user;
    if (level > PJ_LOG_ERROR) return;
#ifdef __ANDROID__
    __android_log_write(ANDROID_LOG_ERROR, "BmapsCoordinates", message);
#else
    fprintf(stderr, "[BmapsCoordinates] ERROR %s\n", message);
#endif
}

static int supported(int code) {
    return code == 4326 || code == 4284 || code == 9475;
}

static int contains(double west, double south, double east, double north, double x, double y) {
    return y >= south && y <= north && (west <= east ? x >= west && x <= east : x >= west || x <= east);
}

static void clear_candidates(bmaps_projection *reader) {
    for (int i = 0; i < reader->count; ++i) {
        proj_destroy(reader->candidates[i].normalized);
        proj_destroy(reader->candidates[i].operation);
    }
    free(reader->candidates);
    reader->candidates = NULL;
    reader->count = 0;
    reader->source = reader->target = 0;
    reader->selected = -1;
}

static int prepare(bmaps_projection *reader, int source, int target) {
    clear_candidates(reader);
    char source_name[32], target_name[32];
    snprintf(source_name, sizeof(source_name), "EPSG:%d", source);
    snprintf(target_name, sizeof(target_name), "EPSG:%d", target);
    PJ *src = proj_create(reader->context, source_name);
    PJ *dst = proj_create(reader->context, target_name);
    PJ_OPERATION_FACTORY_CONTEXT *factory = proj_create_operation_factory_context(reader->context, "EPSG");
    PJ_OBJ_LIST *operations = NULL;
    int result = BMAPS_PROJECTION_ERROR;
    if (!src || !dst || !factory) goto cleanup;
    if (!proj_get_area_of_use(reader->context, src, &reader->source_area[0], &reader->source_area[1],
            &reader->source_area[2], &reader->source_area[3], NULL) ||
        !proj_get_area_of_use(reader->context, dst, &reader->target_area[0], &reader->target_area[1],
            &reader->target_area[2], &reader->target_area[3], NULL)) goto cleanup;
    proj_operation_factory_context_set_allow_ballpark_transformations(reader->context, factory, 0);
    proj_operation_factory_context_set_discard_superseded(reader->context, factory, 1);
    proj_operation_factory_context_set_crs_extent_use(reader->context, factory, PJ_CRS_EXTENT_NONE);
    proj_operation_factory_context_set_grid_availability_use(reader->context, factory,
        PROJ_GRID_AVAILABILITY_DISCARD_OPERATION_IF_MISSING_GRID);
    operations = proj_create_operations(reader->context, src, dst, factory);
    if (!operations) goto cleanup;
    int count = proj_list_get_count(operations);
    if (count < 0 || count > 512) goto cleanup;
    reader->candidates = calloc((size_t)(count ? count : 1), sizeof(candidate));
    if (!reader->candidates) goto cleanup;
    for (int i = 0; i < count; ++i) {
        PJ *operation = proj_list_get(reader->context, operations, i);
        if (!operation) continue;
        candidate entry = {0};
        entry.accuracy = proj_coordoperation_get_accuracy(reader->context, operation);
        if (proj_coordoperation_has_ballpark_transformation(reader->context, operation) ||
            proj_coordoperation_get_grid_used_count(reader->context, operation) != 0 ||
            !isfinite(entry.accuracy) || entry.accuracy < 0 ||
            !proj_get_area_of_use(reader->context, operation, &entry.west, &entry.south, &entry.east, &entry.north, NULL) ||
            !proj_coordoperation_is_instantiable(reader->context, operation)) {
            proj_destroy(operation);
            continue;
        }
        entry.requires_epoch = proj_coordoperation_requires_per_coordinate_input_time(reader->context, operation);
        entry.operation = operation;
        reader->candidates[reader->count++] = entry;
    }
    reader->source = source;
    reader->target = target;
    result = BMAPS_PROJECTION_OK;
cleanup:
    proj_list_destroy(operations);
    proj_operation_factory_context_destroy(factory);
    proj_destroy(src);
    proj_destroy(dst);
    return result;
}

bmaps_projection *bmaps_projection_open(void) {
    bmaps_projection *reader = calloc(1, sizeof(*reader));
    if (!reader) return NULL;
    reader->selected = -1;
    reader->context = proj_context_create();
    if (!reader->context) { free(reader); return NULL; }
    proj_log_func(reader->context, NULL, log_error);
    proj_context_set_enable_network(reader->context, 0);
    return reader;
}

int bmaps_projection_transform(bmaps_projection *reader, int source, int target,
        double longitude, double latitude, double *output) {
    if (!reader || !output) return BMAPS_PROJECTION_ERROR;
    reader->selected = -1;
    if (!supported(source) || !supported(target)) return BMAPS_PROJECTION_UNSUPPORTED;
    if (!isfinite(longitude) || !isfinite(latitude) || longitude < -180 || longitude > 180 ||
        latitude < -90 || latitude > 90) return BMAPS_PROJECTION_OUTSIDE;
    if (reader->source != source || reader->target != target) {
        int status = prepare(reader, source, target);
        if (status != BMAPS_PROJECTION_OK) return status;
    }
    if (!contains(reader->source_area[0], reader->source_area[1], reader->source_area[2], reader->source_area[3], longitude, latitude) ||
        !contains(reader->target_area[0], reader->target_area[1], reader->target_area[2], reader->target_area[3], longitude, latitude))
        return BMAPS_PROJECTION_OUTSIDE;
    if (source == target) {
        output[0] = longitude; output[1] = latitude; output[2] = 0; output[3] = -1;
        return BMAPS_PROJECTION_OK;
    }
    int selected = -1;
    for (int i = 0; i < reader->count; ++i) {
        candidate *entry = &reader->candidates[i];
        if (contains(entry->west, entry->south, entry->east, entry->north, longitude, latitude) &&
            (selected < 0 || entry->accuracy < reader->candidates[selected].accuracy)) selected = i;
    }
    if (selected < 0) return BMAPS_PROJECTION_MISSING_OPERATION;
    candidate *entry = &reader->candidates[selected];
    if (!entry->normalized) entry->normalized = proj_normalize_for_visualization(reader->context, entry->operation);
    if (!entry->normalized) return BMAPS_PROJECTION_ERROR;
    proj_errno_reset(entry->normalized);
    /* Epoch-dependent geographic 2D operations use their EPSG reference epoch, 2010.0. */
    double epoch = entry->requires_epoch ? 2010.0 : HUGE_VAL;
    PJ_COORD result = proj_trans(entry->normalized, PJ_FWD, proj_coord(longitude, latitude, 0, epoch));
    if (proj_errno(entry->normalized) || !isfinite(result.xy.x) || !isfinite(result.xy.y)) return BMAPS_PROJECTION_ERROR;
    if (result.xy.x < -180 || result.xy.x > 180 || result.xy.y < -90 || result.xy.y > 90) return BMAPS_PROJECTION_OUTSIDE;
    output[0] = result.xy.x; output[1] = result.xy.y; output[2] = entry->accuracy;
    output[3] = entry->requires_epoch ? epoch : -1;
    reader->selected = selected;
    return BMAPS_PROJECTION_OK;
}

const char *bmaps_projection_operation(const bmaps_projection *reader) {
    if (!reader || reader->selected < 0) return "Identity";
    return proj_get_name(reader->candidates[reader->selected].operation);
}

void bmaps_projection_close(bmaps_projection *reader) {
    if (!reader) return;
    clear_candidates(reader);
    proj_context_destroy(reader->context);
    free(reader);
}
