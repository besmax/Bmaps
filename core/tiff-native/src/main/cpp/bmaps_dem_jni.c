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

JNIEXPORT void JNICALL Java_bes_max_bmaps_core_tiff_NativeDem_close(JNIEnv *env, jclass type, jlong handle) {
    (void)env; (void)type;
    bmaps_dem_close((bmaps_dem *)(uintptr_t)handle);
}
