package com.android.qttransfer.hcc2d

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Native HCC2D capture path. Camera Y/U/V buffers are consumed directly in
 * C++; failed/duplicate frames do not create a Kotlin object or JNI byte array. */
class Hcc2dCameraView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {
    interface Listener {
        fun onFrames(frames: Array<NativeHcc2dFrame>, width: Int, height: Int)
        fun onStats(stats: NativeHcc2dStats)
        fun onGeometry(quads: Array<DoubleArray>, width: Int, height: Int, rotationDegrees: Int)
    }

    var listener: Listener? = null
    private val previewView = PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    }
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val generation = AtomicInteger(0)
    private val statsPosted = AtomicBoolean(false)
    private var provider: ProcessCameraProvider? = null
    @Volatile private var running = false
    // This field is read and released only on [cameraExecutor].
    private var nativeDecoder = 0L
    private var lastStatsAtMs = 0L

    init {
        addView(previewView, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    fun start(owner: LifecycleOwner, lensFacing: Int) {
        val current = generation.incrementAndGet()
        cameraExecutor.execute {
            releaseDecoderOnCameraThread()
            nativeDecoder = NativeHcc2dBridge.createDecoder()
            lastStatsAtMs = 0L
        }
        ProcessCameraProvider.getInstance(context).addListener({
            if (current != generation.get()) return@addListener
            val nextProvider = try { ProcessCameraProvider.getInstance(context).get() } catch (_: Exception) { return@addListener }
            provider = nextProvider
            bind(nextProvider, owner, lensFacing, current)
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        generation.incrementAndGet()
        running = false
        provider?.unbindAll()
        cameraExecutor.execute { releaseDecoderOnCameraThread() }
    }

    private fun bind(provider: ProcessCameraProvider, owner: LifecycleOwner, lensFacing: Int, current: Int) {
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        val rotation = display?.rotation ?: Surface.ROTATION_0
        val fpsRange = bestFpsRange(provider, selector)
        val focusMode = continuousFocusMode(provider, selector)
        val resolution = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy(CAPTURE_SIZE, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
            .build()
        val previewBuilder = Preview.Builder().setResolutionSelector(resolution).setTargetRotation(rotation)
        fpsRange?.let { Camera2Interop.Extender(previewBuilder).setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
        focusMode?.let { Camera2Interop.Extender(previewBuilder).setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, it) }
        val preview = previewBuilder.build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val analysisBuilder = ImageAnalysis.Builder().setResolutionSelector(resolution)
            .setTargetRotation(rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
        fpsRange?.let { Camera2Interop.Extender(analysisBuilder).setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
        focusMode?.let { Camera2Interop.Extender(analysisBuilder).setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, it) }
        val analysis = analysisBuilder.build()
        analysis.setAnalyzer(cameraExecutor) { image ->
            try {
                if (!running || current != generation.get() || nativeDecoder == 0L) return@setAnalyzer
                val y = image.planes[0]
                val u = image.planes[1]
                val v = image.planes[2]
                val frameWidth = image.width
                val frameHeight = image.height
                val rotationDegrees = image.imageInfo.rotationDegrees
                val result = NativeHcc2dBridge.decodeYuv(
                    nativeDecoder,
                    y.buffer, y.buffer.position(), y.rowStride, y.pixelStride,
                    u.buffer, u.buffer.position(), u.rowStride, u.pixelStride,
                    v.buffer, v.buffer.position(), v.rowStride, v.pixelStride,
                    frameWidth, frameHeight
                )
                if (!result.isNullOrEmpty() && running && current == generation.get()) {
                    post { if (running && current == generation.get()) listener?.onFrames(result, frameWidth, frameHeight) }
                }
                val now = SystemClock.elapsedRealtime()
                if (now - lastStatsAtMs >= STATS_INTERVAL_MS) {
                    lastStatsAtMs = now
                    val stats = NativeHcc2dStats.fromNative(NativeHcc2dBridge.readStats(nativeDecoder))
                    val quads = NativeHcc2dBridge.readQuads(nativeDecoder)
                    if (stats != null && statsPosted.compareAndSet(false, true)) {
                        post {
                            statsPosted.set(false)
                            if (running && current == generation.get()) {
                                listener?.onStats(stats)
                                if (!quads.isNullOrEmpty()) listener?.onGeometry(quads, frameWidth, frameHeight, rotationDegrees)
                            }
                        }
                    }
                }
            } finally {
                image.close()
            }
        }
        try {
            provider.unbindAll()
            provider.bindToLifecycle(owner, selector, preview, analysis)
            running = true
        } catch (_: Exception) {
            analysis.clearAnalyzer()
        }
    }

    private fun releaseDecoderOnCameraThread() {
        if (nativeDecoder != 0L) {
            NativeHcc2dBridge.releaseDecoder(nativeDecoder)
            nativeDecoder = 0L
        }
    }

    override fun onDetachedFromWindow() {
        stop()
        // Keep queued closes/releases ordered after the active camera frame.
        cameraExecutor.shutdown()
        super.onDetachedFromWindow()
    }

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

    private fun continuousFocusMode(provider: ProcessCameraProvider, selector: CameraSelector): Int? = try {
        val modes = selector.filter(provider.availableCameraInfos)
            .flatMap { info ->
                Camera2CameraInfo.from(info)
                    .getCameraCharacteristic(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                    ?.asList()
                    .orEmpty()
            }
        when {
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE in modes -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO in modes -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    private companion object {
        val CAPTURE_SIZE = Size(1920, 1440)
        const val MIN_CAPTURE_FPS = 30
        const val STATS_INTERVAL_MS = 250L
    }
}
