// The JNI side of AomVideoCodec.kt (CSD-134, AV1 both ways). The codec itself is
// src/nativeInterop/aom/ciris_aom.h, shared with iOS; this only moves bytes.
#include <jni.h>
#include "ciris_aom.h"

#define FN(name) Java_ai_ciris_mobile_shared_calls_media_AomNative_##name

JNIEXPORT jlong JNICALL FN(encOpen)(JNIEnv *env, jclass cls, jint w, jint h, jint fps, jint kbps, jint threads) {
    return (jlong) (intptr_t) ciris_aom_enc_open(w, h, fps, kbps, threads);
}

JNIEXPORT jbyteArray JNICALL FN(encEncode)(JNIEnv *env, jclass cls, jlong handle,
        jbyteArray y, jbyteArray u, jbyteArray v, jlong ptsUs, jboolean key) {
    void *e = (void *) (intptr_t) handle;
    if (!e) return NULL;
    jbyte *py = (*env)->GetByteArrayElements(env, y, NULL);
    jbyte *pu = (*env)->GetByteArrayElements(env, u, NULL);
    jbyte *pv = (*env)->GetByteArrayElements(env, v, NULL);
    uint8_t *out = NULL;
    size_t len = 0;
    int r = ciris_aom_enc_encode(e, (const uint8_t *) py, (const uint8_t *) pu, (const uint8_t *) pv,
                                 (int64_t) ptsUs, key ? 1 : 0, &out, &len);
    (*env)->ReleaseByteArrayElements(env, y, py, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, u, pu, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, v, pv, JNI_ABORT);
    if (r != 0 || !out) { ciris_aom_free(out); return NULL; }
    jbyteArray arr = (*env)->NewByteArray(env, (jsize) len);
    if (arr) (*env)->SetByteArrayRegion(env, arr, 0, (jsize) len, (const jbyte *) out);
    ciris_aom_free(out);
    return arr;
}

JNIEXPORT void JNICALL FN(encClose)(JNIEnv *env, jclass cls, jlong handle) {
    ciris_aom_enc_close((void *) (intptr_t) handle);
}

JNIEXPORT jlong JNICALL FN(decOpen)(JNIEnv *env, jclass cls, jint threads) {
    return (jlong) (intptr_t) ciris_aom_dec_open(threads);
}

// [width(4, big-endian) | height(4) | Y | U | V], or null when no picture came out.
JNIEXPORT jbyteArray JNICALL FN(decDecode)(JNIEnv *env, jclass cls, jlong handle, jbyteArray data) {
    void *d = (void *) (intptr_t) handle;
    if (!d) return NULL;
    jsize n = (*env)->GetArrayLength(env, data);
    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    uint8_t *out = NULL;
    int w = 0, h = 0;
    int r = ciris_aom_dec_decode(d, (const uint8_t *) bytes, (size_t) n, &out, &w, &h);
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
    if (r != 0 || !out) { ciris_aom_free(out); return NULL; }
    size_t size = (size_t) w * h * 3 / 2;
    jbyteArray arr = (*env)->NewByteArray(env, (jsize) (8 + size));
    if (arr) {
        jbyte hdr[8] = { (jbyte) (w >> 24), (jbyte) (w >> 16), (jbyte) (w >> 8), (jbyte) w,
                         (jbyte) (h >> 24), (jbyte) (h >> 16), (jbyte) (h >> 8), (jbyte) h };
        (*env)->SetByteArrayRegion(env, arr, 0, 8, hdr);
        (*env)->SetByteArrayRegion(env, arr, 8, (jsize) size, (const jbyte *) out);
    }
    ciris_aom_free(out);
    return arr;
}

JNIEXPORT void JNICALL FN(decClose)(JNIEnv *env, jclass cls, jlong handle) {
    ciris_aom_dec_close((void *) (intptr_t) handle);
}
