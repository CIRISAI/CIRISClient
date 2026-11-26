// AV1 for Android calls (CSD-134): libaom, real-time encode and decode, behind a
// small JNI surface used by AomVideoCodec.kt. One temporal unit in or out per call.
// Frames are planar I420, 8-bit, as every platform's VideoFrame is.
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "aom/aom_decoder.h"
#include "aom/aom_encoder.h"
#include "aom/aomcx.h"
#include "aom/aomdx.h"

typedef struct {
    aom_codec_ctx_t ctx;
    aom_image_t img;
    int w, h;
} Enc;

JNIEXPORT jlong JNICALL
Java_ai_ciris_mobile_shared_calls_media_AomNative_encOpen(JNIEnv *env, jclass cls, jint w, jint h, jint fps, jint kbps, jint threads) {
    aom_codec_iface_t *iface = aom_codec_av1_cx();
    aom_codec_enc_cfg_t cfg;
    if (aom_codec_enc_config_default(iface, &cfg, AOM_USAGE_REALTIME) != AOM_CODEC_OK) return 0;
    cfg.g_w = (unsigned) w;
    cfg.g_h = (unsigned) h;
    cfg.g_timebase.num = 1;
    cfg.g_timebase.den = 1000000; // microseconds, as VideoFrame.timestampUs
    cfg.rc_target_bitrate = (unsigned) kbps;
    cfg.rc_end_usage = AOM_CBR;
    cfg.g_lag_in_frames = 0; // a call never waits on a later frame
    cfg.g_threads = (unsigned) threads;
    cfg.g_error_resilient = 0;
    cfg.kf_mode = AOM_KF_AUTO;
    cfg.kf_max_dist = 9999; // keyframes are asked for by the caller
    Enc *e = calloc(1, sizeof(Enc));
    if (!e) return 0;
    if (aom_codec_enc_init(&e->ctx, iface, &cfg, 0) != AOM_CODEC_OK) { free(e); return 0; }
    aom_codec_control(&e->ctx, AOME_SET_CPUUSED, 10);
    aom_codec_control(&e->ctx, AV1E_SET_ROW_MT, 1);
    aom_codec_control(&e->ctx, AV1E_SET_AQ_MODE, 3);
    aom_codec_control(&e->ctx, AV1E_SET_ENABLE_ORDER_HINT, 0);
    aom_codec_control(&e->ctx, AV1E_SET_DELTAQ_MODE, 0);
    if (!aom_img_alloc(&e->img, AOM_IMG_FMT_I420, (unsigned) w, (unsigned) h, 16)) {
        aom_codec_destroy(&e->ctx); free(e); return 0;
    }
    e->w = w; e->h = h;
    return (jlong) (intptr_t) e;
}

static void copy_plane(uint8_t *dst, int dstStride, const jbyte *src, int w, int h) {
    for (int r = 0; r < h; r++) memcpy(dst + (size_t) r * dstStride, src + (size_t) r * w, (size_t) w);
}

JNIEXPORT jbyteArray JNICALL
Java_ai_ciris_mobile_shared_calls_media_AomNative_encEncode(JNIEnv *env, jclass cls, jlong handle,
        jbyteArray y, jbyteArray u, jbyteArray v, jlong ptsUs, jboolean key) {
    Enc *e = (Enc *) (intptr_t) handle;
    if (!e) return NULL;
    jbyte *py = (*env)->GetPrimitiveArrayCritical(env, y, NULL);
    copy_plane(e->img.planes[0], e->img.stride[0], py, e->w, e->h);
    (*env)->ReleasePrimitiveArrayCritical(env, y, py, JNI_ABORT);
    jbyte *pu = (*env)->GetPrimitiveArrayCritical(env, u, NULL);
    copy_plane(e->img.planes[1], e->img.stride[1], pu, e->w / 2, e->h / 2);
    (*env)->ReleasePrimitiveArrayCritical(env, u, pu, JNI_ABORT);
    jbyte *pv = (*env)->GetPrimitiveArrayCritical(env, v, NULL);
    copy_plane(e->img.planes[2], e->img.stride[2], pv, e->w / 2, e->h / 2);
    (*env)->ReleasePrimitiveArrayCritical(env, v, pv, JNI_ABORT);
    if (aom_codec_encode(&e->ctx, &e->img, (aom_codec_pts_t) ptsUs, 1, key ? AOM_EFLAG_FORCE_KF : 0) != AOM_CODEC_OK) return NULL;
    // Lag is zero, so everything this frame makes is out now: one temporal unit.
    size_t total = 0;
    aom_codec_iter_t it = NULL;
    const aom_codec_cx_pkt_t *p;
    while ((p = aom_codec_get_cx_data(&e->ctx, &it)) != NULL)
        if (p->kind == AOM_CODEC_CX_FRAME_PKT) total += p->data.frame.sz;
    if (total == 0) return NULL;
    jbyteArray out = (*env)->NewByteArray(env, (jsize) total);
    if (!out) return NULL;
    size_t off = 0;
    it = NULL;
    while ((p = aom_codec_get_cx_data(&e->ctx, &it)) != NULL) {
        if (p->kind != AOM_CODEC_CX_FRAME_PKT) continue;
        (*env)->SetByteArrayRegion(env, out, (jsize) off, (jsize) p->data.frame.sz, (const jbyte *) p->data.frame.buf);
        off += p->data.frame.sz;
    }
    return out;
}

