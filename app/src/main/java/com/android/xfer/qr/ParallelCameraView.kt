package com.android.xfer.qr

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.graphics.Rect
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.zxing.ResultPoint
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

/**
 * Camera2/CameraX QR stream reader.
 *
 * The former JourneyApps Camera1 path silently chose a preview size and frame
 * rate based on the view. This view requests a 1280×960 4:3 analysis stream,
 * matching Decimen's capture target, keeps only the latest frame, then feeds
 * that upright luminance image to the native Decimen decoder.
 */
class ParallelCameraView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {
    data class DecodedSymbol(val bytes: ByteArray, val points: Array<ResultPoint>)

    interface Listener {
        fun onDecoded(symbols: List<DecodedSymbol>, frameWidth: Int, frameHeight: Int)

        /** Full-frame acquisition results, before crop tracking takes over. */
        fun onFullScan(symbolsReported: Int, symbolsDecoded: Int, activeRegions: Int, width: Int, height: Int) = Unit

        /** Benchmark hooks. A camera frame may lead to zero or several native
         * decode attempts while tracker crops are active. */
        fun onCameraFrame() = Unit
        fun onNativeDecodeAttempt(durationNanos: Long) = Unit
    }

    private data class Region(
        val modules: Int?,
        val quad: DoubleArray?,
        val bounds: Rect,
        val decoded: Boolean,
        val drift: Float,
        val seenAtMs: Long
    )

    private data class NativeCall<T>(val started: Boolean, val value: T?)
    private data class SensorFrame(
        val luma: ByteArray,
        val width: Int,
        val height: Int,
        val rotationDegrees: Int
    )

    var listener: Listener? = null

