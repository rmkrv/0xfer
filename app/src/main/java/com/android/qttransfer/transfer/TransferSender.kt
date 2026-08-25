package com.android.qttransfer.transfer

import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

class TransferSender(
    file: File,
    fileName: String? = null,
    blockLength: Int = TransferConfig.CHUNK_SIZE,
    mediaType: String = "application/octet-stream"
) {
    val sessionId = SecureRandom().nextInt(0xFFFF) + 1
    val info: FileInfo
    private val encoder: FountainEncoder

    init {
        val raw = file.readBytes()
        val name = fileName ?: file.name
        val container = DecimenContainer.pack(name, mediaType, raw)
        val sourceBlocks = (container.size + blockLength - 1) / blockLength
        require(sourceBlocks <= TransferConfig.MAX_SOURCE_BLOCKS) { "File is too large for this QR frame size" }
        info = FileInfo(name, raw.size.toLong(), blockLength, sourceBlocks, MessageDigest.getInstance("SHA-256").digest(raw), mediaType)
        encoder = FountainEncoder(container, blockLength, sessionId)
    }

    fun nextPacket(): ByteArray = PacketCodec.encode(encoder.nextPacket())
}
