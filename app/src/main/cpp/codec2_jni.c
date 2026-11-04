#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include "codec2.h"

/* Modes in the order the Kotlin side uses: 0 = 700C, 1 = 1300, 2 = 2400, 3 = 3200. */
static int mode_of(jint m) {
    switch (m) {
        case 0: return CODEC2_MODE_700C;
        case 1: return CODEC2_MODE_1300;
        case 2: return CODEC2_MODE_2400;
        case 3: return CODEC2_MODE_3200;
        default: return -1;
    }
}

/* PCM (8 kHz, 16 bit) to packed frames. The last partial frame is padded with silence. */
JNIEXPORT jbyteArray JNICALL
Java_com_nodewave_mesh_audio_Codec2Native_encode(JNIEnv *env, jclass cls, jint mode, jshortArray pcmArr) {
    int m = mode_of(mode);
    if (m < 0) return NULL;
    struct CODEC2 *c = codec2_create(m);
    if (!c) return NULL;
    int spf = codec2_samples_per_frame(c);
    int bpf = codec2_bytes_per_frame(c);
    jsize n = (*env)->GetArrayLength(env, pcmArr);
    int frames = (n + spf - 1) / spf;
    short *buf = calloc((size_t)frames * spf, sizeof(short));
    unsigned char *out = malloc((size_t)frames * bpf);
    if (!buf || !out) { free(buf); free(out); codec2_destroy(c); return NULL; }
    (*env)->GetShortArrayRegion(env, pcmArr, 0, n, buf);
    for (int f = 0; f < frames; f++) codec2_encode(c, out + (size_t)f * bpf, buf + (size_t)f * spf);
    jbyteArray res = (*env)->NewByteArray(env, frames * bpf);
    if (res) (*env)->SetByteArrayRegion(env, res, 0, frames * bpf, (jbyte *)out);
    free(buf); free(out); codec2_destroy(c);
    return res;
}

/* Packed frames to PCM; null if the length is not a whole number of frames. */
JNIEXPORT jshortArray JNICALL
Java_com_nodewave_mesh_audio_Codec2Native_decode(JNIEnv *env, jclass cls, jint mode, jbyteArray dataArr) {
    int m = mode_of(mode);
    if (m < 0) return NULL;
    struct CODEC2 *c = codec2_create(m);
    if (!c) return NULL;
    int spf = codec2_samples_per_frame(c);
    int bpf = codec2_bytes_per_frame(c);
    jsize n = (*env)->GetArrayLength(env, dataArr);
    if (n <= 0 || n % bpf != 0) { codec2_destroy(c); return NULL; }
    int frames = n / bpf;
    unsigned char *in = malloc(n);
    short *out = malloc((size_t)frames * spf * sizeof(short));
    if (!in || !out) { free(in); free(out); codec2_destroy(c); return NULL; }
    (*env)->GetByteArrayRegion(env, dataArr, 0, n, (jbyte *)in);
    for (int f = 0; f < frames; f++) codec2_decode(c, out + (size_t)f * spf, in + (size_t)f * bpf);
    jshortArray res = (*env)->NewShortArray(env, frames * spf);
    if (res) (*env)->SetShortArrayRegion(env, res, 0, frames * spf, out);
    free(in); free(out); codec2_destroy(c);
    return res;
}
