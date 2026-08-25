package com.android.xfer.qr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.google.zxing.common.BitMatrix
import java.util.IdentityHashMap
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Displays QR frames as pre-rasterised bitmaps.
 *
 * Drawing every dark module with Canvas.drawRect() turns a V40 frame into
 * roughly 15,000 drawing calls and prevents the display from reaching the
 * selected transfer FPS. A one-pixel/module bitmap preserves exact square
 * modules while each cell becomes one nearest-neighbour blit.
 */
class QrDisplayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private val bitmapPaint = Paint().apply {
        isAntiAlias = false
        // QR modules must stay visibly square. Scaling by a whole number of
        // pixels keeps their hard black/white edges instead of softening the
        // code into grey blocks.
        isFilterBitmap = false
        isDither = false
    }
    private var matrices: List<BitMatrix> = emptyList()
    private var bitmaps: List<Bitmap> = emptyList()

    init { setBackgroundColor(Color.WHITE) }

    fun setMatrix(value: BitMatrix) = setMatrices(listOf(value))

    /** Tiles 1, 2, 3, 4, or 6 same-version QR matrices with an independent quiet zone per cell.
     * Three is deliberately a one-column vertical stack: code1, code2, code3. */
    fun setMatrices(values: List<BitMatrix>) {
        require(values.isNotEmpty())
        require(values.all { it.width == values[0].width && it.height == values[0].height })

        // SenderActivity mutates its working MutableList in place before it
        // calls us again. Retaining that list made a new matrix inherit the
        // bitmap at the same index from the previous frame, visually freezing
        // the QR even though the transfer coroutine kept running.
        val snapshot = values.toList()

        // A staggered grid replaces just one matrix per tick. Reuse the other
        // cell rasters by identity, so a frame update allocates/rasterises one
        // small bitmap instead of rebuilding the whole grid on the UI thread.
        val cached = IdentityHashMap<BitMatrix, Bitmap>(matrices.size)
        matrices.forEachIndexed { index, matrix -> cached[matrix] = bitmaps[index] }
        bitmaps = snapshot.map { matrix -> cached[matrix] ?: rasterize(matrix) }
        matrices = snapshot
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val qr = matrices.firstOrNull() ?: return
        val columns = sqrt(matrices.size.toDouble()).toInt().coerceAtLeast(1)
        val rows = (matrices.size + columns - 1) / columns
        require(columns * rows == matrices.size) { "Grid must be rectangular" }
        val cellModules = qr.width + QUIET_ZONE * 2
        // Do not round down to a whole-pixel module scale. On displays whose
        // dimensions are not exact multiples of the code, that left large
        // unused margins. The nearest-neighbour bitmap draw keeps edges hard
        // while distributing the unavoidable sub-pixel remainder across
        // modules, so every grid uses the largest possible square area.
        val scale = min(
            width.toFloat() / (columns * cellModules),
            height.toFloat() / (rows * cellModules)
        )
        if (scale <= 0f) return

        val gridWidth = columns * cellModules * scale
        val gridHeight = rows * cellModules * scale
        val left = (width - gridWidth) / 2f
        val top = (height - gridHeight) / 2f
        bitmaps.forEachIndexed { index, bitmap ->
            val cellLeft = left + (index % columns) * cellModules * scale
            val cellTop = top + (index / columns) * cellModules * scale
            canvas.drawBitmap(
                bitmap,
                null,
                RectF(cellLeft, cellTop, cellLeft + cellModules * scale, cellTop + cellModules * scale),
                bitmapPaint
            )
        }
    }

    private fun rasterize(matrix: BitMatrix): Bitmap {
        val side = matrix.width + QUIET_ZONE * 2
        val pixels = IntArray(side * side) { Color.WHITE }
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix[x, y]) pixels[(y + QUIET_ZONE) * side + x + QUIET_ZONE] = Color.BLACK
            }
        }
        return Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888)
    }

    private companion object { const val QUIET_ZONE = 4 }
}
