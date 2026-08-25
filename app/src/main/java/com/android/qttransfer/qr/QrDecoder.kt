package com.android.qttransfer.qr

import android.graphics.Bitmap
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.nio.charset.StandardCharsets

object QrDecoder {

    private val reader =
        MultiFormatReader()

    fun decode(
        bitmap: Bitmap
    ): ByteArray? {

        val width =
            bitmap.width

        val height =
            bitmap.height

        val pixels =
            IntArray(width * height)

        bitmap.getPixels(
            pixels,
            0,
            width,
            0,
            0,
            width,
            height
        )

        val source =
            RGBLuminanceSource(
                width,
                height,
                pixels
            )

        val binaryBitmap =
            BinaryBitmap(
                HybridBinarizer(source)
            )

        return try {

            val result =
                reader.decode(binaryBitmap)

            /*
             * QR payload was encoded as ISO-8859-1.
             */
            result.text.toByteArray(
                StandardCharsets.ISO_8859_1
            )

        } catch (_: NotFoundException) {

            null
        } finally {

            reader.reset()
        }
    }
}