JNIEXPORT void JNICALL
Java_ai_ciris_mobile_shared_calls_media_AomNative_encClose(JNIEnv *env, jclass cls, jlong handle) {
    Enc *e = (Enc *) (intptr_t) handle;
    if (!e) return;
    aom_img_free(&e->img);
    aom_codec_destroy(&e->ctx);
    free(e);
}

JNIEXPORT jlong JNICALL
Java_ai_ciris_mobile_shared_calls_media_AomNative_decOpen(JNIEnv *env, jclass cls, jint threads) {
    aom_codec_ctx_t *ctx = calloc(1, sizeof(aom_codec_ctx_t));
    if (!ctx) return 0;
    aom_codec_dec_cfg_t cfg = { (unsigned) threads, 0, 0, 1 };
    if (aom_codec_dec_init(ctx, aom_codec_av1_dx(), &cfg, 0) != AOM_CODEC_OK) { free(ctx); return 0; }
    return (jlong) (intptr_t) ctx;
}

// Decodes one temporal unit. Returns the last picture it completes as
// [width(4, big-endian) | height(4) | Y | U | V], tightly packed, or null.
JNIEXPORT jbyteArray JNICALL
Java_ai_ciris_mobile_shared_calls_media_AomNative_decDecode(JNIEnv *env, jclass cls, jlong handle, jbyteArray data) {
    aom_codec_ctx_t *ctx = (aom_codec_ctx_t *) (intptr_t) handle;
    if (!ctx) return NULL;
    jsize n = (*env)->GetArrayLength(env, data);
    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    aom_codec_err_t r = aom_codec_decode(ctx, (const uint8_t *) bytes, (size_t) n, NULL);
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
    if (r != AOM_CODEC_OK) return NULL;
    aom_codec_iter_t it = NULL;
    aom_image_t *img, *last = NULL;
    while ((img = aom_codec_get_frame(ctx, &it)) != NULL) last = img;
    if (!last || last->fmt != AOM_IMG_FMT_I420) return NULL;
    int w = (int) last->d_w & ~1, h = (int) last->d_h & ~1;
    if (w <= 0 || h <= 0) return NULL;
    size_t ys = (size_t) w * h, cs = ys / 4;
    jbyteArray out = (*env)->NewByteArray(env, (jsize) (8 + ys + 2 * cs));
    if (!out) return NULL;
    jbyte hdr[8] = { (jbyte) (w >> 24), (jbyte) (w >> 16), (jbyte) (w >> 8), (jbyte) w,
                     (jbyte) (h >> 24), (jbyte) (h >> 16), (jbyte) (h >> 8), (jbyte) h };
    (*env)->SetByteArrayRegion(env, out, 0, 8, hdr);
    size_t off = 8;
    for (int p = 0; p < 3; p++) {
        int pw = p ? w / 2 : w, ph = p ? h / 2 : h;
        for (int row = 0; row < ph; row++) {
            (*env)->SetByteArrayRegion(env, out, (jsize) off, pw, (const jbyte *) (last->planes[p] + (size_t) row * last->stride[p]));
            off += (size_t) pw;
        }
    }
    return out;
}

JNIEXPORT void JNICALL
Java_ai_ciris_mobile_shared_calls_media_AomNative_decClose(JNIEnv *env, jclass cls, jlong handle) {
    aom_codec_ctx_t *ctx = (aom_codec_ctx_t *) (intptr_t) handle;
    if (!ctx) return;
    aom_codec_destroy(ctx);
    free(ctx);
}
