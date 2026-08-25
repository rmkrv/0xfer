package com.android.xfer.hcc2d

import java.nio.ByteBuffer
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Deterministic camera-like HCC2D frames for instrumentation tests.
 *
 * Coordinates deliberately use the decoder's convention: [innerQuad] maps
 * the *inner* QR-compatible grid boundaries {0,0}, {dim,0}, {dim,dim},
 * {0,dim}.  It does not include HCC2D's one-module palette border or the
 * display quiet zone.  This is the convention required by
 * [Hcc2dDecoder.setGeometry] and by Decimen's GridSampler ABI.
 */
object Hcc2dSyntheticScene {
    data class Frame(
        val y: ByteBuffer,
        val u: ByteBuffer,
        val v: ByteBuffer,
        val width: Int,
        val height: Int,
        val innerQuad: DoubleArray,
        val innerDimension: Int
    )

    /** A square, unwarped symbol with the same four-module white quiet zone
     * used by [Hcc2dDisplayView]. */
    fun axisAligned(
        symbol: NativeHcc2dEncoded,
        pixelsPerModule: Int = 4,
        quietModules: Int = 4
    ): Frame {
        require(pixelsPerModule > 0)
        require(quietModules >= 0)
        val dim = symbol.fullDimension - 2
        val side = (symbol.fullDimension + quietModules * 2) * pixelsPerModule
        val innerOrigin = (quietModules + 1) * pixelsPerModule.toDouble()
        val innerSide = dim * pixelsPerModule.toDouble()
        return perspective(
            symbol,
            side,
            side,
            doubleArrayOf(
                innerOrigin, innerOrigin,
                innerOrigin + innerSide, innerOrigin,
                innerOrigin + innerSide, innerOrigin + innerSide,
                innerOrigin, innerOrigin + innerSide
            )
        )
    }

    /** Renders several same-version HCC2D symbols into one camera frame.
     * This exercises the native multi-code locator rather than repeatedly
     * calling a one-symbol decoder. */
    fun grid(
        symbols: List<NativeHcc2dEncoded>,
        columns: Int,
        pixelsPerModule: Int = 4,
        quietModules: Int = 4
    ): Frame {
        require(symbols.isNotEmpty())
        require(columns in 1..symbols.size)
        require(pixelsPerModule > 0 && quietModules >= 0)
        val fullDimension = symbols.first().fullDimension
        require(symbols.all { it.fullDimension == fullDimension })
        val rows = (symbols.size + columns - 1) / columns
        val cellSide = (fullDimension + quietModules * 2) * pixelsPerModule
        val width = cellSide * columns
        val height = cellSide * rows
        require(width % 2 == 0 && height % 2 == 0)
        val y = ByteBuffer.allocateDirect(width * height)
        val u = ByteBuffer.allocateDirect(width / 2 * (height / 2))
        val v = ByteBuffer.allocateDirect(width / 2 * (height / 2))
        val colourAt = { px: Int, py: Int ->
            val column = px / cellSide
            val row = py / cellSide
            val index = row * columns + column
            if (index !in symbols.indices) {
                WHITE
            } else {
                val localX = px % cellSide
                val localY = py % cellSide
                val moduleX = localX / pixelsPerModule - quietModules
                val moduleY = localY / pixelsPerModule - quietModules
                val symbol = symbols[index]
                if (moduleX !in 0 until fullDimension || moduleY !in 0 until fullDimension) {
                    WHITE
                } else {
                    palette(symbol.colors, symbol.modules[moduleY * fullDimension + moduleX].toInt() and 0xFF)
                }
            }
        }
        for (py in 0 until height) for (px in 0 until width) {
            y.put(py * width + px, yOf(colourAt(px, py)).toByte())
        }
        for (cy in 0 until height / 2) for (cx in 0 until width / 2) {
            var uu = 0
            var vv = 0
            for (dy in 0..1) for (dx in 0..1) {
                val rgb = colourAt(cx * 2 + dx, cy * 2 + dy)
                val yy = yOf(rgb)
                uu += uOf(rgb, yy)
                vv += vOf(rgb, yy)
            }
            val index = cy * (width / 2) + cx
            u.put(index, (uu / 4).toByte())
            v.put(index, (vv / 4).toByte())
        }
        val innerDimension = fullDimension - 2
        val innerOrigin = (quietModules + 1) * pixelsPerModule.toDouble()
        val innerSide = innerDimension * pixelsPerModule.toDouble()
        return Frame(
            y, u, v, width, height,
            doubleArrayOf(
                innerOrigin, innerOrigin,
                innerOrigin + innerSide, innerOrigin,
                innerOrigin + innerSide, innerOrigin + innerSide,
                innerOrigin, innerOrigin + innerSide
            ),
            innerDimension
        )
    }

