/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

#include "bmaps_dem.h"
#include <jni.h>
#include <stdlib.h>
#include <string.h>

JNIEXPORT jint JNICALL Java_bes_max_bmaps_core_tiff_NativeDem_open(JNIEnv *env, jclass type,
        jbyteArray path, jlongArray output) {
    (void)type;
    if (!path || !output || (*env)->GetArrayLength(env, output) != 1) return BMAPS_DEM_INVALID;
    jlong handle = 0;
    (*env)->SetLongArrayRegion(env, output, 0, 1, &handle);
    if ((*env)->ExceptionCheck(env)) return BMAPS_DEM_INVALID;
    jsize size = (*env)->GetArrayLength(env, path);
    if (size < 1 || size > 4096) return BMAPS_DEM_INVALID;
    char *name = malloc((size_t)size + 1);
    if (!name) return BMAPS_DEM_LIMIT;
    (*env)->GetByteArrayRegion(env, path, 0, size, (jbyte *)name);
    if ((*env)->ExceptionCheck(env) || memchr(name, 0, (size_t)size)) { free(name); return BMAPS_DEM_INVALID; }
    name[size] = 0;
    int status = BMAPS_DEM_INVALID;
    bmaps_dem *r = bmaps_dem_open(name, &status);
    free(name);
    if (!r) return status;
    handle = (jlong)(uintptr_t)r;
    (*env)->SetLongArrayRegion(env, output, 0, 1, &handle);
    if ((*env)->ExceptionCheck(env)) {
        bmaps_dem_close(r);
        return BMAPS_DEM_INVALID;
    }
    return BMAPS_DEM_OK;
}

JNIEXPORT jdoubleArray JNICALL Java_bes_max_bmaps_core_tiff_NativeDem_metadata(JNIEnv *env, jclass type, jlong handle) {
    (void)type;
    if (!handle) return NULL;
    double values[BMAPS_DEM_METADATA_COUNT];
    bmaps_dem_metadata((bmaps_dem *)(uintptr_t)handle, values);
    jdoubleArray result = (*env)->NewDoubleArray(env, BMAPS_DEM_METADATA_COUNT);
    if (result) (*env)->SetDoubleArrayRegion(env, result, 0, BMAPS_DEM_METADATA_COUNT, values);
    return result;
}

JNIEXPORT jint JNICALL Java_bes_max_bmaps_core_tiff_NativeDem_sample(JNIEnv *env, jclass type, jlong handle,
        jint column, jint row, jdoubleArray output) {
    (void)type;
    if (!handle || !output || (*env)->GetArrayLength(env, output) != 1) return BMAPS_DEM_INVALID;
    double value = 0;
    int status = bmaps_dem_sample((bmaps_dem *)(uintptr_t)handle, (uint32_t)column, (uint32_t)row, &value);
    if (status == BMAPS_DEM_OK) (*env)->SetDoubleArrayRegion(env, output, 0, 1, &value);
    return status;
}

JNIEXPORT jint JNICALL Java_bes_max_bmaps_core_tiff_NativeDem_samples(JNIEnv *env, jclass type,
        jlong handle, jintArray columns, jint row, jdoubleArray output) {
    (void)type;
    if (!handle || !columns || !output) return BMAPS_DEM_INVALID;
    jsize count = (*env)->GetArrayLength(env, columns);
    if (count < 1 || count > 8192 || (*env)->GetArrayLength(env, output) != count) return BMAPS_DEM_INVALID;
    int32_t *indices = malloc((size_t)count * sizeof(int32_t));
    double *values = malloc((size_t)count * sizeof(double));
    if (!indices || !values) { free(indices); free(values); return BMAPS_DEM_LIMIT; }
    (*env)->GetIntArrayRegion(env, columns, 0, count, (jint *)indices);
    int status = BMAPS_DEM_INVALID;
    if (!(*env)->ExceptionCheck(env)) {
        status = bmaps_dem_samples((bmaps_dem *)(uintptr_t)handle, indices, (uint32_t)count, row, values);
        if (status == BMAPS_DEM_OK) (*env)->SetDoubleArrayRegion(env, output, 0, count, values);
    }
    free(indices); free(values);
    return status;
}

JNIEXPORT void JNICALL Java_bes_max_bmaps_core_tiff_NativeDem_enableCache(JNIEnv *env, jclass type, jlong handle) {
    (void)env; (void)type;
    if (handle) bmaps_dem_enable_cache((bmaps_dem *)(uintptr_t)handle);
}

JNIEXPORT jdoubleArray JNICALL Java_bes_max_bmaps_core_tiff_NativeDem_metrics(JNIEnv *env, jclass type, jlong handle) {
    (void)type;
    if (!handle) return NULL;
    double values[5];
    bmaps_dem_metrics((bmaps_dem *)(uintptr_t)handle, values);
    jdoubleArray result = (*env)->NewDoubleArray(env, 5);
    if (result) (*env)->SetDoubleArrayRegion(env, result, 0, 5, values);
    return result;
}

JNIEXPORT jint JNICALL Java_bes_max_bmaps_core_tiff_NativeDem_range(JNIEnv *env, jclass type,
        jlong handle, jlong first_block, jdoubleArray output) {
    (void)type;
    if (!handle || !output || first_block < 0 || first_block > UINT32_MAX || (*env)->GetArrayLength(env, output) != 5) return BMAPS_DEM_INVALID;
    double values[5];
    int status = bmaps_dem_range((bmaps_dem *)(uintptr_t)handle, (uint32_t)first_block, values);
    if (status == BMAPS_DEM_OK) (*env)->SetDoubleArrayRegion(env, output, 0, 5, values);
    return status;
}

JNIEXPORT jint JNICALL Java_bes_max_bmaps_core_tiff_NativeDem_grid(JNIEnv *env, jclass type,
        jlong handle, jintArray columns, jintArray rows, jdoubleArray output) {
    (void)type;
    if (!handle || !columns || !rows || !output) return BMAPS_DEM_INVALID;
    jsize width = (*env)->GetArrayLength(env, columns), height = (*env)->GetArrayLength(env, rows);
    if (width < 1 || width > 8192 || height < 1 || height > 1024 || (int64_t)width * height > 131072 ||
        (*env)->GetArrayLength(env, output) != width * height) return BMAPS_DEM_INVALID;
    int32_t *x = malloc((size_t)width * sizeof(int32_t)), *y = malloc((size_t)height * sizeof(int32_t));
    double *values = malloc((size_t)width * height * sizeof(double));
    if (!x || !y || !values) { free(x); free(y); free(values); return BMAPS_DEM_LIMIT; }
    (*env)->GetIntArrayRegion(env, columns, 0, width, (jint *)x);
    if (!(*env)->ExceptionCheck(env)) (*env)->GetIntArrayRegion(env, rows, 0, height, (jint *)y);
    int status = BMAPS_DEM_INVALID;
    if (!(*env)->ExceptionCheck(env)) {
        status = bmaps_dem_grid((bmaps_dem *)(uintptr_t)handle, x, width, y, height, values);
        if (status == BMAPS_DEM_OK) (*env)->SetDoubleArrayRegion(env, output, 0, width * height, values);
    }
    free(x); free(y); free(values);
    return status;
}

JNIEXPORT void JNICALL Java_bes_max_bmaps_core_tiff_NativeDem_close(JNIEnv *env, jclass type, jlong handle) {
    (void)env; (void)type;
    bmaps_dem_close((bmaps_dem *)(uintptr_t)handle);
}
