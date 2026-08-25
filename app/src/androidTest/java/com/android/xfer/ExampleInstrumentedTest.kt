package com.android.xfer

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*
import com.android.xfer.hcc2d.NativeHcc2dBridge
import com.android.xfer.hcc2d.Hcc2dSyntheticScene

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.android.transfer", appContext.packageName)
    }

    @Test
    fun hcc2dReferenceEncoderSupportsVersion40BinaryPackets() {
        // v40, EC Q: the experimental colour profile trades raw payload for
        // enough correction to survive phone-display chroma blur. HCC2D's
        // 3-byte BYTE framing leaves these exact raw capacities.
        val hcc4Packet = ByteArray(3_307) { (it * 17).toByte() }
        val hcc8Packet = ByteArray(4_973) { (it * 29).toByte() }
        val four = requireNotNull(NativeHcc2dBridge.encode(hcc4Packet, 4, 40))
        val eight = requireNotNull(NativeHcc2dBridge.encode(hcc8Packet, 8, 40))
        assertEquals(179, four.fullDimension)
        assertEquals(179, eight.fullDimension)
        assertEquals(4, four.colors)
        assertEquals(8, eight.colors)
        assertEquals(3_329, four.payloadCapacity)
        assertEquals(4_995, eight.payloadCapacity)
    }

    @Test
    fun hcc2d4Version40RoundTripsSyntheticCameraYuv() {
        assertSyntheticRoundTrip(4, ByteArray(1_024) { (it * 31 + 7).toByte() })
    }

    @Test
    fun hcc2d8Version40RoundTripsSyntheticCameraYuv() {
        assertSyntheticRoundTrip(8, ByteArray(2_048) { (it * 13 + 41).toByte() })
    }

    @Test
    fun hcc2dAxisAlignedGeometryStaysSquare() {
        val payload = ByteArray(1_024) { (it * 31 + 7).toByte() }
        val symbol = requireNotNull(NativeHcc2dBridge.encode(payload, 4, 40))
        val image = Hcc2dSyntheticScene.axisAligned(symbol, pixelsPerModule = 4)
        val decoder = NativeHcc2dBridge.createDecoder()
        try {
            val decoded = NativeHcc2dBridge.decodeYuv(
                decoder,
                image.y, 0, image.width, 1,
                image.u, 0, image.width / 2, 1,
                image.v, 0, image.width / 2, 1,
                image.width, image.height
            ).orEmpty()
            assertTrue("axis-aligned HCC2D frame did not decode", decoded.any { it.valid })
            val quad = requireNotNull(NativeHcc2dBridge.readQuads(decoder)?.singleOrNull())
            for (point in 0 until 4) {
                val index = point * 2
                val dx = quad[index] - image.innerQuad[index]
                val dy = quad[index + 1] - image.innerQuad[index + 1]
                assertTrue(
                    "axis-aligned corner $point drifted: actual=${quad.joinToString()} expected=${image.innerQuad.joinToString()}",
                    kotlin.math.hypot(dx, dy) <= 8.0
                )
            }
        } finally {
            NativeHcc2dBridge.releaseDecoder(decoder)
        }
    }

    @Test
    fun hcc2dOwnDetectorRoundTripsPerspectiveAcrossVersions() {
        val cases = listOf(
            Triple(4, 1, 8),
            Triple(4, 20, 512),
            Triple(8, 20, 512),
            Triple(8, 40, 2_048)
        )
        val quad = doubleArrayOf(
            150.0, 110.0,
            1120.0, 165.0,
            1050.0, 875.0,
            100.0, 790.0
        )
        for ((colors, version, size) in cases) {
            val payload = ByteArray(size) { index -> (index * 37 + colors * 11 + version).toByte() }
            val symbol = requireNotNull(NativeHcc2dBridge.encode(payload, colors, version))
            val image = Hcc2dSyntheticScene.perspective(symbol, 1280, 960, quad)
            val decoder = NativeHcc2dBridge.createDecoder()
            val decoded: Array<com.android.xfer.hcc2d.NativeHcc2dFrame> = try {
                NativeHcc2dBridge.decodeYuv(
                    decoder,
                    image.y, 0, image.width, 1,
                    image.u, 0, image.width / 2, 1,
                    image.v, 0, image.width / 2, 1,
                    image.width, image.height
                ) ?: emptyArray()
            } finally {
                NativeHcc2dBridge.releaseDecoder(decoder)
            }
            val valid = decoded.firstOrNull { it.valid }
            assertNotNull(
                "HCC2D$colors v$version perspective frame did not decode; " +
                    "expected=${image.innerQuad.joinToString(prefix = "(", postfix = ")") { "%.1f".format(it) }}",
                valid
            )
            assertArrayEquals(payload, valid!!.payload)
        }
    }

    @Test
    fun hcc2dOwnDetectorDecodesTwoSymbolsInOneCameraFrame() {
        val payloads = listOf(
            ByteArray(512) { (it * 11 + 3).toByte() },
            ByteArray(512) { (it * 23 + 71).toByte() }
        )
        val symbols = payloads.map { payload ->
            requireNotNull(NativeHcc2dBridge.encode(payload, 4, 20))
        }
        val image = Hcc2dSyntheticScene.grid(symbols, columns = 2)
        val decoder = NativeHcc2dBridge.createDecoder()
        val decoded = try {
            NativeHcc2dBridge.decodeYuv(
                decoder,
                image.y, 0, image.width, 1,
                image.u, 0, image.width / 2, 1,
                image.v, 0, image.width / 2, 1,
                image.width, image.height
            ).orEmpty()
        } finally {
            NativeHcc2dBridge.releaseDecoder(decoder)
        }
        val valid = decoded.filter { it.valid }
        assertEquals("both HCC2D cells must be acquired from the same frame", 2, valid.size)
        for (payload in payloads)
            assertTrue("a grid payload was not decoded", valid.any { it.payload.contentEquals(payload) })
    }

    @Test
    fun hcc2dOwnDetectorDecodesAllSixCellsInTwoByThreeGrid() {
        val payloads = (0 until 6).map { cell ->
            ByteArray(512) { index -> (index * (cell * 6 + 11) + cell * 29 + 3).toByte() }
        }
        val symbols = payloads.map { requireNotNull(NativeHcc2dBridge.encode(it, 4, 15)) }
        val image = Hcc2dSyntheticScene.grid(symbols, columns = 3)
        val decoder = NativeHcc2dBridge.createDecoder()
        val decoded = try {
            NativeHcc2dBridge.decodeYuv(
                decoder,
                image.y, 0, image.width, 1,
                image.u, 0, image.width / 2, 1,
                image.v, 0, image.width / 2, 1,
                image.width, image.height
            ).orEmpty()
        } finally {
            NativeHcc2dBridge.releaseDecoder(decoder)
        }
        val valid = decoded.filter { it.valid }
        assertEquals("all six HCC2D grid cells must decode", 6, valid.size)
        for (payload in payloads)
            assertTrue("a 2x3 grid payload was not decoded", valid.any { it.payload.contentEquals(payload) })
    }

    @Test
    fun hcc2dOwnDetectorDoesNotCrossWireVerticallyStackedSymbols() {
        // This is the camera layout that previously produced a rhombus: an
        // alignment marker in the upper symbol was incorrectly combined with
        // finder patterns from the same/adjacent cell. Both rows must decode
        // from one acquisition instead of leaving the second code untracked.
        val payloads = listOf(
            ByteArray(512) { (it * 7 + 19).toByte() },
            ByteArray(512) { (it * 37 + 5).toByte() }
        )
        val symbols = payloads.map { payload ->
            requireNotNull(NativeHcc2dBridge.encode(payload, 4, 20))
        }
        val image = Hcc2dSyntheticScene.grid(symbols, columns = 1)
        val decoder = NativeHcc2dBridge.createDecoder()
        val decoded = try {
            NativeHcc2dBridge.decodeYuv(
                decoder,
                image.y, 0, image.width, 1,
                image.u, 0, image.width / 2, 1,
                image.v, 0, image.width / 2, 1,
                image.width, image.height
            ).orEmpty()
        } finally {
            NativeHcc2dBridge.releaseDecoder(decoder)
        }
        val valid = decoded.filter { it.valid }
        assertEquals("both vertically stacked HCC2D cells must decode", 2, valid.size)
        for (payload in payloads)
            assertTrue("a vertically stacked grid payload was not decoded", valid.any { it.payload.contentEquals(payload) })
    }

    @Test
    fun hcc2dOwnDetectorKeepsTiltedVerticalSymbolsIndependent() {
        // Matches the real failure shape: the upper cell is viewed as a
        // trapezoid while a second same-version cell sits below it. A locator
        // must not use an upper alignment mark as a finder and stretch the
        // upper quad into the lower symbol.
        val payloads = listOf(
            ByteArray(512) { (it * 17 + 13).toByte() },
            ByteArray(512) { (it * 43 + 29).toByte() }
        )
        val symbols = payloads.map { requireNotNull(NativeHcc2dBridge.encode(it, 4, 15)) }
        val image = Hcc2dSyntheticScene.perspectiveGrid(symbols, 1280, 960, listOf(
            doubleArrayOf(310.0, 85.0, 705.0, 115.0, 760.0, 440.0, 350.0, 410.0),
            doubleArrayOf(300.0, 505.0, 720.0, 500.0, 715.0, 835.0, 300.0, 835.0)
        ))
        val decoder = NativeHcc2dBridge.createDecoder()
        val decoded = try {
            NativeHcc2dBridge.decodeYuv(
                decoder,
                image.y, 0, image.width, 1,
                image.u, 0, image.width / 2, 1,
                image.v, 0, image.width / 2, 1,
                image.width, image.height
            ).orEmpty()
        } finally {
            NativeHcc2dBridge.releaseDecoder(decoder)
        }
        val valid = decoded.filter { it.valid }
        assertEquals("both tilted vertical HCC2D cells must decode", 2, valid.size)
        for (payload in payloads)
            assertTrue("a tilted grid payload was not decoded", valid.any { it.payload.contentEquals(payload) })
    }

    @Test
    fun hcc2dOwnDetectorHandlesQuarterTurnPerspective() {
        val payload = ByteArray(512) { (it * 29 + 17).toByte() }
        val symbol = requireNotNull(NativeHcc2dBridge.encode(payload, 4, 20))
        // Logical TL/TR/BR/BL appear rotated clockwise in the camera plane.
        val image = Hcc2dSyntheticScene.perspective(symbol, 1280, 960, doubleArrayOf(
            1000.0, 110.0,
            1080.0, 760.0,
            210.0, 835.0,
            165.0, 150.0
        ))
        val decoder = NativeHcc2dBridge.createDecoder()
        val decoded = try {
            NativeHcc2dBridge.decodeYuv(
                decoder,
                image.y, 0, image.width, 1,
                image.u, 0, image.width / 2, 1,
                image.v, 0, image.width / 2, 1,
                image.width, image.height
            ).orEmpty()
        } finally {
            NativeHcc2dBridge.releaseDecoder(decoder)
        }
        val valid = decoded.firstOrNull { it.valid }
        assertNotNull("quarter-turn HCC2D frame did not decode", valid)
        assertArrayEquals(payload, valid!!.payload)
    }

    @Test
    fun hcc2dOwnDetectorHandlesUnevenScreenLighting() {
        val payload = ByteArray(512) { (it * 41 + 9).toByte() }
        val symbol = requireNotNull(NativeHcc2dBridge.encode(payload, 4, 20))
        val image = Hcc2dSyntheticScene.unevenScreenLighting(
            Hcc2dSyntheticScene.perspective(symbol, 1280, 960, doubleArrayOf(
                120.0, 80.0,
                1120.0, 145.0,
                1065.0, 870.0,
                90.0, 800.0
            )),
            minLift = 4,
            maxLift = 132
        )
        val decoder = NativeHcc2dBridge.createDecoder()
        val decoded = try {
            NativeHcc2dBridge.decodeYuv(
                decoder,
                image.y, 0, image.width, 1,
                image.u, 0, image.width / 2, 1,
                image.v, 0, image.width / 2, 1,
                image.width, image.height
            ).orEmpty()
        } finally {
            NativeHcc2dBridge.releaseDecoder(decoder)
        }
        val valid = decoded.firstOrNull { it.valid }
        assertNotNull("uneven display lighting must not lose the HCC2D finder lock", valid)
        assertArrayEquals(payload, valid!!.payload)
    }

    @Test
    fun hcc2dOwnDetectorHandlesDiagonalVersion40() {
        val payload = ByteArray(1_024) { (it * 47 + 31).toByte() }
        val symbol = requireNotNull(NativeHcc2dBridge.encode(payload, 4, 40))
        // A roughly 30-degree in-plane rotation makes horizontal finder runs
        // appreciably wider than the symbol's actual module pitch. It is a
        // normal handheld camera pose and must not make a v40 seed look like
        // a much smaller version.
        val image = Hcc2dSyntheticScene.perspective(symbol, 1280, 960, doubleArrayOf(
            519.0, 29.0,
            1091.0, 359.0,
            761.0, 931.0,
            189.0, 601.0
        ))
        val decoder = NativeHcc2dBridge.createDecoder()
        val decoded = try {
            NativeHcc2dBridge.decodeYuv(
                decoder,
                image.y, 0, image.width, 1,
                image.u, 0, image.width / 2, 1,
                image.v, 0, image.width / 2, 1,
                image.width, image.height
            ).orEmpty()
        } finally {
            NativeHcc2dBridge.releaseDecoder(decoder)
        }
        val valid = decoded.firstOrNull { it.valid }
        assertNotNull("diagonal v40 pose must retain the true version hypothesis", valid)
        assertArrayEquals(payload, valid!!.payload)
    }

    @Test
    fun hcc2dStaysLockedAcrossChangingV40Frames() {
        for (colors in listOf(4, 8)) {
            val decoder = NativeHcc2dBridge.createDecoder()
            try {
                repeat(8) { frameNumber ->
                    val payload = ByteArray(1_024) { (it * 19 + frameNumber * 37).toByte() }
                    val symbol = requireNotNull(NativeHcc2dBridge.encode(payload, colors, 40))
                    val image = Hcc2dSyntheticScene.axisAligned(symbol)
                    val decoded = requireNotNull(
                        NativeHcc2dBridge.decodeYuv(
                            decoder,
                            image.y, 0, image.width, 1,
                            image.u, 0, image.width / 2, 1,
                            image.v, 0, image.width / 2, 1,
                            image.width, image.height
                        )
                    )
                    val valid = decoded.firstOrNull { it.valid }
                    assertNotNull("HCC2D$colors frame $frameNumber did not correct", valid)
                    assertTrue("HCC2D$colors frame $frameNumber was not detected", valid!!.detected)
                    assertArrayEquals(payload, valid.payload)
                }
            } finally {
                NativeHcc2dBridge.releaseDecoder(decoder)
            }
        }
    }

    private fun assertSyntheticRoundTrip(colors: Int, payload: ByteArray) {
        val symbol = requireNotNull(NativeHcc2dBridge.encode(payload, colors, 40))
        val image = Hcc2dSyntheticScene.axisAligned(symbol)
        val decoder = NativeHcc2dBridge.createDecoder()
        val decoded = try {
            requireNotNull(
                NativeHcc2dBridge.decodeYuv(
                    decoder,
                    image.y, 0, image.width, 1,
                    image.u, 0, image.width / 2, 1,
                    image.v, 0, image.width / 2, 1,
                    image.width, image.height
                )
            )
        } finally {
            NativeHcc2dBridge.releaseDecoder(decoder)
        }
        val valid = decoded.firstOrNull { it.valid }
        assertNotNull("HCC2D$colors payload did not pass native correction", valid)
        assertTrue("HCC2D$colors finder geometry was not acquired", valid!!.detected)
        assertEquals(colors, valid.colors)
        assertEquals(40, valid.version)
        assertArrayEquals(payload, valid.payload)
    }
}
