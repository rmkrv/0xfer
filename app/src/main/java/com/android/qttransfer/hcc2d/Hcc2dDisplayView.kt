package com.android.qttransfer.hcc2d

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.IdentityHashMap
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.coroutines.resume

/** Renders one or more native HCC2D palette-index symbols without filtering.
 * The grid rules match the QR sender: each cell keeps an independent quiet
 * zone and only a changed cell is re-rasterised on a staggered update. */
class Hcc2dDisplayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private data class Raster(val bitmap: Bitmap, val dimension: Int)

    private val paint = Paint().apply {
        isAntiAlias = false
        isDither = false
        isFilterBitmap = false
    }
    private var symbols: List<NativeHcc2dEncoded> = emptyList()
    private var rasters: List<Raster> = emptyList()
    private var pendingSymbols: List<NativeHcc2dEncoded>? = null
    private var pendingToken: Any? = null
    private var displayed: (() -> Unit)? = null

    init { setBackgroundColor(Color.WHITE) }

    /** Wait until every supplied symbol has actually been drawn. The sender
     * uses this as the presentation clock so an update cannot be overwritten
     * before Android has displayed it. Must be called on the UI thread. */
    suspend fun present(values: List<NativeHcc2dEncoded>) = suspendCancellableCoroutine<Unit> { continuation ->
        require(values.isNotEmpty())
        require(values.all { it.fullDimension == values[0].fullDimension && it.colors == values[0].colors })
        val token = Any()
        pendingSymbols = values.toList()
        pendingToken = token
        displayed = {
            if (continuation.isActive) continuation.resume(Unit)
        }
        continuation.invokeOnCancellation {
            if (pendingToken === token) {
                pendingSymbols = null
                pendingToken = null
                displayed = null
            }
        }
        invalidate()
    }

    private fun accept(values: List<NativeHcc2dEncoded>) {
        // Sender replaces one grid cell at a time. Reuse the five stable
        // bitmaps rather than converting six module arrays on every tick.
        val cached = IdentityHashMap<NativeHcc2dEncoded, Raster>(symbols.size)
        symbols.forEachIndexed { index, symbol -> cached[symbol] = rasters[index] }
        rasters = values.map { symbol -> cached[symbol] ?: rasterize(symbol) }
        symbols = values
    }

    private fun rasterize(symbol: NativeHcc2dEncoded): Raster {
        val side = symbol.fullDimension
        require(symbol.modules.size == side * side)
        val palette = if (symbol.colors == 8) PALETTE_8 else PALETTE_4
        val argb = IntArray(side * side)
        for (i in symbol.modules.indices) {
            val index = symbol.modules[i].toInt() and 0xFF
            argb[i] = palette.getOrElse(index) { Color.WHITE }
        }
        return Raster(Bitmap.createBitmap(argb, side, side, Bitmap.Config.ARGB_8888), side)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        pendingSymbols?.let { values ->
            accept(values)
            pendingSymbols = null
            pendingToken = null
        }
        val first = rasters.firstOrNull() ?: return
        val columns = sqrt(rasters.size.toDouble()).toInt().coerceAtLeast(1)
        val rows = (rasters.size + columns - 1) / columns
        require(columns * rows == rasters.size) { "Grid must be rectangular" }
        val cellModules = first.dimension + QUIET * 2
        val scale = min(
            width.toFloat() / (columns * cellModules),
            height.toFloat() / (rows * cellModules)
        )
        if (scale <= 0f) return
        val gridWidth = columns * cellModules * scale
        val gridHeight = rows * cellModules * scale
        val left = (width - gridWidth) / 2f
        val top = (height - gridHeight) / 2f
        rasters.forEachIndexed { index, raster ->
            val cellLeft = left + (index % columns) * cellModules * scale
            val cellTop = top + (index / columns) * cellModules * scale
            val imageLeft = cellLeft + QUIET * scale
            val imageTop = cellTop + QUIET * scale
            val imageSide = raster.dimension * scale
            canvas.drawBitmap(
                raster.bitmap,
                null,
                RectF(imageLeft, imageTop, imageLeft + imageSide, imageTop + imageSide),
                paint
            )
        }
        displayed?.also { callback ->
            displayed = null
            callback()
        }
    }

    private companion object {
        const val QUIET = 4
        val PALETTE_4 = intArrayOf(
            Color.rgb(0, 0, 0), Color.rgb(220, 0, 0),
            Color.rgb(0, 200, 220), Color.WHITE
        )
        val PALETTE_8 = intArrayOf(
            Color.rgb(0, 0, 0), Color.rgb(200, 0, 0), Color.rgb(0, 130, 0), Color.rgb(0, 60, 180),
            Color.rgb(0, 215, 235), Color.rgb(255, 220, 50), Color.rgb(255, 130, 230), Color.WHITE
        )
    }
}