    /**
     * Renders [symbol] into an exact YUV_420_888-compatible planar frame.
     * [innerQuad] is TL, TR, BR, BL in camera pixel coordinates. The image is
     * white outside the symbol's palette border and quiet zone.
     *
     * Chroma is box-filtered into 2x2 samples rather than copied from one
     * luma pixel. That exposes module/chroma-boundary behaviour that an
     * ordinary RGB bitmap test misses.
     */
    fun perspective(
        symbol: NativeHcc2dEncoded,
        width: Int,
        height: Int,
        innerQuad: DoubleArray
    ): Frame {
        require(width > 1 && height > 1)
        require(width % 2 == 0 && height % 2 == 0) { "YUV420 frame dimensions must be even" }
        require(innerQuad.size == 8)
        val dim = symbol.fullDimension - 2
        require(dim >= 21 && 17 + ((dim - 17) / 4) * 4 == dim)
        val forward = homography(
            doubleArrayOf(0.0, 0.0, dim.toDouble(), 0.0, dim.toDouble(), dim.toDouble(), 0.0, dim.toDouble()),
            innerQuad
        )
        val inverse = invert3x3(forward)
        val y = ByteBuffer.allocateDirect(width * height)
        val u = ByteBuffer.allocateDirect(width / 2 * (height / 2))
        val v = ByteBuffer.allocateDirect(width / 2 * (height / 2))

        val colourAt = { x: Double, yy: Double ->
            val source = apply(inverse, x, yy)
            val moduleX = floor(source[0]).toInt()
            val moduleY = floor(source[1]).toInt()
            if (moduleX !in -1..dim || moduleY !in -1..dim) {
                WHITE
            } else {
                val index = (moduleY + 1) * symbol.fullDimension + (moduleX + 1)
                palette(symbol.colors, symbol.modules[index].toInt() and 0xFF)
            }
        }

        for (py in 0 until height) for (px in 0 until width) {
            val rgb = colourAt(px + .5, py + .5)
            y.put(py * width + px, yOf(rgb).toByte())
        }
        for (cy in 0 until height / 2) for (cx in 0 until width / 2) {
            var uu = 0
            var vv = 0
            for (dy in 0..1) for (dx in 0..1) {
                val rgb = colourAt(cx * 2 + dx + .5, cy * 2 + dy + .5)
                val yy = yOf(rgb)
                uu += uOf(rgb, yy)
                vv += vOf(rgb, yy)
            }
            val index = cy * (width / 2) + cx
            u.put(index, (uu / 4).toByte())
            v.put(index, (vv / 4).toByte())
        }
        return Frame(y, u, v, width, height, innerQuad.copyOf(), dim)
    }

    /**
     * Applies the kind of uneven luma lift that occurs when a camera faces a
     * bright phone display in a darker room.  Black modules on one side of
     * the code become visibly gray while the opposite side remains black;
     * chroma is intentionally left alone because this is an illumination,
     * not a palette, perturbation.
     */
    fun unevenScreenLighting(frame: Frame, minLift: Int = 6, maxLift: Int = 72): Frame {
        require(minLift in 0..255 && maxLift in minLift..255)
        for (py in 0 until frame.height) for (px in 0 until frame.width) {
            val t = (px.toDouble() / (frame.width - 1).coerceAtLeast(1) +
                py.toDouble() / (frame.height - 1).coerceAtLeast(1)) * .5
            val lift = (minLift + (maxLift - minLift) * t).roundToInt()
            val index = py * frame.width + px
            val value = frame.y.get(index).toInt() and 0xFF
            frame.y.put(index, (value + lift).coerceAtMost(255).toByte())
        }
        return frame
    }

    private fun palette(colors: Int, index: Int): IntArray = when (colors) {
        4 -> PALETTE_4.getOrElse(index) { WHITE }
        8 -> PALETTE_8.getOrElse(index) { WHITE }
        else -> WHITE
    }

