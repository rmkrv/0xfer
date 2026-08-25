package com.android.xfer.receiver

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.text.format.Formatter
import android.util.Log
import android.graphics.Rect
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.Button
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.appcompat.app.AppCompatActivity
import com.android.xfer.R
import com.android.xfer.hcc2d.Hcc2dCameraView
import com.android.xfer.hcc2d.NativeHcc2dFrame
import com.android.xfer.hcc2d.NativeHcc2dStats
import com.android.xfer.qr.ParallelCameraView
import com.google.zxing.ResultPoint
import com.android.xfer.transfer.FileInfo
import com.android.xfer.transfer.PacketCodec
import com.android.xfer.transfer.TransferReceiver
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import android.content.ContentValues
import androidx.annotation.RequiresApi

private const val TAG = "OpticalReceiver"
private const val FILE_PROVIDER_AUTHORITY = "com.android.xfer.fileprovider"

private fun ByteArray?.hex(n: Int = 12): String {
    if (this == null) return "null"
    return take(n).joinToString("") { "%02x".format(it.toUByte().toInt()) }
}

class ReceiverActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var debug: TextView
    private lateinit var barcodeView: ParallelCameraView
    private lateinit var hcc2dView: Hcc2dCameraView
    private lateinit var overlay: QROverlayView
    private lateinit var resultPanel: ScrollView
    private lateinit var resultTitle: TextView
    private lateinit var resultMeta: TextView
    private lateinit var resultSha: TextView
    private lateinit var resultPreview: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var activityIndicator: View
    private lateinit var benchmark: TextView
    private lateinit var btnSave: Button
    private lateinit var btnShare: Button
    private lateinit var btnOpen: Button
    private lateinit var btnRescan: Button
    private lateinit var btnSwitchCamera: android.widget.ImageButton

    private var receiver: TransferReceiver? = null
    private var currentSession: Int? = null
    private var currentInfo: FileInfo? = null
    private var outFile: File? = null
    private var done = false
    private var frames = 0
    private var cameraId = 0
    private var receiveStartedAtMs: Long? = null
    private var receivedPayloadBytes = 0L
    private var scanDetail = "full scan: waiting"
    private var hccMode = false
    private var hccCameraFrames = 0L
    private var hccAttempts = 0L
    private var hccDecoded = 0L
    private var hccDecodeNanos = 0L
    private var hccRawBytes = 0L
    private var hccStartedAtMs = 0L
    private var hccLocked = 0L
    private var hccFormatRead = 0L
    private var hccCodewordsRead = 0L
    private var hccPalette4 = 0L
    private var hccPalette8 = 0L
    private var lastDebugAtMs = 0L
    private var qrCameraFrames = 0L
    private var qrDecodeAttempts = 0L
    private var qrDecodedFrames = 0L
    private var qrDecodeNanos = 0L
    private var qrRawBytes = 0L
    private var qrStartedAtMs = 0L


    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_receiver)
        status = findViewById(R.id.receiver_status)
        debug = findViewById(R.id.receiver_debug)
        barcodeView = findViewById(R.id.receiver_preview)
        hcc2dView = findViewById(R.id.receiver_hcc2d_preview)
        overlay = findViewById(R.id.receiver_overlay)
        resultPanel = findViewById(R.id.receiver_result)
        resultTitle = findViewById(R.id.result_title)
        resultMeta = findViewById(R.id.result_meta)
        resultSha = findViewById(R.id.result_sha)
        resultPreview = findViewById(R.id.result_preview)
        progressBar = findViewById(R.id.receiver_progress)
        activityIndicator = findViewById(R.id.receiver_activity_indicator)
        benchmark = findViewById(R.id.receiver_benchmark)
        btnSave = findViewById(R.id.result_save)
        btnShare = findViewById(R.id.result_share)
        btnOpen = findViewById(R.id.result_open)
        btnRescan = findViewById(R.id.result_rescan)
        btnSwitchCamera = findViewById(R.id.receiver_switch_camera)
        hccMode = intent.getBooleanExtra(EXTRA_HCC2D, false)
        benchmark.visibility = View.VISIBLE

        btnSave.setOnClickListener { saveToDownloads() }
        btnShare.setOnClickListener { shareFile() }
        btnOpen.setOnClickListener { openFile() }
        btnRescan.setOnClickListener { resetAndRescan() }
        btnSwitchCamera.setOnClickListener { switchCamera() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startScanning()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 100)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startScanning()
        } else {
            status.text = "Camera access is needed to receive a transfer."
        }
    }

    private fun startScanning() {
        status.text = if (hccMode) "Looking for an HCC2D transfer…" else "Looking for a transfer…"
        progressBar.visibility = View.GONE
        progressBar.progress = 0
        activityIndicator.visibility = View.VISIBLE
        activityIndicator.startAnimation(AnimationUtils.loadAnimation(this, R.anim.receiver_activity_pulse))

        barcodeView.visibility = if (hccMode) View.GONE else View.VISIBLE
        hcc2dView.visibility = if (hccMode) View.VISIBLE else View.GONE
        overlay.visibility = View.VISIBLE
        if (hccMode) {
            hcc2dView.listener = object : Hcc2dCameraView.Listener {
                override fun onFrames(frames: Array<NativeHcc2dFrame>, width: Int, height: Int) {
                    for (frame in frames) onHcc2dFrame(frame, width, height)
                }

                override fun onStats(stats: NativeHcc2dStats) {
                    onHcc2dStats(stats)
                }

                override fun onGeometry(quads: Array<DoubleArray>, width: Int, height: Int, rotationDegrees: Int) {
                    updateHccOverlay(quads, width, height, rotationDegrees)
                }
            }
            hcc2dView.start(
                this,
                if (cameraId == 0) androidx.camera.core.CameraSelector.LENS_FACING_BACK
                else androidx.camera.core.CameraSelector.LENS_FACING_FRONT
            )
            return
        }

        barcodeView.listener = object : ParallelCameraView.Listener {
            override fun onDecoded(symbols: List<ParallelCameraView.DecodedSymbol>, frameWidth: Int, frameHeight: Int) {
                val now = SystemClock.elapsedRealtime()
                if (qrStartedAtMs == 0L) qrStartedAtMs = now
                qrDecodedFrames += symbols.size
                qrRawBytes += symbols.sumOf { it.bytes.size }.toLong()
                overlay.updateSymbols(
                    symbols.map { it.points.toList() },
                    Rect(0, 0, barcodeView.width, barcodeView.height),
                    Rect(0, 0, frameWidth, frameHeight)
                )
                for (symbol in symbols) {
                    processDecoded(symbol.bytes, frameWidth, frameHeight)
                }
                updateQrBenchmark(now)
            }

            override fun onFullScan(
                symbolsReported: Int,
                symbolsDecoded: Int,
                activeRegions: Int,
                width: Int,
                height: Int
            ) {
                scanDetail = "full scan: $symbolsDecoded decoded / $symbolsReported candidates · tracking $activeRegions · ${width}x${height}"
                if (frames == 0) debug.text = scanDetail
            }

            override fun onCameraFrame() {
                val now = SystemClock.elapsedRealtime()
                if (qrStartedAtMs == 0L) qrStartedAtMs = now
                qrCameraFrames++
                updateQrBenchmark(now)
            }

            override fun onNativeDecodeAttempt(durationNanos: Long) {
                qrDecodeAttempts++
                qrDecodeNanos += durationNanos
            }
        }
        barcodeView.start(
            this,
            if (cameraId == 0) androidx.camera.core.CameraSelector.LENS_FACING_BACK
            else androidx.camera.core.CameraSelector.LENS_FACING_FRONT
        )
    }

    private fun switchCamera() {
        cameraId = if (cameraId == 0) 1 else 0
        barcodeView.stop()
        hcc2dView.stop()
        startScanning()
    }

    private fun onHcc2dFrame(frame: NativeHcc2dFrame, width: Int, height: Int) {
        hccRawBytes += frame.payload.size.toLong()
        processDecoded(frame.payload, width, height)
    }

    /** HCC2D uses the same QR-compatible finder geometry. Preserve the native
     * corner ordering, rotate it into PreviewView coordinates, then hand it to
     * the shared animated overlay. */
    private fun updateHccOverlay(quads: Array<DoubleArray>, width: Int, height: Int, rotationDegrees: Int) {
        if (quads.isEmpty() || hcc2dView.width <= 0 || hcc2dView.height <= 0) return
        fun upright(x: Double, y: Double): Pair<Float, Float> = when (rotationDegrees) {
            90 -> (height - 1.0 - y).toFloat() to x.toFloat()
            180 -> (width - 1.0 - x).toFloat() to (height - 1.0 - y).toFloat()
            270 -> y.toFloat() to (width - 1.0 - x).toFloat()
            else -> x.toFloat() to y.toFloat()
        }
        fun points(quad: DoubleArray): List<ResultPoint>? {
            if (quad.size != 8) return null
            fun point(index: Int): ResultPoint {
                val (x, y) = upright(quad[index], quad[index + 1])
                return ResultPoint(x, y)
            }
            return listOf(point(6), point(0), point(2), point(4))
        }
        val uprightWidth = if (rotationDegrees % 180 == 0) width else height
        val uprightHeight = if (rotationDegrees % 180 == 0) height else width
        overlay.updateSymbols(
            quads.mapNotNull(::points),
            Rect(0, 0, hcc2dView.width, hcc2dView.height),
            Rect(0, 0, uprightWidth, uprightHeight)
        )
    }

    private fun onHcc2dStats(stats: NativeHcc2dStats) {
        val now = SystemClock.elapsedRealtime()
        if (hccStartedAtMs == 0L && stats.cameraFrames > 0L) hccStartedAtMs = now
        hccCameraFrames = stats.cameraFrames
        hccAttempts = stats.attempts
        hccDecoded = stats.decoded
        hccDecodeNanos = stats.decodeNanos
        hccLocked = stats.locked
        hccFormatRead = stats.formatRead
        hccCodewordsRead = stats.codewordsRead
        hccPalette4 = stats.palette4
        hccPalette8 = stats.palette8
        updateHcc2dBenchmark(now, stats)
    }

    private fun updateHcc2dBenchmark(now: Long, stats: NativeHcc2dStats) {
        val elapsed = ((now - hccStartedAtMs).coerceAtLeast(1L)) / 1000.0
        val fps = hccCameraFrames / elapsed
        val success = hccDecoded * 100.0 / hccAttempts.coerceAtLeast(1L)
        val decodeMs = hccDecodeNanos / hccAttempts.coerceAtLeast(1L) / 1_000_000.0

        val scanMs = stats.acquisitionNanos / stats.acquisitionScans.coerceAtLeast(1L) / 1_000_000.0
        val rawRate = hccRawBytes / 1024.0 / elapsed
        val usefulRate = receivedPayloadBytes / 1024.0 / elapsed
        val detected = if (stats.detected) "lock" else "search"
        benchmark.text = String.format(
            Locale.US,
            "HCC2D%d v%d · %s · camera %.1f fps\nattempted %d · decoded %d (%.1f%%) · decode %.1f ms · scan %.1f ms\nlock %d · format %d · codewords %d · palette 4/8 %d/%d\nacq f/c/s/h/g %d/%d/%d/%d/%d · black %d\nraw %.1f kB/s · useful %.1f kB/s · %d B/symbol",
            stats.colors, stats.version, detected, fps, hccAttempts, hccDecoded, success,
            decodeMs, scanMs, hccLocked, hccFormatRead, hccCodewordsRead, hccPalette4, hccPalette8,
            stats.rawFinders, stats.clusteredFinders, stats.tripleSeeds, stats.hypotheses,
            stats.acceptedGeometries, stats.blackThreshold,
            rawRate, usefulRate, stats.payloadCapacity
        )
    }

    private fun updateQrBenchmark(now: Long) {
        if (qrStartedAtMs == 0L) return
        val elapsed = ((now - qrStartedAtMs).coerceAtLeast(1L)) / 1000.0
        val cameraFps = qrCameraFrames / elapsed
        val success = qrDecodedFrames * 100.0 / qrDecodeAttempts.coerceAtLeast(1L)
        val decodeMs = qrDecodeNanos / qrDecodeAttempts.coerceAtLeast(1L) / 1_000_000.0
        val rawRate = qrRawBytes / 1024.0 / elapsed
        val usefulRate = receivedPayloadBytes / 1024.0 / elapsed
        val bytesPerSymbol = qrRawBytes.toDouble() / qrDecodedFrames.coerceAtLeast(1L)
        benchmark.text = String.format(
            Locale.US,
            "QR baseline · camera %.1f fps\nattempted %d · decoded %d (%.1f%%) · decode %.1f ms\nraw %.1f kB/s · useful %.1f kB/s · %.0f B/symbol",
            cameraFps, qrDecodeAttempts, qrDecodedFrames, success, decodeMs, rawRate, usefulRate, bytesPerSymbol
        )
    }

    private fun processDecoded(
        bytes: ByteArray,
        frameWidth: Int,
        frameHeight: Int
    ) {
        if (done) return
        frames++
        val now = SystemClock.elapsedRealtime()
        // Hex formatting and TextView layout are useful diagnostics, but must
        // not compete with a high-rate HCC2D packet stream on the UI thread.
        if (now - lastDebugAtMs >= 250L) {
            lastDebugAtMs = now
            val isDecimen = PacketCodec.isDecimenFrame(bytes)
            val kind = if (isDecimen) "Decimen packet" else "NOT OURS"
            debug.text = "$scanDetail\nsymbols frame#$frames | ${bytes.size}B | $kind | ${frameWidth}x${frameHeight} | hex=${bytes.hex()}"
        }
        if (PacketCodec.isDecimenFrame(bytes)) tryPacket(bytes)
    }

    private fun tryPacket(bytes: ByteArray): Boolean {
        return try {
            val packet = PacketCodec.decode(bytes)
            val rcv = receiver?.takeIf { it.belongsTo(packet) } ?: TransferReceiver(packet).also {
                receiver = it
                currentSession = packet.sessionId
                currentInfo = null
                receiveStartedAtMs = null
                receivedPayloadBytes = 0L
                progressBar.visibility = View.VISIBLE
                progressBar.progress = 0
                status.text = if (hccMode) "HCC2D transfer found · getting ready…" else "Transfer found · getting ready…"
            }
            val accepted = rcv.receive(packet)
            if (accepted) {
                val started = receiveStartedAtMs ?: SystemClock.elapsedRealtime().also {
                    receiveStartedAtMs = it
                }
                receivedPayloadBytes += rcv.blockLength.toLong()
                val elapsedSeconds = (SystemClock.elapsedRealtime() - started).coerceAtLeast(1L) / 1000.0
                val rateKbPerSecond = receivedPayloadBytes / 1024.0 / elapsedSeconds
                val rate = String.format(Locale.US, "%.1f kB/s", rateKbPerSecond)
                val pct = (rcv.progress() * 100).toInt()
                progressBar.progress = pct
                status.text = if (hccMode) "Receiving HCC2D · $pct% · $rate" else "Receiving · $pct% · $rate"
                if (!hccMode) updateQrBenchmark(SystemClock.elapsedRealtime())
            }
            if (rcv.isComplete()) finalize(rcv)
            true
        } catch (e: Exception) {
            Log.d(TAG, "packet rejected: ${e.message}")
            false
        }
    }

    private fun finalize(rcv: TransferReceiver) {
        done = true
        val dir = File(getExternalFilesDir(null), "received")
        dir.mkdirs()
        try {
            val unpacked = rcv.unpack()
            val info = unpacked.info
            currentInfo = info
            val file = File(dir, info.fileName)
            file.writeBytes(unpacked.bytes)
            outFile = file
            barcodeView.stop()
            hcc2dView.stop()
            activityIndicator.clearAnimation()
            activityIndicator.visibility = View.GONE
            progressBar.visibility = View.GONE
            showResult(file, info)
        } catch (e: Exception) {
            Log.e(TAG, "save failed", e)
            // A corrupted-but-QR-valid frame cannot be identified until the
            // full container FNV is checked. Discard that solve and continue
            // with the repeating carousel instead of leaving a dead session.
            receiver = null
            currentSession = null
            currentInfo = null
            receiveStartedAtMs = null
            receivedPayloadBytes = 0L
            status.text = "Checksum mismatch — re-locking on the next carousel sweep…"
            done = false
        }
    }

    private fun showResult(file: File, info: FileInfo) {
        val actualSha = sha256Hex(file)
        val expectedSha = info.sha256.joinToString("") { "%02x".format(it) }
        val verified = actualSha == expectedSha

        resultTitle.text = if (verified) "Transfer complete" else "Transfer needs attention"
        resultMeta.text = "${info.fileName}\n${Formatter.formatFileSize(this, file.length())}"
        resultSha.text = if (verified) {
            "Verified securely. This file matches what was sent."
        } else {
            "This file could not be verified. Please receive it again."
        }

        // Message preview for small text files.
        val isText = info.fileName.endsWith(".txt", ignoreCase = true) || info.fileName.endsWith(".json", ignoreCase = true)
        if (isText && file.length() <= 64 * 1024) {
            resultPreview.visibility = android.view.View.VISIBLE
            resultPreview.text = file.readText(Charsets.UTF_8)
        } else {
            resultPreview.visibility = android.view.View.GONE
        }

        status.text = if (verified) "Transfer complete" else "Transfer needs attention"
        resultPanel.visibility = android.view.View.VISIBLE
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveToDownloads() {
        val file = outFile ?: return
        val name = currentInfo?.fileName ?: file.name
        val uri = copyToDownloads(file, name)
        if (uri != null) {
            Toast.makeText(this, "Saved to Downloads: $name", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Could not save to Downloads", Toast.LENGTH_LONG).show()
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun copyToDownloads(file: File, name: String): Uri? {
        val resolver = contentResolver
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Downloads.getContentUri("external")
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: return null
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            return uri
        } catch (e: Exception) {
            Log.e(TAG, "download copy failed", e)
            return null
        }
    }

    private fun fileUri(): Uri? {
        val file = outFile ?: run {
            Toast.makeText(this, "No received file is available yet.", Toast.LENGTH_SHORT).show()
            return null
        }
        return try {
            FileProvider.getUriForFile(this, FILE_PROVIDER_AUTHORITY, file)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "received file is outside the FileProvider path", e)
            Toast.makeText(this, "This file cannot be shared yet.", Toast.LENGTH_LONG).show()
            null
        }
    }

    private fun openFile() {
        val uri = fileUri() ?: return
        val type = contentResolver.getType(uri) ?: "application/octet-stream"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, type)
            clipData = ClipData.newRawUri("Received file", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (intent.resolveActivity(packageManager) == null) {
            Toast.makeText(this, "No app can open this file type.", Toast.LENGTH_LONG).show()
            return
        }
        startActivity(Intent.createChooser(intent, "Open with"))
    }

    private fun shareFile() {
        val uri = fileUri() ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = contentResolver.getType(uri) ?: "*/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("Received file", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share via"))
    }

    private fun resetAndRescan() {
        receiver = null
        currentSession = null
        currentInfo = null
        outFile = null
        done = false
        frames = 0
        receiveStartedAtMs = null
        receivedPayloadBytes = 0L
        hccAttempts = 0L
        hccCameraFrames = 0L
        hccDecoded = 0L
        hccDecodeNanos = 0L
        hccRawBytes = 0L
        hccStartedAtMs = 0L
        hccLocked = 0L
        hccFormatRead = 0L
        hccCodewordsRead = 0L
        hccPalette4 = 0L
        hccPalette8 = 0L
        lastDebugAtMs = 0L
        qrCameraFrames = 0L
        qrDecodeAttempts = 0L
        qrDecodedFrames = 0L
        qrDecodeNanos = 0L
        qrRawBytes = 0L
        qrStartedAtMs = 0L
        resultPanel.visibility = android.view.View.GONE
        status.text = "Looking for a transfer…"
        startScanning()
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        return digest.joinToString("") { "%02x".format(it) }
    }

    override fun onResume() {
        super.onResume()
        if (!done && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startScanning()
        }
    }

    override fun onPause() {
        super.onPause()
        barcodeView.stop()
        hcc2dView.stop()
    }

    companion object {
        const val EXTRA_HCC2D = "receive_hcc2d"
    }
}
