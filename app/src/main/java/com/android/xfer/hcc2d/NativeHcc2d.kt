package com.android.xfer.hcc2d

import java.nio.ByteBuffer

/** Native HCC2D 0.9.0 codec payloads. Module bytes are palette indices, not
 * text or Base64, and camera planes are passed as direct CameraX buffers. */
data class NativeHcc2dEncoded(
    val modules: ByteArray,
    val fullDimension: Int,
    val colors: Int,
    val version: Int,
    val maskPattern: Int,
    val payloadCapacity: Int
)

data class NativeHcc2dFrame(
    val payload: ByteArray,
    val detected: Boolean,
    val valid: Boolean,
    val colors: Int,
    val version: Int,
    val payloadCapacity: Int,
    /** 0=no lock, 1=geometry, 2=colours, 3=format, 4=codewords, 5=payload, 6=valid. */
    val stage: Int,
    val decodeNanos: Long
)

/** A snapshot is requested only four times per second; all per-frame counters
 * remain native so a missed camera frame creates no Java object or byte array. */
data class NativeHcc2dStats(
    val cameraFrames: Long,
    val attempts: Long,
    val decoded: Long,
    val decodeNanos: Long,
    val locked: Long,
    val formatRead: Long,
    val codewordsRead: Long,
    val palette4: Long,
    val palette8: Long,
    val colors: Int,
    val version: Int,
    val payloadCapacity: Int,
    val detected: Boolean,
    val acquisitionScans: Long,
    val acquisitionNanos: Long,
    val rawFinders: Int,
    val clusteredFinders: Int,
    val tripleSeeds: Int,
    val hypotheses: Int,
    val acceptedGeometries: Int,
    val blackThreshold: Int
) {
    companion object {
        fun fromNative(values: LongArray?): NativeHcc2dStats? {
            if (values == null || values.size < 21) return null
            return NativeHcc2dStats(
                values[0], values[1], values[2], values[3], values[4], values[5], values[6],
                values[7], values[8], values[9].toInt(), values[10].toInt(),
                values[11].toInt(), values[12] != 0L, values[13], values[14], values[15].toInt(),
                values[16].toInt(), values[17].toInt(), values[18].toInt(), values[19].toInt(), values[20].toInt()
            )
        }
    }
}

object NativeHcc2dBridge {
    init { System.loadLibrary("decimen_jni") }

    external fun encode(payload: ByteArray, colors: Int, version: Int): NativeHcc2dEncoded?
    external fun createDecoder(): Long
    external fun releaseDecoder(handle: Long)
    external fun decodeYuv(
        handle: Long,
        y: ByteBuffer, yOffset: Int, yRowStride: Int, yPixelStride: Int,
        u: ByteBuffer, uOffset: Int, uRowStride: Int, uPixelStride: Int,
        v: ByteBuffer, vOffset: Int, vRowStride: Int, vPixelStride: Int,
        width: Int, height: Int
    ): Array<NativeHcc2dFrame>?
    /** Native counters, returned at a low UI refresh rate. Indexes are kept
     * native-only so failed camera frames never allocate Kotlin objects. */
    external fun readStats(handle: Long): LongArray?
    external fun readQuads(handle: Long): Array<DoubleArray>?
}
