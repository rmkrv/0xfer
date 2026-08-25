package com.android.qttransfer.receiver

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.google.zxing.ResultPoint
import kotlin.math.max

class QROverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.CYAN
        style = Paint.Style.STROKE
        strokeWidth = 8f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        alpha = 200
    }

    private data class Quad(val xy: FloatArray) {
        val centerX: Float get() = (xy[0] + xy[2] + xy[4] + xy[6]) / 4f
        val centerY: Float get() = (xy[1] + xy[3] + xy[5] + xy[7]) / 4f
        val size: Float get() = maxOf(
            kotlin.math.abs(xy[2] - xy[0]), kotlin.math.abs(xy[5] - xy[3]),
            kotlin.math.abs(xy[4] - xy[6]), kotlin.math.abs(xy[7] - xy[1])
        )
    }
    private data class SeenQuad(val quad: Quad, val seenAtMs: Long)

    private val path = Path()
    private var displayedQuads: List<Quad> = emptyList()
    private val seenQuads = mutableListOf<SeenQuad>()
    private var geometryAnimator: ValueAnimator? = null
    private var lastGeometryAtMs = 0L

    private var scale: Float = 1f
    private var offsetX: Float = 0f
    private var offsetY: Float = 0f

    private val clearRunnable = Runnable {
        geometryAnimator?.cancel()
        displayedQuads = emptyList()
        seenQuads.clear()
        invalidate()
    }

    /**
     * Updates the points and mapping configuration.
     * @param resultPoints Detected QR points
     * @param framingRect The framing rect in View coordinates
     * @param previewFramingRect The framing rect in Preview coordinates
     */
    fun updatePoints(resultPoints: List<ResultPoint>?, framingRect: Rect?, previewFramingRect: Rect?) {
        updateSymbols(resultPoints?.let { listOf(it) }, framingRect, previewFramingRect)
    }

    /** Draw one outline for every QR decoded from the same camera frame. */
    fun updateSymbols(
        decodedSymbols: List<List<ResultPoint>>?,
        framingRect: Rect?,
        previewFramingRect: Rect?
    ) {
        val usable = decodedSymbols.orEmpty().filter { it.isNotEmpty() }
        if (usable.isEmpty() || framingRect == null || previewFramingRect == null) return

        // PreviewView defaults to FILL_CENTER. Width-only scaling was the
        // reason outlines appeared above the QR on a tall portrait screen:
        // the preview is centre-cropped vertically/horizontally to fill it.
        scale = max(
            framingRect.width().toFloat() / previewFramingRect.width(),
            framingRect.height().toFloat() / previewFramingRect.height()
        )
        offsetX = framingRect.left + (framingRect.width() - previewFramingRect.width() * scale) / 2f
        offsetY = framingRect.top + (framingRect.height() - previewFramingRect.height() * scale) / 2f

        val incoming = usable.mapNotNull(::mapQuad)
        if (incoming.isEmpty()) return

        // Full scans can report every grid cell together, while the faster
        // tracked path completes each crop separately. Keep recent locations
        // so a 2/4-code grid remains visible while those completions arrive.
        val now = SystemClock.uptimeMillis()
        seenQuads.removeAll { now - it.seenAtMs > OUTLINE_TTL_MS }
        incoming.forEach { candidate ->
            val index = seenQuads.indexOfFirst { existing -> sameLocation(existing.quad, candidate) }
            if (index >= 0) seenQuads[index] = SeenQuad(candidate, now)
            else if (seenQuads.size < MAX_OUTLINES) seenQuads += SeenQuad(candidate, now)
        }
        val target = seenQuads
            .map { it.quad }
            .sortedWith(compareBy<Quad> { it.centerY }.thenBy { it.centerX })

        // Decoder callbacks can arrive at camera rate. Geometry is expensive
        // only when a new target is accepted; retain and animate it between
        // updates rather than remapping every decoded camera frame.
        if (target.size == displayedQuads.size && now - lastGeometryAtMs < GEOMETRY_INTERVAL_MS) {
            renewClearTimer()
            return
        }
        lastGeometryAtMs = now
        animateTo(target)
        renewClearTimer()
    }

    private fun renewClearTimer() {
        removeCallbacks(clearRunnable)
        postDelayed(clearRunnable, CLEAR_AFTER_MS)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (displayedQuads.isEmpty()) return

        displayedQuads.forEach { quad ->
            path.reset()
            path.moveTo(quad.xy[0], quad.xy[1])
            path.lineTo(quad.xy[2], quad.xy[3])
            path.lineTo(quad.xy[4], quad.xy[5])
            path.lineTo(quad.xy[6], quad.xy[7])
            path.close()
            canvas.drawPath(path, paint)
        }
    }

    private fun mapQuad(points: List<ResultPoint>): Quad? {
        if (points.size < 3) return null
        fun mapX(x: Float) = x * scale + offsetX
        fun mapY(y: Float) = y * scale + offsetY
        // The decoder gives BL, TL, TR, BR. Synthesize BR for a fallback
        // decoder that only reports finder centres.
        val blX = mapX(points[0].x)
        val blY = mapY(points[0].y)
        val tlX = mapX(points[1].x)
        val tlY = mapY(points[1].y)
        val trX = mapX(points[2].x)
        val trY = mapY(points[2].y)
        val brX = if (points.size >= 4) mapX(points[3].x) else blX + trX - tlX
        val brY = if (points.size >= 4) mapY(points[3].y) else blY + trY - tlY
        return Quad(floatArrayOf(tlX, tlY, trX, trY, brX, brY, blX, blY))
    }

    private fun animateTo(target: List<Quad>) {
        geometryAnimator?.cancel()
        if (displayedQuads.size != target.size) {
            displayedQuads = target
            invalidate()
            return
        }
        val start = displayedQuads.map { Quad(it.xy.copyOf()) }
        geometryAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ANIMATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                val fraction = animator.animatedValue as Float
                displayedQuads = target.indices.map { index ->
                    val from = start[index].xy
                    val to = target[index].xy
                    Quad(FloatArray(8) { point -> from[point] + (to[point] - from[point]) * fraction })
                }
                invalidate()
            }
            start()
        }
    }

    private fun sameLocation(a: Quad, b: Quad): Boolean {
        val distance = kotlin.math.hypot(a.centerX - b.centerX, a.centerY - b.centerY)
        return distance < maxOf(a.size, b.size) / 2f
    }

    private companion object {
        const val GEOMETRY_INTERVAL_MS = 100L
        const val CLEAR_AFTER_MS = 350L
        const val OUTLINE_TTL_MS = 500L
        const val ANIMATION_MS = 120L
        const val MAX_OUTLINES = 6
    }
}
