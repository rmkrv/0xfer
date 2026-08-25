package com.android.xfer.sender

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import com.android.xfer.R
import com.android.xfer.hcc2d.Hcc2dDisplayView
import com.android.xfer.hcc2d.NativeHcc2dBridge
import com.android.xfer.qr.QrGenerator
import com.android.xfer.qr.QrDisplayView
import com.android.xfer.transfer.FileInfo
import com.android.xfer.transfer.TransferConfig
import com.android.xfer.transfer.TransferSender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SenderActivity : AppCompatActivity() {
    private lateinit var imageView: QrDisplayView
    private lateinit var status: TextView
    private lateinit var editText: EditText
    private lateinit var pickButton: Button
    private lateinit var chooseTextButton: Button
    private lateinit var startButton: Button
    private lateinit var versionSpinner: Spinner
    private lateinit var fpsSpinner: Spinner
    private lateinit var gridSpinner: Spinner
    private lateinit var codeTypeSpinner: Spinner
    private lateinit var hcc2dView: Hcc2dDisplayView
    private lateinit var controls: View
    private lateinit var textContainer: View
    private lateinit var qrInformation: View
    private lateinit var navigation: View
    private lateinit var statusPanel: View
    private lateinit var bottomHint: View

    // Low-density codes decode far more reliably through a phone camera. Packet
    // payload is chosen from the selected version instead of wasting a v15/v20
    // symbol on the old fixed 120-byte chunk.
    private val versions = listOf(10, 15, 20, 25, 40)

    // Matches Decimen's fast options. 55 FPS is useful on 60 Hz screens: its
    // phase drifts against refresh rather than tearing the same frame edge.
    private val fpsOptions = listOf(1.0, 2.0, 5.0, 10.0, 15.0, 20.0, 24.0, 30.0, 55.0, 60.0)
    private val gridOptions = listOf(1, 2, 3, 4, 6)
    private val codeTypes = listOf("QR — baseline(Decimen)", "HCC2D4 — experimental(Hcc2d)", "HCC2D8 — experimental(Hcc2d)")

    private var sourceFile: File? = null
    private var sourceName: String? = null
    private var textMode = false

    private var playing = false
    private var job: Job? = null
    private var qrFullscreen = false

    private val frameIntervalMs: Long
        get() {
            val fps = fpsOptions.getOrElse(fpsSpinner.selectedItemPosition) { 2.0 }
            return (1000.0 / fps).toLong()
        }

    private val selectedVersion: Int
        get() = versions.getOrElse(versionSpinner.selectedItemPosition) { TransferConfig.QR_VERSION }

    private val selectedGrid: Int
        get() = gridOptions.getOrElse(gridSpinner.selectedItemPosition) { 1 }

    private val selectedCodeType: Int
        get() = codeTypeSpinner.selectedItemPosition.coerceIn(0, 2)

    /** Byte-mode capacity at EC level L, less our 33-byte packet header and margin. */
    private val selectedChunkSize: Int
        get() = when (selectedVersion) {
            10 -> 220
            15 -> 480
            20 -> 780
            25 -> 1_180
            40 -> 2_850
            else -> TransferConfig.CHUNK_SIZE
        }

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val bytes = contentResolver.openInputStream(uri)!!.readBytes()

            var name = "file.bin"
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst()) {
                    name = cursor.getString(nameIndex)
                }
            }

            prepareSource(name, bytes)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not read file: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_sender)

        imageView = findViewById(R.id.sender_image)
        status = findViewById(R.id.sender_status)
        editText = findViewById(R.id.sender_text)
        pickButton = findViewById(R.id.sender_pick)
        chooseTextButton = findViewById(R.id.sender_choose_text)
        startButton = findViewById(R.id.sender_start)
        versionSpinner = findViewById(R.id.sender_version)
        fpsSpinner = findViewById(R.id.sender_fps)
        gridSpinner = findViewById(R.id.sender_grid)
        codeTypeSpinner = findViewById(R.id.sender_code_type)
        hcc2dView = findViewById(R.id.sender_hcc2d_image)
        controls = findViewById(R.id.sender_choice_panel)
        textContainer = findViewById(R.id.sender_text_container)
        qrInformation = findViewById(R.id.sender_qr_information)
        navigation = findViewById(R.id.sender_navigation)
        statusPanel = findViewById(R.id.sender_status_panel)
        bottomHint = findViewById(R.id.sender_bottom_hint)

        versionSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            versions.map { "QR ${QrGenerator.moduleSize(it)}x${QrGenerator.moduleSize(it)} (v$it)" }
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        fpsSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            fpsOptions.map { "$it FPS" }
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        gridSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            gridOptions.map { count ->
                when (count) {
                    1 -> "1 code — largest modules"
                    2 -> "2-code grid — 2× stream frames"
                    3 -> "3-code grid — 3× stream frames"
                    4 -> "4-code grid — 4× stream frames"
                    else -> "2×3 grid — 6× stream frames"
                }
            }
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        codeTypeSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            codeTypes
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        versionSpinner.setSelection(1) // v15: good density/performance balance.
        fpsSpinner.setSelection(8) // 55 FPS: high throughput without fixed 60 Hz phase tearing.

        findViewById<View>(R.id.sender_back).setOnClickListener { finish() }
        pickButton.setOnClickListener {
            textMode = false
            textContainer.visibility = View.GONE
            pickFile.launch(arrayOf("*/*"))
        }
        chooseTextButton.setOnClickListener { chooseText() }
        editText.addTextChangedListener {
            if (textMode) {
                sourceFile = null
                sourceName = null
                if (it.isNullOrBlank()) {
                    status.text = "Write a short message to continue."
                } else {
                    status.text = "Ready to send your message."
                }
            }
            updateStartButton()
        }
        startButton.setOnClickListener { if (playing) stop() else start() }
        imageView.setOnClickListener { toggleQrFullscreen() }
        hcc2dView.setOnClickListener { toggleQrFullscreen() }
    }

    private fun prepareSource(name: String, bytes: ByteArray) {
        val f = File(cacheDir, "send_${System.nanoTime()}.bin")
        f.writeBytes(bytes)
        sourceFile = f
        sourceName = name
        textMode = false
        textContainer.visibility = View.GONE
        status.text = "Ready to send $name · ${formatBytes(bytes.size.toLong())}"
        updateStartButton()
    }

    private fun chooseText() {
        textMode = true
        sourceFile = null
        sourceName = null
        textContainer.visibility = View.VISIBLE
        status.text = if (editText.text.isNullOrBlank()) {
            "Write a short message to continue."
        } else {
            "Ready to send your message."
        }
        updateStartButton()
    }

    private fun updateStartButton() {
        startButton.isEnabled = sourceFile != null || (textMode && editText.text.isNotBlank())
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> String.format("%.1f KB", bytes / 1024.0)
        else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    }

    private fun start() {
        var name = sourceName
        val file = sourceFile ?: run {
            val t = editText.text.toString()
            if (t.isEmpty()) {
                Toast.makeText(this, "Type a message or pick a file", Toast.LENGTH_SHORT).show()
                return
            }
            val f = File(cacheDir, "send_text.bin")
            f.writeBytes(t.toByteArray(Charsets.UTF_8))
            sourceFile = f
            name = "message.txt"
            sourceName = name
            f
        }

        if (selectedCodeType == 0) {
            startQr(file, name)
        } else {
            startHcc2d(file, name, if (selectedCodeType == 1) 4 else 8)
        }
    }

    /** The existing QR baseline path. Its packets, matrix generator and
     * staggered-grid update behaviour intentionally stay unchanged. */
    private fun startQr(file: File, name: String?) {
        val version = selectedVersion
        val chunkSize = selectedChunkSize
        val gridCount = selectedGrid
        val sender = TransferSender(
            file = file,
            fileName = name,
            blockLength = chunkSize,
            mediaType = if (name == "message.txt") com.android.xfer.transfer.DecimenContainer.textType() else "application/octet-stream"
        )
        val info: FileInfo = sender.info

        playing = true
        controls.visibility = View.GONE
        qrInformation.visibility = View.VISIBLE
        startButton.text = "Stop transfer"
        status.text = "Preparing ${info.fileName}…"
        imageView.visibility = View.VISIBLE
        hcc2dView.visibility = View.GONE

        job = lifecycleScope.launch(Dispatchers.Default) {
            val matrices = MutableList(gridCount) {
                QrGenerator.generateMatrix(sender.nextPacket(), version)
            }
            withContext(Dispatchers.Main) { imageView.setMatrices(matrices) }
            var cellCursor = 0
            while (isActive && playing) {
                val currentInterval = frameIntervalMs
                // Stagger cell flips. One camera exposure can straddle at
                // most one QR update; the other tiles remain stable.
                delay((currentInterval / gridCount).coerceAtLeast(1L))
                matrices[cellCursor] = QrGenerator.generateMatrix(sender.nextPacket(), version)
                withContext(Dispatchers.Main) {
                    imageView.setMatrices(matrices)
                    status.text = "Sending ${info.fileName}"
                }
                cellCursor = (cellCursor + 1) % gridCount
            }
        }
    }

    /** HCC2D is isolated from the QR branch: same fountain packets, but the
     * official native HCC2D 0.9.0 encoder produces a colour module matrix. */
    private fun startHcc2d(file: File, name: String?, colors: Int) {
        val version = selectedVersion
        val intervalMs = frameIntervalMs
        val gridCount = selectedGrid
        val blockLength = hcc2dBlockLength(colors, version)
        val sender = TransferSender(
            file = file,
            fileName = name,
            blockLength = blockLength,
            mediaType = if (name == "message.txt") com.android.xfer.transfer.DecimenContainer.textType() else "application/octet-stream"
        )
        val info = sender.info
        playing = true
        controls.visibility = View.GONE
        qrInformation.visibility = View.VISIBLE
        startButton.text = "Stop transfer"
        imageView.visibility = View.GONE
        hcc2dView.visibility = View.VISIBLE
        status.text = "Preparing HCC2D$colors v$version · ${info.fileName}…"

        job = lifecycleScope.launch(Dispatchers.Default) {
            val symbols = ArrayList<com.android.xfer.hcc2d.NativeHcc2dEncoded>(gridCount)
            repeat(gridCount) {
                val symbol = NativeHcc2dBridge.encode(sender.nextPacket(), colors, version)
                    ?: run {
                        withContext(Dispatchers.Main) {
                            status.text = "HCC2D$colors v$version cannot fit this frame"
                            playing = false
                        }
                        return@launch
                    }
                symbols += symbol
            }
            var cellCursor = 0
            val startedAt = SystemClock.elapsedRealtime()
            var shown = 0L
            var lastStatusAt = 0L
            while (isActive && playing) {
                // Start reference encoding while the current grid is visible.
                // The view snapshots the list at draw time, then only this one
                // cell changes; the other cells stay optically stable.
                val next = async { NativeHcc2dBridge.encode(sender.nextPacket(), colors, version) }
                withContext(Dispatchers.Main) {
                    hcc2dView.present(symbols)
                    shown++
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastStatusAt >= 250L) {
                        lastStatusAt = now
                        val elapsed = ((now - startedAt).coerceAtLeast(1L)) / 1000.0
                        val actualRate = symbols[0].payloadCapacity * shown / elapsed / 1024.0
                        status.text = "HCC2D$colors v$version · ${symbols[0].payloadCapacity} B/symbol · $gridCount-code grid · displayed ${"%.1f".format(actualRate)} kB/s"
                    }
                }
                delay(intervalMs.coerceAtLeast(1L))
                val nextSymbol = next.await()
                if (nextSymbol == null) {
                    withContext(Dispatchers.Main) {
                        status.text = "HCC2D$colors v$version cannot fit this frame"
                        playing = false
                    }
                    return@launch
                }
                symbols[cellCursor] = nextSymbol
                cellCursor = (cellCursor + 1) % gridCount
            }
        }
    }

    private fun hcc2dBlockLength(colors: Int, version: Int): Int {
        // HCC2D uses Q-level EC: colour-module errors are materially more
        // frequent than binary QR errors on a phone display. Query the native
        // reference layout rather than duplicating a version table in Kotlin.
        // The packet carries Decimen's 22-byte header inside HCC2D's payload.
        return (NativeHcc2dBridge.payloadCapacity(colors, version) - 22).coerceAtLeast(1)
    }

    private fun stop() {
        playing = false
        job?.cancel()
        controls.visibility = View.VISIBLE
        qrInformation.visibility = View.GONE
        startButton.text = "Show transfer code"
        status.text = "Transfer stopped. You can try again."
        updateStartButton()
    }

    private fun toggleQrFullscreen() {
        qrFullscreen = !qrFullscreen
        val chromeVisibility = if (qrFullscreen) View.GONE else View.VISIBLE
        navigation.visibility = chromeVisibility
        statusPanel.visibility = chromeVisibility
        startButton.visibility = chromeVisibility
        bottomHint.visibility = chromeVisibility
        controls.visibility = if (qrFullscreen || playing) View.GONE else View.VISIBLE
        qrInformation.visibility = if (!qrFullscreen && playing) View.VISIBLE else View.GONE
        // The enlarged code is the important part; immersive flags merely
        // recover the status/navigation-bar pixels where Android permits it.
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (qrFullscreen) {
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        } else 0
    }

    override fun onDestroy() {
        super.onDestroy()
        playing = false
        job?.cancel()
    }

    override fun onBackPressed() {
        if (qrFullscreen) {
            toggleQrFullscreen()
        } else {
            super.onBackPressed()
        }
    }

}
