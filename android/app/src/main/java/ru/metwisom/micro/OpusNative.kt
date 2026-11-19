package ru.metwisom.micro

/** Native libopus encoder (JNI). Far cheaper than the pure-Java Concentus. */
object OpusNative {
    init { System.loadLibrary("opusjni") }

    const val APPLICATION_VOIP = 2048 // OPUS_APPLICATION_VOIP
    const val APPLICATION_RESTRICTED_LOWDELAY = 2051 // CELT-only, lowest delay (no FEC)

    /** Create an encoder; returns a native handle (0 on failure). */
    external fun create(sampleRate: Int, channels: Int, application: Int, bitrate: Int, complexity: Int): Long

    external fun setBitrate(handle: Long, value: Int)
    external fun setPacketLoss(handle: Long, value: Int)
    external fun setFec(handle: Long, on: Int)

    /** Encode one frame; returns the number of bytes written to [out] (or < 0 on error). */
    external fun encode(handle: Long, pcm: ShortArray, frameSize: Int, out: ByteArray, maxOut: Int): Int

    external fun destroy(handle: Long)
}
