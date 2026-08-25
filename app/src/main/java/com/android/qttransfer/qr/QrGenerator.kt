package com.android.qttransfer.qr

import android.graphics.Bitmap
import android.graphics.Color
import com.android.qttransfer.transfer.TransferConfig
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import com.google.zxing.qrcode.encoder.QRCode

object QrGenerator {

    /*
     * Generates a QR using ZXing's binary-capable
     * Byte mode through ISO-8859-1.
     *
     * ISO-8859-1 maps byte 0..255 directly to
     * characters with the same byte values.
     *
     * The QR version controls the symbol size: a version V code is
     * (17 + 4*V) modules per side (e.g. v40 -> 177x177, v25 -> 117x117).
     * The rendered bitmap is the symbol plus a 4-module quiet zone on each
     * side, scaled by [scale] pixels per module.
     */
    fun generate(
        payload: ByteArray,
        version: Int = TransferConfig.QR_VERSION,
        scale: Int = 10
    ): Bitmap {

        val content = String(payload, Charsets.ISO_8859_1)

        val hints = mutableMapOf<EncodeHintType, Any>(
            EncodeHintType.CHARACTER_SET to "ISO-8859-1",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
            EncodeHintType.MARGIN to 0,
            // Decimen pins one valid mask for the entire stream. Besides
            // avoiding ZXing's eight-mask scoring pass, it keeps format/data
            // layout stable from frame to frame.
            EncodeHintType.QR_MASK_PATTERN to 4
        )
        if (version > 0) {
            hints[EncodeHintType.QR_VERSION] = version
        }

        val modules = if (version > 0) 17 + 4 * version else 21 // Approx if auto
        val matrix = QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            0, 0, // Request minimum size, we will scale it ourselves
            hints
        )

        // The matrix returned might not be exactly modules x modules if version was auto-selected,
        // so we use the matrix's actual dimensions.
        val mWidth = matrix.width
        val mHeight = matrix.height
        val quiet = 4
        val sizeW = (mWidth + 2 * quiet) * scale
        val sizeH = (mHeight + 2 * quiet) * scale

        // Bitmap.setPixel() performs a JNI call for every output pixel and
        // makes a 10 FPS sender impossible. Rasterize in memory and upload the
        // finished image once instead.
        val pixels = IntArray(sizeW * sizeH) { Color.WHITE }
        for (my in 0 until mHeight) {
            for (mx in 0 until mWidth) {
                if (!matrix[mx, my]) continue
                val x0 = (mx + quiet) * scale
                val y0 = (my + quiet) * scale
                for (dy in 0 until scale) {
                    val row = (y0 + dy) * sizeW
                    for (dx in 0 until scale) pixels[row + x0 + dx] = Color.BLACK
                }
            }
        }
        val bitmap = Bitmap.createBitmap(sizeW, sizeH, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, sizeW, 0, 0, sizeW, sizeH)

        return bitmap
    }

    /** The unscaled symbol matrix for module-aligned display rendering. */
    fun generateMatrix(payload: ByteArray, version: Int): BitMatrix {
        val content = String(payload, Charsets.ISO_8859_1)
        val hints = mutableMapOf<EncodeHintType, Any>(
            EncodeHintType.CHARACTER_SET to "ISO-8859-1",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
            EncodeHintType.MARGIN to 0,
            EncodeHintType.QR_MASK_PATTERN to 4,
            EncodeHintType.QR_VERSION to version
        )
        return QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints)
    }

    /** Nominal module count per side for a QR of the given version. */
    fun moduleSize(version: Int): Int = 17 + 4 * version
}
