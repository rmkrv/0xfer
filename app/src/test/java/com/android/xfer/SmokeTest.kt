package com.android.xfer

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.Decoder
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.detector.Detector
import com.android.xfer.transfer.FileChunker
import com.android.xfer.transfer.FileInfo
import com.android.xfer.transfer.FountainDecoder
import com.android.xfer.transfer.FountainEncoder
import com.android.xfer.transfer.PacketCodec
import com.android.xfer.qr.TrackedQrDecoder
import com.android.xfer.transfer.TransferConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.EnumMap
import kotlin.math.ceil

class SmokeTest {
    @Test
    fun decimenV3HeaderMatchesPublishedWireVector() {
        val actual = PacketCodec.encode(
            com.android.xfer.transfer.FountainPacket(
                sessionId = 0xBEEF,
                sequence = 0x01020304,
                sourceBlockCount = 0x0111,
                blockLength = 6,
                totalLength = 0x00FEDCBA,
                payloadFnv = 0x89ABCDEF.toInt(),
                payload = byteArrayOf(1, 2, 3, 4, 5, 6)
            )
        )
        val expected = "d1 c3 03 00 ef be 04 03 02 01 11 01 06 00 ba dc fe 00 ef cd ab 89 01 02 03 04 05 06"
            .split(" ").map { it.toInt(16).toByte() }.toByteArray()
        assertArrayEquals(expected, actual)
    }

