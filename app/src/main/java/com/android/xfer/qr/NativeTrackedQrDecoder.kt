package com.android.xfer.qr

import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.LuminanceSource
import com.google.zxing.Result
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.Decoder as JourneyDecoder

/** Result returned from Decimen's native QR-only decoder. */
data class NativeQrResult(
    val valid: Boolean,
    val modules: Int,
    val quad: DoubleArray,
    val bytes: ByteArray
)

internal object NativeDecimenBridge {
    init { System.loadLibrary("decimen_jni") }
    external fun readFull(luma: ByteArray, width: Int, height: Int): NativeQrResult?
    external fun readFullAll(luma: ByteArray, width: Int, height: Int): Array<NativeQrResult>?
    external fun readTracked(
        luma: ByteArray, width: Int, height: Int, modules: Int, quad: DoubleArray
    ): NativeQrResult?
}

/**
 * Native Decimen tracked QR decoder. Unlike the Java fallback, this retains
 * the detector's true four-corner perspective quad and refreshes it after each
 * successful frame, so it avoids general QR detection while the stream holds.
 */
class NativeTrackedQrDecoder : JourneyDecoder(com.google.zxing.qrcode.QRCodeReader()) {
    interface Listener {
        fun onMetrics(frameW: Int, frameH: Int, qrW: Float, qrH: Float, dim: Int, ppm: Float)
    }

    var listener: Listener? = null
    private var lock: NativeQrResult? = null

    fun forgetGeometry() { lock = null }

    override fun decode(source: LuminanceSource): Result? = try {
        val cached = lock
        val decoded = if (cached == null) {
            NativeDecimenBridge.readFull(source.matrix, source.width, source.height)
        } else {
            val tracked = NativeDecimenBridge.readTracked(
                source.matrix, source.width, source.height, cached.modules, cached.quad
            )
            if (tracked?.valid == true) tracked else {
                // Keep a detector or tracked sighting as the next anchor. It
                // costs no correctness: only a valid QR becomes a Result.
                tracked?.takeIf { it.modules > 0 }?.let { lock = it }
                NativeDecimenBridge.readFull(source.matrix, source.width, source.height)
            }
        }
        decoded?.also { if (it.modules > 0) lock = it }
            ?.takeIf { it.valid && it.bytes.isNotEmpty() }
            ?.toResult(source.width, source.height)
    } catch (e: Exception) {
        Log.d(TAG, "native decode failed: ${e.message}")
        null
    }

    private fun NativeQrResult.toResult(frameW: Int, frameH: Int): Result {
        // Overlay convention: bottom-left, top-left, top-right, bottom-right.
        val points = arrayOf(
            ResultPoint(quad[6].toFloat(), quad[7].toFloat()),
            ResultPoint(quad[0].toFloat(), quad[1].toFloat()),
            ResultPoint(quad[2].toFloat(), quad[3].toFloat()),
            ResultPoint(quad[4].toFloat(), quad[5].toFloat())
        )
        val width = kotlin.math.hypot(quad[2] - quad[0], quad[3] - quad[1]).toFloat()
        val height = kotlin.math.hypot(quad[6] - quad[0], quad[7] - quad[1]).toFloat()
        listener?.onMetrics(frameW, frameH, width, height, modules, (width + height) / 2f / modules)
        return Result(String(bytes, Charsets.ISO_8859_1), bytes, points, BarcodeFormat.QR_CODE)
    }

    private companion object { const val TAG = "NativeTrackedQrDecoder" }
}
