#include "bmaps_proj.h"
#include <jni.h>
#include <stdint.h>

JNIEXPORT jlong JNICALL Java_bes_max_bmaps_core_proj_NativeProjection_open(JNIEnv *env, jclass type) {
    (void)env; (void)type;
    return (jlong)(uintptr_t)bmaps_projection_open();
}

JNIEXPORT jint JNICALL Java_bes_max_bmaps_core_proj_NativeProjection_transform(JNIEnv *env, jclass type,
        jlong handle, jint source, jint target, jdouble x, jdouble y, jdoubleArray output) {
    (void)type;
    if (!handle || !output || (*env)->GetArrayLength(env, output) != 4) return BMAPS_PROJECTION_ERROR;
    double values[4] = {0};
    int status = bmaps_projection_transform((bmaps_projection *)(uintptr_t)handle, source, target, x, y, values);
    if (status == BMAPS_PROJECTION_OK) (*env)->SetDoubleArrayRegion(env, output, 0, 4, values);
    return status;
}

JNIEXPORT jstring JNICALL Java_bes_max_bmaps_core_proj_NativeProjection_operation(JNIEnv *env, jclass type, jlong handle) {
    (void)type;
    const char *name = bmaps_projection_operation((bmaps_projection *)(uintptr_t)handle);
    return (*env)->NewStringUTF(env, name ? name : "");
}

JNIEXPORT void JNICALL Java_bes_max_bmaps_core_proj_NativeProjection_close(JNIEnv *env, jclass type, jlong handle) {
    (void)env; (void)type;
    bmaps_projection_close((bmaps_projection *)(uintptr_t)handle);
}
