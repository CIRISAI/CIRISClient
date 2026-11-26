// AV1 for phone calls (CSD-134, AV1 both ways): libaom, real-time encode and
// decode, as a few plain C calls. Header-only so that Android's JNI wrapper
// (src/androidMain/cpp) and iOS's Kotlin/Native binding (aom.def) share ONE copy.
// Frames are planar I420, 8-bit, tightly packed, as every platform's VideoFrame is.
// Every buffer handed out is malloc'd; the caller frees it with ciris_aom_free.
// Handles are opaque (void *), so a binding never sees libaom's own structs.
#ifndef CIRIS_AOM_H
#define CIRIS_AOM_H

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
} ciris_aom_enc;

typedef struct {
    aom_codec_ctx_t ctx;
} ciris_aom_dec;

static inline void ciris_aom_free(void *p) { free(p); }

static inline void *ciris_aom_enc_open(int w, int h, int fps, int kbps, int threads) {
    aom_codec_iface_t *iface = aom_codec_av1_cx();
    aom_codec_enc_cfg_t cfg;
    if (w <= 0 || h <= 0 || (w & 1) || (h & 1)) return NULL;
    if (aom_codec_enc_config_default(iface, &cfg, AOM_USAGE_REALTIME) != AOM_CODEC_OK) return NULL;
    cfg.g_w = (unsigned) w;
    cfg.g_h = (unsigned) h;
    cfg.g_timebase.num = 1;
    cfg.g_timebase.den = 1000000; // microseconds, as VideoFrame.timestampUs
    cfg.rc_target_bitrate = (unsigned) kbps;
    cfg.rc_end_usage = AOM_CBR;
    cfg.g_lag_in_frames = 0; // a call never waits on a later frame
    cfg.g_threads = (unsigned) threads;
    cfg.kf_mode = AOM_KF_AUTO;
    cfg.kf_max_dist = 9999; // keyframes are asked for by the caller
    (void) fps;
    ciris_aom_enc *e = (ciris_aom_enc *) calloc(1, sizeof(ciris_aom_enc));
    if (!e) return NULL;
    if (aom_codec_enc_init(&e->ctx, iface, &cfg, 0) != AOM_CODEC_OK) { free(e); return NULL; }
    aom_codec_control(&e->ctx, AOME_SET_CPUUSED, 10);
    aom_codec_control(&e->ctx, AV1E_SET_ROW_MT, 1);
    aom_codec_control(&e->ctx, AV1E_SET_AQ_MODE, 3);
    aom_codec_control(&e->ctx, AV1E_SET_ENABLE_ORDER_HINT, 0);
    aom_codec_control(&e->ctx, AV1E_SET_DELTAQ_MODE, 0);
    if (!aom_img_alloc(&e->img, AOM_IMG_FMT_I420, (unsigned) w, (unsigned) h, 16)) {
        aom_codec_destroy(&e->ctx); free(e); return NULL;
    }
    e->w = w; e->h = h;
    return e;
}

static inline void ciris_aom_copy_plane(uint8_t *dst, int dstStride, const uint8_t *src, int w, int h) {
    for (int r = 0; r < h; r++) memcpy(dst + (size_t) r * dstStride, src + (size_t) r * w, (size_t) w);
}