    private fun yOf(c: IntArray) = (0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2]).roundToInt().coerceIn(0, 255)
    private fun uOf(c: IntArray, yy: Int) = (128 + 0.564 * (c[2] - yy)).roundToInt().coerceIn(0, 255)
    private fun vOf(c: IntArray, yy: Int) = (128 + 0.713 * (c[0] - yy)).roundToInt().coerceIn(0, 255)

    /** Returns an H where destination ~= H * source. */
    private fun homography(source: DoubleArray, destination: DoubleArray): DoubleArray {
        require(source.size == 8 && destination.size == 8)
        val augmented = Array(8) { DoubleArray(9) }
        for (i in 0 until 4) {
            val x = source[i * 2]
            val yy = source[i * 2 + 1]
            val xx = destination[i * 2]
            val y = destination[i * 2 + 1]
            augmented[i * 2][0] = x
            augmented[i * 2][1] = yy
            augmented[i * 2][2] = 1.0
            augmented[i * 2][6] = -xx * x
            augmented[i * 2][7] = -xx * yy
            augmented[i * 2][8] = xx
            augmented[i * 2 + 1][3] = x
            augmented[i * 2 + 1][4] = yy
            augmented[i * 2 + 1][5] = 1.0
            augmented[i * 2 + 1][6] = -y * x
            augmented[i * 2 + 1][7] = -y * yy
            augmented[i * 2 + 1][8] = y
        }
        for (column in 0 until 8) {
            var pivot = column
            for (row in column + 1 until 8)
                if (kotlin.math.abs(augmented[row][column]) > kotlin.math.abs(augmented[pivot][column])) pivot = row
            require(kotlin.math.abs(augmented[pivot][column]) > 1e-12) { "Degenerate quadrilateral" }
            val temp = augmented[column]
            augmented[column] = augmented[pivot]
            augmented[pivot] = temp
            val scale = augmented[column][column]
            for (j in column until 9) augmented[column][j] /= scale
            for (row in 0 until 8) if (row != column) {
                val factor = augmented[row][column]
                for (j in column until 9) augmented[row][j] -= factor * augmented[column][j]
            }
        }
        return doubleArrayOf(
            augmented[0][8], augmented[1][8], augmented[2][8],
            augmented[3][8], augmented[4][8], augmented[5][8],
            augmented[6][8], augmented[7][8], 1.0
        )
    }

    private fun invert3x3(m: DoubleArray): DoubleArray {
        val determinant = m[0] * (m[4] * m[8] - m[5] * m[7]) -
            m[1] * (m[3] * m[8] - m[5] * m[6]) +
            m[2] * (m[3] * m[7] - m[4] * m[6])
        require(kotlin.math.abs(determinant) > 1e-12) { "Singular homography" }
        val inverseDet = 1.0 / determinant
        return doubleArrayOf(
            (m[4] * m[8] - m[5] * m[7]) * inverseDet,
            (m[2] * m[7] - m[1] * m[8]) * inverseDet,
            (m[1] * m[5] - m[2] * m[4]) * inverseDet,
            (m[5] * m[6] - m[3] * m[8]) * inverseDet,
            (m[0] * m[8] - m[2] * m[6]) * inverseDet,
            (m[2] * m[3] - m[0] * m[5]) * inverseDet,
            (m[3] * m[7] - m[4] * m[6]) * inverseDet,
            (m[1] * m[6] - m[0] * m[7]) * inverseDet,
            (m[0] * m[4] - m[1] * m[3]) * inverseDet
        )
    }

    private fun apply(m: DoubleArray, x: Double, y: Double): DoubleArray {
        val denominator = m[6] * x + m[7] * y + m[8]
        return doubleArrayOf(
            (m[0] * x + m[1] * y + m[2]) / denominator,
            (m[3] * x + m[4] * y + m[5]) / denominator
        )
    }

    private val WHITE = intArrayOf(255, 255, 255)
    private val PALETTE_4 = arrayOf(
        intArrayOf(0, 0, 0), intArrayOf(220, 0, 0),
        intArrayOf(0, 200, 220), WHITE
    )
    private val PALETTE_8 = arrayOf(
        intArrayOf(0, 0, 0), intArrayOf(200, 0, 0), intArrayOf(0, 130, 0), intArrayOf(0, 60, 180),
        intArrayOf(0, 215, 235), intArrayOf(255, 220, 50), intArrayOf(255, 130, 230), WHITE
    )
}
