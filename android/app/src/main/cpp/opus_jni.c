// Minimal JNI wrapper over libopus — exposes exactly the encoder controls we use
// (bitrate, in-band FEC, packet-loss %, complexity). Native = much lower CPU than Concentus.

#include <jni.h>
#include <stdint.h>
#include <opus.h>

JNIEXPORT jlong JNICALL
Java_ru_metwisom_micro_OpusNative_create(JNIEnv *env, jobject thiz,
                                         jint sr, jint ch, jint app, jint bitrate, jint complexity) {
    int err = 0;
    OpusEncoder *enc = opus_encoder_create(sr, ch, app, &err);
    if (err != OPUS_OK || enc == NULL) return 0;
    opus_encoder_ctl(enc, OPUS_SET_BITRATE(bitrate));
    opus_encoder_ctl(enc, OPUS_SET_INBAND_FEC(1));
    opus_encoder_ctl(enc, OPUS_SET_PACKET_LOSS_PERC(5));
    opus_encoder_ctl(enc, OPUS_SET_COMPLEXITY(complexity));
    opus_encoder_ctl(enc, OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE));
    return (jlong) (intptr_t) enc;
}

JNIEXPORT void JNICALL
Java_ru_metwisom_micro_OpusNative_setBitrate(JNIEnv *env, jobject thiz, jlong h, jint v) {
    if (h) opus_encoder_ctl((OpusEncoder *) (intptr_t) h, OPUS_SET_BITRATE(v));
}

JNIEXPORT void JNICALL
Java_ru_metwisom_micro_OpusNative_setPacketLoss(JNIEnv *env, jobject thiz, jlong h, jint v) {
    if (h) opus_encoder_ctl((OpusEncoder *) (intptr_t) h, OPUS_SET_PACKET_LOSS_PERC(v));
}

JNIEXPORT void JNICALL
Java_ru_metwisom_micro_OpusNative_setFec(JNIEnv *env, jobject thiz, jlong h, jint on) {
    if (h) opus_encoder_ctl((OpusEncoder *) (intptr_t) h, OPUS_SET_INBAND_FEC(on));
}

JNIEXPORT jint JNICALL
Java_ru_metwisom_micro_OpusNative_encode(JNIEnv *env, jobject thiz,
                                         jlong h, jshortArray pcm, jint frame,
                                         jbyteArray out, jint maxOut) {
    if (!h) return -1;
    // Critical access = no array copy on the hot path. Either Get*Critical can return NULL under
    // memory pressure; bail cleanly instead of handing opus_encode a NULL buffer (→ native crash).
    jshort *p = (*env)->GetPrimitiveArrayCritical(env, pcm, NULL);
    if (!p) return -1;
    jbyte *o = (*env)->GetPrimitiveArrayCritical(env, out, NULL);
    if (!o) {
        (*env)->ReleasePrimitiveArrayCritical(env, pcm, p, JNI_ABORT);
        return -1;
    }
    int n = opus_encode((OpusEncoder *) (intptr_t) h, p, frame, (unsigned char *) o, maxOut);
    (*env)->ReleasePrimitiveArrayCritical(env, out, o, 0);
    (*env)->ReleasePrimitiveArrayCritical(env, pcm, p, JNI_ABORT);
    return n;
}

JNIEXPORT void JNICALL
Java_ru_metwisom_micro_OpusNative_destroy(JNIEnv *env, jobject thiz, jlong h) {
    if (h) opus_encoder_destroy((OpusEncoder *) (intptr_t) h);
}