// One frame in; its whole temporal unit out (lag is zero), or 0 bytes. Returns 0 on success.
static inline int ciris_aom_enc_encode(void *handle, const uint8_t *y, const uint8_t *u, const uint8_t *v,
                                       int64_t pts_us, int keyframe, uint8_t **out, size_t *out_len) {
    ciris_aom_enc *e = (ciris_aom_enc *) handle;
    *out = NULL; *out_len = 0;
    if (!e) return -1;
    ciris_aom_copy_plane(e->img.planes[0], e->img.stride[0], y, e->w, e->h);
    ciris_aom_copy_plane(e->img.planes[1], e->img.stride[1], u, e->w / 2, e->h / 2);
    ciris_aom_copy_plane(e->img.planes[2], e->img.stride[2], v, e->w / 2, e->h / 2);
    if (aom_codec_encode(&e->ctx, &e->img, (aom_codec_pts_t) pts_us, 1, keyframe ? AOM_EFLAG_FORCE_KF : 0) != AOM_CODEC_OK) return -1;
    size_t total = 0;
    aom_codec_iter_t it = NULL;
    const aom_codec_cx_pkt_t *p;
    while ((p = aom_codec_get_cx_data(&e->ctx, &it)) != NULL)
        if (p->kind == AOM_CODEC_CX_FRAME_PKT) total += p->data.frame.sz;
    if (total == 0) return 0;
    uint8_t *buf = (uint8_t *) malloc(total);
    if (!buf) return -1;
    size_t off = 0;
    it = NULL;
    while ((p = aom_codec_get_cx_data(&e->ctx, &it)) != NULL) {
        if (p->kind != AOM_CODEC_CX_FRAME_PKT) continue;
        memcpy(buf + off, p->data.frame.buf, p->data.frame.sz);
        off += p->data.frame.sz;
    }
    *out = buf; *out_len = total;
    return 0;
}

static inline void ciris_aom_enc_close(void *handle) {
    ciris_aom_enc *e = (ciris_aom_enc *) handle;
    if (!e) return;
    aom_img_free(&e->img);
    aom_codec_destroy(&e->ctx);
    free(e);
}

static inline void *ciris_aom_dec_open(int threads) {
    ciris_aom_dec *d = (ciris_aom_dec *) calloc(1, sizeof(ciris_aom_dec));
    if (!d) return NULL;
    aom_codec_dec_cfg_t cfg;
    memset(&cfg, 0, sizeof(cfg));
    cfg.threads = (unsigned) threads;
    cfg.allow_lowbitdepth = 1;
    if (aom_codec_dec_init(&d->ctx, aom_codec_av1_dx(), &cfg, 0) != AOM_CODEC_OK) { free(d); return NULL; }
    return d;
}

// One temporal unit in; the last picture it completes out as packed I420
// (w*h + 2*(w/2)*(h/2) bytes), dimensions rounded down to even. Returns 0 when a
// picture came out, 1 when none did, negative on a decode error.
static inline int ciris_aom_dec_decode(void *handle, const uint8_t *data, size_t len,
                                       uint8_t **out, int *out_w, int *out_h) {
    ciris_aom_dec *d = (ciris_aom_dec *) handle;
    *out = NULL; *out_w = 0; *out_h = 0;
    if (!d) return -1;
    if (aom_codec_decode(&d->ctx, data, len, NULL) != AOM_CODEC_OK) return -1;
    aom_codec_iter_t it = NULL;
    aom_image_t *img, *last = NULL;
    while ((img = aom_codec_get_frame(&d->ctx, &it)) != NULL) last = img;
    if (!last || last->fmt != AOM_IMG_FMT_I420) return 1;
    int w = (int) last->d_w & ~1, h = (int) last->d_h & ~1;
    if (w <= 0 || h <= 0) return 1;
    size_t ys = (size_t) w * h, cs = ys / 4;
    uint8_t *buf = (uint8_t *) malloc(ys + 2 * cs);
    if (!buf) return -1;
    size_t off = 0;
    for (int p = 0; p < 3; p++) {
        int pw = p ? w / 2 : w, ph = p ? h / 2 : h;
        for (int row = 0; row < ph; row++) {
            memcpy(buf + off, last->planes[p] + (size_t) row * last->stride[p], (size_t) pw);
            off += (size_t) pw;
        }
    }
    *out = buf; *out_w = w; *out_h = h;
    return 0;
}

static inline void ciris_aom_dec_close(void *handle) {
    ciris_aom_dec *d = (ciris_aom_dec *) handle;
    if (!d) return;
    aom_codec_destroy(&d->ctx);
    free(d);
}

#endif
