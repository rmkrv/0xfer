package com.android.qttransfer.qr

import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.NotFoundException
import com.google.zxing.Result
import com.google.zxing.ResultPoint
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.common.PerspectiveTransform
import com.google.zxing.qrcode.decoder.Decoder
import com.google.zxing.qrcode.detector.Detector
import com.journeyapps.barcodescanner.Decoder as JDecoder
import java.util.EnumMap

/**
 * Decimen-style QR decoder.
 *
 * Standard ZXing runs full QR detection on every camera frame. At high QR
 * versions the symbol has many small modules, so each module occupies very
 * few pixels and per-frame detection is fragile under blur/scale/interpolation.
 *
 * This decoder instead acquires the QR geometry once ([readFull]: finder
 * pattern positions + module count) and then, on every subsequent frame,
 * reuses that geometry to sample the module grid directly through a
 * perspective transform (homography) — [readTracked] — without re-running
 * general detection. It falls back to [readFull] whenever tracking fails, and
 * the app can call [forgetGeometry] when a decoded frame turns out not to be
 * ours, so it re-acquires on the next frame.
 */
class TrackedQrDecoder : JDecoder(com.google.zxing.qrcode.QRCodeReader()) {

    interface Listener {
        fun onMetrics(
            frameW: Int,
            frameH: Int,
            qrW: Float,
            qrH: Float,
            dim: Int,
            ppm: Float
        )
    }

    var listener: Listener? = null

    private data class Geometry(
        val dim: Int,
        val finder: Array<ResultPoint>,
        val tlX: Float, val tlY: Float,
        val trX: Float, val trY: Float,
        val blX: Float, val blY: Float,
        val brX: Float, val brY: Float
    )

    @Volatile
    private var geometry: Geometry? = null

    private var trackedFailures = 0
    private var trackedReadsSinceFull = 0
    private val moduleSampleOffsets = floatArrayOf(0.32f, 0.68f)

    /*
     * ZXing's Java detector exposes the three finder centres, not the complete
     * position quad used by Decimen's native codec.  Therefore a cached grid
     * cannot safely survive arbitrary hand/camera movement.  Periodically
     * re-running detection gives it a new anchor before accumulated drift
     * becomes a long run of misses, while still avoiding detection for most
     * preview frames.
     */
    // A FRESH hints map must be used for every detect/decode call. ZXing
    // mutates the hints map in place; sharing one across calls corrupts it
    // and makes later detections fail.
    private fun newHints(): Map<DecodeHintType, Any> =
        EnumMap<DecodeHintType, Any>(DecodeHintType::class.java).apply {
            put(DecodeHintType.TRY_HARDER, true)
        }

    /** Drop the acquired geometry so the next frame re-detects from scratch. */
    fun forgetGeometry() {
        geometry = null
        trackedFailures = 0
        trackedReadsSinceFull = 0
    }

    // journeyapps DecoderThread calls decoder.decode(LuminanceSource) and
    // treats a NULL result as "no barcode this frame" — it has NO try/catch
    // around the call, so throwing would crash the DecoderThread. Returning
    // null keeps the decode loop alive and just requests the next preview.
    override fun decode(source: LuminanceSource): Result? = decodeSource(source)