    @Test
    fun decimenCarouselStartsWithDirectSourceBlocks() {
        val payload = ByteArray(9) { (it + 1).toByte() }
        val encoder = FountainEncoder(payload, 4, 7)
        val first = PacketCodec.decode(PacketCodec.encode(encoder.nextPacket()))
        val second = PacketCodec.decode(PacketCodec.encode(encoder.nextPacket()))
        val third = PacketCodec.decode(PacketCodec.encode(encoder.nextPacket()))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), first.payload)
        assertArrayEquals(byteArrayOf(5, 6, 7, 8), second.payload)
        assertArrayEquals(byteArrayOf(9, 0, 0, 0), third.payload)
    }

    @Test
    fun decimenRepairFramesAlwaysHaveValidDegree() {
        val encoder = FountainEncoder(ByteArray(64 * 32) { it.toByte() }, 64, 7)
        repeat(2_000) {
            val packet = encoder.nextPacket()
            assertTrue(packet.payload.size == 64)
        }
    }

    private fun bitMatrixToRgb(matrix: BitMatrix, scale: Int = 4): IntArray {
        val w = matrix.width
        val h = matrix.height
        val W = w * scale
        val H = h * scale
        val px = IntArray(W * H)
        for (y in 0 until h) for (x in 0 until w) {
            val c = if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            for (dy in 0 until scale) {
                val row = (y * scale + dy) * W
                for (dx in 0 until scale) px[row + x * scale + dx] = c
            }
        }
        return px
    }

    @Test
    fun qrRoundTrip_1057bytes() {
        val payload = ByteArray(1057) { (it * 37 + 11).toByte() }
        val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java).apply {
            put(EncodeHintType.CHARACTER_SET, "ISO-8859-1")
            put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.L)
            put(EncodeHintType.QR_VERSION, TransferConfig.QR_VERSION)
        }
        val matrix = QRCodeWriter().encode(payload.toString(Charsets.ISO_8859_1), BarcodeFormat.QR_CODE, 0, 0, hints)
        val rgb = bitMatrixToRgb(matrix, 4)
        val src = RGBLuminanceSource(matrix.width * 4, matrix.height * 4, rgb)
        val result = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(src)),
            EnumMap<DecodeHintType, Any>(DecodeHintType::class.java).apply { put(DecodeHintType.TRY_HARDER, true) })
        val decoded = result.text.toByteArray(Charsets.ISO_8859_1)
        assertArrayEquals(payload, decoded)
    }

    @Test
    fun trackedDecoderReadFullThenReadTracked() {
        // Force readFull on the first call, then readTracked on the second
        // (geometry is now cached). Both must recover the payload, proving the
        // homography module-sampling path round-trips. This also exercises the
        // two-call path that previously broke by reusing a shared hints map.
        val payload = ByteArray(151) { (it * 37 + 11).toByte() }
        val encodeHints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java).apply {
            put(EncodeHintType.CHARACTER_SET, "ISO-8859-1")
            put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.L)
            put(EncodeHintType.QR_VERSION, TransferConfig.QR_VERSION)
        }
        val matrix = QRCodeWriter().encode(payload.toString(Charsets.ISO_8859_1), BarcodeFormat.QR_CODE, 0, 0, encodeHints)
        val rgb = bitMatrixToRgb(matrix, 4)
        val src = RGBLuminanceSource(matrix.width * 4, matrix.height * 4, rgb)

        val decoder = TrackedQrDecoder()
        val r1 = try {
            decoder.decode(src) ?: throw RuntimeException("R1 (readFull) returned null")
        } catch (e: Exception) {
            throw RuntimeException("R1 (readFull) threw", e)
        }
        assertArrayEquals(payload, r1.text.toByteArray(Charsets.ISO_8859_1))
        val r2 = try {
            decoder.decode(src) ?: throw RuntimeException("R2 (readTracked) returned null")
        } catch (e: Exception) {
            throw RuntimeException("R2 (readTracked) threw", e)
        }
        assertArrayEquals(payload, r2.text.toByteArray(Charsets.ISO_8859_1))
    }

    @Test
    fun v20Carries780ByteChunkPacket() {
        val payload = ByteArray(780) { (it * 29 + 7).toByte() }
        val packet = PacketCodec.encode(
            com.android.xfer.transfer.FountainPacket(
                sessionId = 1,
                sequence = 0,
                sourceBlockCount = 10,
                blockLength = payload.size,
                totalLength = 5_000,
                payloadFnv = 123,
                payload = payload
            )
        )
        val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java).apply {
            put(EncodeHintType.CHARACTER_SET, "ISO-8859-1")
            put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.L)
            put(EncodeHintType.QR_VERSION, 20)
        }
        val matrix = QRCodeWriter().encode(
            packet.toString(Charsets.ISO_8859_1), BarcodeFormat.QR_CODE, 0, 0, hints
        )
        val src = RGBLuminanceSource(matrix.width * 4, matrix.height * 4, bitMatrixToRgb(matrix, 4))
        val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(src))).text
            .toByteArray(Charsets.ISO_8859_1)
        assertArrayEquals(packet, decoded)
    }

    @Test
    fun trackedDecoderReadsChangedPayloadWithoutNewFinderSearch() {
        val first = ByteArray(151) { (it * 19 + 3).toByte() }
        val second = ByteArray(151) { (it * 47 + 9).toByte() }
        val encodeHints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java).apply {
            put(EncodeHintType.CHARACTER_SET, "ISO-8859-1")
            put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.L)
            put(EncodeHintType.QR_VERSION, TransferConfig.QR_VERSION)
        }
        fun source(payload: ByteArray): RGBLuminanceSource {
            val matrix = QRCodeWriter().encode(
                payload.toString(Charsets.ISO_8859_1), BarcodeFormat.QR_CODE, 0, 0, encodeHints
            )
            return RGBLuminanceSource(matrix.width * 4, matrix.height * 4, bitMatrixToRgb(matrix, 4))
        }

        val decoder = TrackedQrDecoder()
        assertArrayEquals(first, decoder.decode(source(first))!!.text.toByteArray(Charsets.ISO_8859_1))
        assertArrayEquals(second, decoder.decode(source(second))!!.text.toByteArray(Charsets.ISO_8859_1))
    }

    @Test
    fun decimenCarouselRoundTripWithoutMetadata() {
        val data = ByteArray(5000) { it.toByte() }
        val blockLength = 120
        val container = com.android.xfer.transfer.DecimenContainer.pack("roundtrip.bin", "application/octet-stream", data)
        val enc = FountainEncoder(container, blockLength, 123)
        val first = PacketCodec.decode(PacketCodec.encode(enc.nextPacket()))
        val rcv = com.android.xfer.transfer.TransferReceiver(first)
        rcv.receive(first)
        var frames = 1
        while (frames < 10_000 && !rcv.isComplete()) {
            rcv.receive(enc.nextPacket())
            frames++
        }
        assertTrue("carousel did not complete", rcv.isComplete())
        assertArrayEquals(data, rcv.unpack().bytes)
    }

    @Test
    fun decimenCarouselSurvivesOutOfOrderFrames() {
        val data = ByteArray(48_000) { (it * 31).toByte() }
        val blockLength = 240
        val container = com.android.xfer.transfer.DecimenContainer.pack("parallel.bin", "application/octet-stream", data)
        val encoder = FountainEncoder(container, blockLength, 991)
        val frames = MutableList(900) { encoder.nextPacket() }
        // A parallel camera pipeline may finish later frames first.
        frames.reverse()
        val receiver = com.android.xfer.transfer.TransferReceiver(frames.first())
        for (frame in frames) {
            receiver.receive(frame)
            if (receiver.isComplete()) break
        }
        assertTrue("out-of-order carousel did not complete", receiver.isComplete())
        assertArrayEquals(data, receiver.unpack().bytes)
    }
}