    // Match Decimen's receiver pool: on an eight-core phone this gives six
    // decoder slots rather than four. Stale frames are dropped, so extra slots
    // increase useful captures instead of building a latency queue.
    private val workerCount = min(6, max(2, Runtime.getRuntime().availableProcessors()))
    private val inFlight = AtomicInteger(0)
    private val fullScanQueued = AtomicBoolean(false)
    private val regionLock = Any()
    private val regions = mutableListOf<Region>()
    private val fullCalls = Semaphore(workerCount, true)
    private val trackedCalls = Semaphore(workerCount, true)
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val previewView = PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    }

    @Volatile private var decoding = false
    @Volatile private var lastFullScanAtMs = 0L
    private var expectedRegions = 0
    private var expectedRegionsAtMs = 0L
    private var cropRotate = 0
    private var workers = Executors.newFixedThreadPool(workerCount)
    private var provider: ProcessCameraProvider? = null
    private var boundOwner: LifecycleOwner? = null
    private var boundLens = CameraSelector.LENS_FACING_BACK
    private val cameraGeneration = AtomicInteger(0)

    init {
        addView(
            previewView,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
    }

    /** Start the back/front Camera2 analysis pipeline for [owner]. */
    fun start(owner: LifecycleOwner, lensFacing: Int = CameraSelector.LENS_FACING_BACK) {
        if (decoding && boundOwner === owner && boundLens == lensFacing) return
        boundOwner = owner
        boundLens = lensFacing
        val generation = cameraGeneration.incrementAndGet()
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (generation != cameraGeneration.get()) return@addListener
            val cameraProvider = try {
                future.get()
            } catch (_: Exception) {
                return@addListener
            }
            provider = cameraProvider
            bind(cameraProvider, owner, lensFacing, generation)
        }, ContextCompat.getMainExecutor(context))
    }

    /** Stop analysis and release the camera use cases. */
    fun stop() {
        cameraGeneration.incrementAndGet()
        decoding = false
        provider?.unbindAll()
        workers.shutdownNow()
        inFlight.set(0)
        fullScanQueued.set(false)
        synchronized(regionLock) { regions.clear() }
    }

    private fun bind(
        cameraProvider: ProcessCameraProvider,
        owner: LifecycleOwner,
        lensFacing: Int,
        generation: Int
    ) {
        val rotation = display?.rotation ?: Surface.ROTATION_0
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        val fpsRange = bestFpsRange(cameraProvider, selector)
        val focusMode = continuousFocusMode(cameraProvider, selector)
        val resolution = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(
                    CAPTURE_SIZE,
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            )
            .build()
        val previewBuilder = Preview.Builder()
            .setResolutionSelector(resolution)
            .setTargetRotation(rotation)
        fpsRange?.let {
            Camera2Interop.Extender(previewBuilder).setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it
            )
        }
        focusMode?.let {
            Camera2Interop.Extender(previewBuilder).setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE, it
            )
        }
        val preview = previewBuilder.build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val analysisBuilder = ImageAnalysis.Builder()
            .setResolutionSelector(resolution)
            .setTargetRotation(rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
        fpsRange?.let {
            Camera2Interop.Extender(analysisBuilder).setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it
            )
        }
        focusMode?.let {
            Camera2Interop.Extender(analysisBuilder).setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE, it
            )
        }
        val analysis = analysisBuilder.build()

        analysis.setAnalyzer(cameraExecutor) { image ->
            try {
                if (decoding && generation == cameraGeneration.get()) {
                    val frame = copySensorLuma(image)
                    processFrame(frame.luma, frame.width, frame.height, frame.rotationDegrees)
                    post { if (decoding) listener?.onCameraFrame() }
                }
            } finally {
                image.close()
            }
        }

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(owner, selector, preview, analysis)
            startPipeline()
        } catch (_: Exception) {
            analysis.clearAnalyzer()
        }
    }

    private fun startPipeline() {
        if (workers.isShutdown) workers = Executors.newFixedThreadPool(workerCount)
        decoding = true
        synchronized(regionLock) { regions.clear() }
        fullScanQueued.set(false)
        lastFullScanAtMs = 0L
        expectedRegions = 0
        expectedRegionsAtMs = 0L
        cropRotate = 0
    }

    /** Ask Camera2 for the fastest normal capture range the chosen rear/front
     * camera advertises. Decimen explicitly asks for 60 fps; leaving this
     * unset commonly negotiates 30 fps even on cameras that can supply 60. */
    private fun bestFpsRange(provider: ProcessCameraProvider, selector: CameraSelector): Range<Int>? = try {
        selector.filter(provider.availableCameraInfos)
            .flatMap { info ->
                Camera2CameraInfo.from(info)
                    .getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                    ?.asList()
                    .orEmpty()
            }
            .filter { it.upper >= MIN_CAPTURE_FPS }
            .maxWithOrNull(compareBy<Range<Int>> { it.upper }.thenBy { it.lower })
    } catch (_: Exception) {
        null
    }

    /** The default AF mode may settle once then stop. A phone pointed at a
     * display benefits greatly from continuous focus: a slight distance or
     * thermal lens drift otherwise leaves finder patterns visible but enough
     * data modules soft for Reed–Solomon to reject the frame. */
    private fun continuousFocusMode(provider: ProcessCameraProvider, selector: CameraSelector): Int? = try {
        val modes = selector.filter(provider.availableCameraInfos)
            .flatMap { info ->
                Camera2CameraInfo.from(info)
                    .getCameraCharacteristic(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                    ?.asList()
                    .orEmpty()
            }
        when {
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE in modes ->
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO in modes ->
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    private fun processFrame(luma: ByteArray, width: Int, height: Int, rotationDegrees: Int) {
        val now = SystemClock.elapsedRealtime()
        val snapshot = synchronized(regionLock) {
            regions.removeAll { now - it.seenAtMs > REGION_TTL_MS }
            regions.toList()
        }
        val live = snapshot.count { it.decoded }
        if (live >= expectedRegions || now - expectedRegionsAtMs > EXPECTED_REGIONS_DECAY_MS) {
            expectedRegions = live
            expectedRegionsAtMs = now
        }
        val fullInterval = when {
            live == 0 -> ACQUISITION_SCAN_INTERVAL_MS
            live < expectedRegions -> DEGRADED_SCAN_INTERVAL_MS
            else -> HEALTHY_SCAN_INTERVAL_MS
        }
        if (now - lastFullScanAtMs >= fullInterval) {
            scheduleFullScan(luma, width, height, rotationDegrees, now)
        } else if (snapshot.isNotEmpty()) {
            for (i in snapshot.indices) {
                val region = snapshot[(i + cropRotate) % snapshot.size]
                if (!scheduleTracked(luma, width, height, rotationDegrees, region)) break
            }
            cropRotate++
        }
    }

    private fun scheduleFullScan(luma: ByteArray, width: Int, height: Int, rotationDegrees: Int, now: Long) {
        if (!fullScanQueued.compareAndSet(false, true)) return
        lastFullScanAtMs = now
        if (!submit {
                try {
                    val call = callNative<Array<NativeQrResult>>(fullCalls) {
                        NativeDecimenBridge.readFullAll(luma, width, height) ?: emptyArray()
                    }
                    if (!call.started) {
                        lastFullScanAtMs = 0L
                        return@submit
                    }
                    val all = call.value ?: emptyArray()
                    if (all.isNotEmpty()) {
                        remember(all, 0, 0)
                        deliver(all, 0, 0, width, height, rotationDegrees)
                    }
                    val decoded = all.count { it.valid && it.bytes.isNotEmpty() }
                    val active = synchronized(regionLock) { regions.count { it.decoded } }
                    post {
                        if (decoding) listener?.onFullScan(all.size, decoded, active, width, height)
                    }
                } finally {
                    fullScanQueued.set(false)
                }
            }) {
            fullScanQueued.set(false)
            // A due scan must not be treated as completed merely because the
            // decode pool was busy with the preceding crop frame. Retry on the
            // next camera image instead of waiting another healthy 1.5 s.
            lastFullScanAtMs = 0L
        }
    }

    private fun scheduleTracked(
        frame: ByteArray,
        frameWidth: Int,
        frameHeight: Int,
        rotationDegrees: Int,
        region: Region
    ): Boolean {
        val crop = cropFor(region, frameWidth, frameHeight) ?: return false
        return submit {
            val luma = cropLuma(frame, frameWidth, crop)
            val localQuad = region.quad?.copyOf()?.also { quad ->
                for (i in quad.indices step 2) {
                    quad[i] -= crop.left
                    quad[i + 1] -= crop.top
                }
            }
            val trackedCall = if (localQuad != null && region.modules != null) {
                callNative(trackedCalls) {
                    NativeDecimenBridge.readTracked(luma, crop.width(), crop.height(), region.modules, localQuad)
                }
            } else NativeCall(started = true, value = null)
            if (!trackedCall.started) return@submit
            val decoded = if (trackedCall.value != null) {
                arrayOf(trackedCall.value)
            } else {
                callNative<Array<NativeQrResult>>(fullCalls) {
                    NativeDecimenBridge.readFull(luma, crop.width(), crop.height())
                        ?.let { arrayOf(it) } ?: emptyArray()
                }.value ?: emptyArray()
            }
            if (decoded.isNotEmpty()) {
                remember(decoded, crop.left, crop.top)
                deliver(decoded, crop.left, crop.top, frameWidth, frameHeight, rotationDegrees)
            }
        }
    }

    private fun submit(task: () -> Unit): Boolean {
        if (!decoding || inFlight.incrementAndGet() > workerCount) {
            inFlight.decrementAndGet()
            return false
        }
        return try {
            workers.execute {
                try {
                    if (decoding) task()
                } finally {
                    inFlight.updateAndGet { active -> (active - 1).coerceAtLeast(0) }
                }
            }
            true
        } catch (_: RejectedExecutionException) {
            inFlight.updateAndGet { active -> (active - 1).coerceAtLeast(0) }
            false
        }
    }

    private fun <T> callNative(semaphore: Semaphore, action: () -> T): NativeCall<T> {
        if (!semaphore.tryAcquire()) return NativeCall(started = false, value = null)
        val startedAt = System.nanoTime()
        return try {
            NativeCall(started = true, value = if (decoding) action() else null)
        } catch (_: Exception) {
            NativeCall(started = true, value = null)
        } finally {
            semaphore.release()
            val duration = System.nanoTime() - startedAt
            post { if (decoding) listener?.onNativeDecodeAttempt(duration) }
        }
    }

    private fun remember(found: Array<NativeQrResult>, offsetX: Int, offsetY: Int) {
        val now = SystemClock.elapsedRealtime()
        val incoming = found.mapNotNull { symbol ->
            if (symbol.modules <= 0 || symbol.quad.size != 8) return@mapNotNull null
            val shifted = symbol.quad.copyOf().also { quad ->
                for (i in quad.indices step 2) {
                    quad[i] += offsetX
                    quad[i + 1] += offsetY
                }
            }
            val bounds = boundsOf(shifted) ?: return@mapNotNull null
            Region(symbol.modules, shifted, bounds, symbol.valid && symbol.bytes.isNotEmpty(), 0f, now)
        }
        if (incoming.isEmpty()) return
        synchronized(regionLock) {
            regions.removeAll { now - it.seenAtMs > REGION_TTL_MS }
            incoming.forEach { candidate ->
                val index = regions.indexOfFirst { existing -> sameSymbol(existing, candidate) }
                if (index >= 0) {
                    val existing = regions[index]
                    if (candidate.decoded) {
                        val distance = kotlin.math.hypot(
                            existing.bounds.exactCenterX() - candidate.bounds.exactCenterX(),
                            existing.bounds.exactCenterY() - candidate.bounds.exactCenterY()
                        )
                        regions[index] = candidate.copy(decoded = true, drift = existing.drift * 0.5f + distance * 0.5f)
                    } else {
                        regions[index] = existing.copy(seenAtMs = now)
                    }
                } else if (candidate.decoded) {
                    if (regions.size < MAX_REGIONS) regions += candidate
                } else {
                    val reference = regions.firstOrNull { it.decoded } ?: return@forEach
                    val ratio = max(candidate.bounds.width(), candidate.bounds.height()).toFloat() /
                        max(reference.bounds.width(), reference.bounds.height()).toFloat()
                    if (ratio in 0.5f..2f && regions.size < MAX_REGIONS) {
                        regions += candidate.copy(modules = null, quad = null, decoded = false)
                    }
                }
            }
        }
    }

    private fun deliver(
        found: Array<NativeQrResult>,
        offsetX: Int,
        offsetY: Int,
        frameWidth: Int,
        frameHeight: Int,
        rotationDegrees: Int
    ) {
        val symbols = found.asSequence()
            .filter { it.valid && it.bytes.isNotEmpty() && it.quad.size == 8 }
            .map { symbol ->
                val quad = symbol.quad
                fun point(index: Int): ResultPoint {
                    val x = quad[index] + offsetX
                    val y = quad[index + 1] + offsetY
                    val (uprightX, uprightY) = uprightPoint(x, y, frameWidth, frameHeight, rotationDegrees)
                    return ResultPoint(uprightX, uprightY)
                }
                DecodedSymbol(
                    symbol.bytes,
                    arrayOf(
                        point(6), point(0), point(2), point(4)
                    )
                )
            }.toList()
        if (symbols.isNotEmpty() && decoding) {
            val uprightWidth = if (rotationDegrees % 180 == 0) frameWidth else frameHeight
        val uprightHeight = if (rotationDegrees % 180 == 0) frameHeight else frameWidth
            post { if (decoding) listener?.onDecoded(symbols, uprightWidth, uprightHeight) }
        }
    }

    /** Transform only decoded corner points for the portrait preview; the
     * complete raster remains unrotated on the worker hot path. */
    private fun uprightPoint(
        x: Double,
        y: Double,
        width: Int,
        height: Int,
        rotationDegrees: Int
    ): Pair<Float, Float> = when (rotationDegrees) {
        90 -> (height - 1.0 - y).toFloat() to x.toFloat()
        180 -> (width - 1.0 - x).toFloat() to (height - 1.0 - y).toFloat()
        270 -> y.toFloat() to (width - 1.0 - x).toFloat()
        else -> x.toFloat() to y.toFloat()
    }

    private fun cropFor(region: Region, frameWidth: Int, frameHeight: Int): Rect? {
        val bounds = region.bounds
        val size = max(bounds.width(), bounds.height()).toFloat()
        val padding = max(MIN_CROP_PADDING, size * CROP_PADDING_FRACTION + min(size, region.drift * 2f))
        val left = max(0, (bounds.left - padding).toInt())
        val top = max(0, (bounds.top - padding).toInt())
        val right = min(frameWidth, (bounds.right + padding).toInt())
        val bottom = min(frameHeight, (bounds.bottom + padding).toInt())
        return Rect(left, top, right, bottom).takeIf { it.width() >= MIN_CROP_SIZE && it.height() >= MIN_CROP_SIZE }
    }

    private fun cropLuma(source: ByteArray, sourceWidth: Int, crop: Rect): ByteArray {
        val out = ByteArray(crop.width() * crop.height())
        var target = 0
        for (y in crop.top until crop.bottom) {
            source.copyInto(out, target, y * sourceWidth + crop.left, y * sourceWidth + crop.right)
            target += crop.width()
        }
        return out
    }

    /**
     * Copy the camera's native Y plane without rotating it. QR detection is
     * orientation-independent, while rotating 1.2 million pixels in Kotlin
     * (including one ByteBuffer access per pixel) capped capture throughput on
     * real devices. Decimen likewise decodes its raw camera raster.
     */
    private fun copySensorLuma(image: ImageProxy): SensorFrame {
        val width = image.width
        val height = image.height
        val out = ByteArray(width * height)
        val plane = image.planes[0]
        val buffer = plane.buffer.duplicate()
        if (plane.pixelStride == 1) {
            for (y in 0 until height) {
                buffer.position(y * plane.rowStride)
                buffer.get(out, y * width, width)
            }
        } else {
            for (y in 0 until height) {
                val source = y * plane.rowStride
                val target = y * width
                for (x in 0 until width) {
                    out[target + x] = buffer.get(source + x * plane.pixelStride)
                }
            }
        }
        return SensorFrame(out, width, height, image.imageInfo.rotationDegrees)
    }

    private fun boundsOf(quad: DoubleArray): Rect? {
        val xs = doubleArrayOf(quad[0], quad[2], quad[4], quad[6])
        val ys = doubleArrayOf(quad[1], quad[3], quad[5], quad[7])
        val left = xs.minOrNull() ?: return null
        val top = ys.minOrNull() ?: return null
        val right = xs.maxOrNull() ?: return null
        val bottom = ys.maxOrNull() ?: return null
        if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite() ||
            right - left < 8.0 || bottom - top < 8.0) return null
        return Rect(left.toInt(), top.toInt(), right.toInt() + 1, bottom.toInt() + 1)
    }

    private fun sameSymbol(a: Region, b: Region): Boolean {
        val dx = kotlin.math.abs(a.bounds.exactCenterX() - b.bounds.exactCenterX())
        val dy = kotlin.math.abs(a.bounds.exactCenterY() - b.bounds.exactCenterY())
        return dx < max(a.bounds.width(), b.bounds.width()) / 2f &&
            dy < max(a.bounds.height(), b.bounds.height()) / 2f
    }

    override fun onDetachedFromWindow() {
        stop()
        cameraExecutor.shutdownNow()
        super.onDetachedFromWindow()
    }

    private companion object {
        val CAPTURE_SIZE = Size(1280, 960)
        const val MAX_REGIONS = 6
        const val ACQUISITION_SCAN_INTERVAL_MS = 100L
        const val DEGRADED_SCAN_INTERVAL_MS = 250L
        const val HEALTHY_SCAN_INTERVAL_MS = 1_500L
        const val EXPECTED_REGIONS_DECAY_MS = 10_000L
        const val REGION_TTL_MS = 1_500L
        const val MIN_CROP_PADDING = 16f
        const val CROP_PADDING_FRACTION = 0.35f
        const val MIN_CROP_SIZE = 48
        const val MIN_CAPTURE_FPS = 30
    }
}