    private fun decodeSource(source: LuminanceSource): Result? {
        val w = source.width
        val h = source.height
        return try {
            val g = geometry
            if (g == null ||
                trackedFailures >= MAX_TRACKED_FAILURES ||
                trackedReadsSinceFull >= REACQUIRE_AFTER_TRACKED_READS
            ) {
                readFull(source, w, h)
            } else {
                try {
                    readTracked(source, g, w, h)
                } catch (e: Exception) {
                    trackedFailures++
                    Log.d(TAG, "tracked failed #$trackedFailures (${e.message})")
                    // One immediate full scan avoids dropping a good frame after
                    // a small camera movement. Repeated failures make the next
                    // call start with full acquisition as well.
                    readFull(source, w, h)
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "decode failed (${e.message})")
            null
        }
    }

    /**
     * Full detection: locate the QR, sample its grid via ZXing's own detector,
     * decode it, and store the geometry so subsequent frames can be tracked.
     */
    private fun readFull(source: LuminanceSource, w: Int, h: Int): Result {
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        val detector = Detector(bitmap.blackMatrix)
        val dr = detector.detect(newHints())
        val bits = dr.bits
        val points = dr.points ?: throw NotFoundException.getNotFoundInstance()
        if (points.size < 3) throw NotFoundException.getNotFoundInstance()

        val dim = bits.height
        val decoded = Decoder().decode(bits, newHints())
        val result = Result(decoded.text, decoded.rawBytes, points, BarcodeFormat.QR_CODE)

        val g = computeGeometry(points, dim)
        geometry = g
        trackedFailures = 0
        trackedReadsSinceFull = 0
        reportMetrics(w, h, g)
        Log.d(TAG, "readFull acquired geometry dim=$dim")
        return result
    }

    /**
     * Tracked decoding: reuse the stored geometry to warp the known module
     * grid onto the current frame, read each module's luminance, binarize the
     * grid, and decode it — no finder-pattern search.
     */
    private fun readTracked(source: LuminanceSource, g: Geometry, w: Int, h: Int): Result {
        val dim = g.dim
        val transform = PerspectiveTransform.quadrilateralToQuadrilateral(
            0f, 0f,
            dim.toFloat(), 0f,
            0f, dim.toFloat(),
            dim.toFloat(), dim.toFloat(),
            g.tlX, g.tlY,
            g.trX, g.trY,
            g.blX, g.blY,
            g.brX, g.brY
        )

        val sw = source.width
        val sh = source.height
        val lum = source.matrix
        val samples = IntArray(dim * dim)
        val pt = FloatArray(2)
        var minL = 255
        var maxL = 0
        var idx = 0
        for (r in 0 until dim) {
            for (c in 0 until dim) {
                // Average four points in the centre of a module rather than
                // trusting one camera pixel. This avoids a module edge becoming
                // a bit flip when the symbol is slightly out of focus.
                var total = 0
                for (dy in moduleSampleOffsets) {
                    for (dx in moduleSampleOffsets) {
                        pt[0] = c + dx
                        pt[1] = r + dy
                        transform.transformPoints(pt)
                        val x = clamp(pt[0].toInt(), 0, sw - 1)
                        val y = clamp(pt[1].toInt(), 0, sh - 1)
                        total += lum[y * sw + x].toInt() and 0xFF
                    }
                }
                val l = total / 4
                samples[idx++] = l
                if (l < minL) minL = l
                if (l > maxL) maxL = l
            }
        }
        val threshold = otsuThreshold(samples, minL, maxL)
        val bits = BitMatrix(dim, dim)
        idx = 0
        for (r in 0 until dim) {
            for (c in 0 until dim) {
                if (samples[idx++] < threshold) bits.set(c, r)
            }
        }

        val decoded = Decoder().decode(bits, newHints())
        trackedFailures = 0
        trackedReadsSinceFull++
        reportMetrics(w, h, g)
        return Result(decoded.text, decoded.rawBytes, g.finder, BarcodeFormat.QR_CODE)
    }

    private fun computeGeometry(points: Array<ResultPoint>, dim: Int): Geometry {
        val tl = points[0]
        val tr = points[1]
        val bl = points[2]

        val topLen = Math.hypot((tr.x - tl.x).toDouble(), (tr.y - tl.y).toDouble()).toFloat()
        val leftLen = Math.hypot((bl.x - tl.x).toDouble(), (bl.y - tl.y).toDouble()).toFloat()
        val msX = topLen / (dim - 7)
        val msY = leftLen / (dim - 7)
        val uX = (tr.x - tl.x) / topLen
        val uY = (tr.y - tl.y) / topLen
        val vX = (bl.x - tl.x) / leftLen
        val vY = (bl.y - tl.y) / leftLen

        // Outer symbol corners are 3.5 modules beyond the finder-pattern centers.
        val off = 3.5f
        val tlX = tl.x - off * msX * uX - off * msY * vX
        val tlY = tl.y - off * msX * uY - off * msY * vY
        val trX = tlX + dim * msX * uX
        val trY = tlY + dim * msX * uY
        val blX = tlX + dim * msY * vX
        val blY = tlY + dim * msY * vY
        val brX = tlX + dim * msX * uX + dim * msY * vX
        val brY = tlY + dim * msX * uY + dim * msY * vY

        return Geometry(dim, points, tlX, tlY, trX, trY, blX, blY, brX, brY)
    }

    private fun reportMetrics(w: Int, h: Int, g: Geometry) {
        val xs = floatArrayOf(g.tlX, g.trX, g.blX, g.brX)
        val ys = floatArrayOf(g.tlY, g.trY, g.blY, g.brY)
        val minX = xs.minOrNull()!!; val maxX = xs.maxOrNull()!!
        val minY = ys.minOrNull()!!; val maxY = ys.maxOrNull()!!
        val qrW = maxX - minX
        val qrH = maxY - minY
        val ppm = ((qrW + qrH) / 2f) / g.dim
        listener?.onMetrics(w, h, qrW, qrH, g.dim, ppm)
        Log.d(
            TAG,
            "metrics frame=${w}x${h} qr=${qrW.toInt()}x${qrH.toInt()} " +
                "v${(g.dim - 17) / 4} dim=${g.dim} ppm=${"%.1f".format(ppm)}"
        )
    }

    private fun clamp(v: Int, lo: Int, hi: Int): Int = if (v < lo) lo else if (v > hi) hi else v

    /** Otsu finds the black/white split from sampled QR modules. */
    private fun otsuThreshold(samples: IntArray, min: Int, max: Int): Int {
        if (max <= min) return 128
        val histogram = IntArray(256)
        var sum = 0L
        for (sample in samples) {
            histogram[sample]++
            sum += sample
        }
        var backgroundWeight = 0
        var backgroundSum = 0L
        var bestVariance = -1.0
        var threshold = (min + max) / 2
        for (t in min until max) {
            backgroundWeight += histogram[t]
            if (backgroundWeight == 0) continue
            val foregroundWeight = samples.size - backgroundWeight
            if (foregroundWeight == 0) break
            backgroundSum += t.toLong() * histogram[t]
            val backgroundMean = backgroundSum.toDouble() / backgroundWeight
            val foregroundMean = (sum - backgroundSum).toDouble() / foregroundWeight
            val variance = backgroundWeight.toDouble() * foregroundWeight *
                (backgroundMean - foregroundMean) * (backgroundMean - foregroundMean)
            if (variance > bestVariance) {
                bestVariance = variance
                threshold = t
            }
        }
        return threshold
    }

    companion object {
        private const val TAG = "TrackedQrDecoder"
        private const val MAX_TRACKED_FAILURES = 2
        private const val REACQUIRE_AFTER_TRACKED_READS = 12
    }
}
