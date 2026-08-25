package com.android.qttransfer.transfer

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Decimen v3 frame codec: 22-byte, little-endian header followed by one block. */
object PacketCodec {
    fun encode(packet: FountainPacket): ByteArray {
        require(packet.sessionId in 0..0xFFFF)
        require(packet.sourceBlockCount in 1..TransferConfig.MAX_SOURCE_BLOCKS)
        require(packet.blockLength in 1..0xFFFF)
        require(packet.totalLength > 0)
        require(packet.payload.size == packet.blockLength)
        return ByteBuffer.allocate(TransferConfig.HEADER_SIZE + packet.blockLength).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(TransferConfig.MAGIC_0); put(TransferConfig.MAGIC_1); put(TransferConfig.WIRE_VERSION.toByte()); put(packet.flags.toByte())
            putShort(packet.sessionId.toShort()); putInt(packet.sequence); putShort(packet.sourceBlockCount.toShort()); putShort(packet.blockLength.toShort())
            putInt(packet.totalLength); putInt(packet.payloadFnv); put(packet.payload)
        }.array()
    }

    fun decode(data: ByteArray): FountainPacket {
        require(data.size > TransferConfig.HEADER_SIZE) { "Frame is truncated" }
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        require(buffer.get() == TransferConfig.MAGIC_0 && buffer.get() == TransferConfig.MAGIC_1) { "Not a Decimen frame" }
        val version = buffer.get().toInt() and 0xFF
        require(version == TransferConfig.WIRE_VERSION) { "Unsupported Decimen wire version: $version" }
        val flags = buffer.get().toInt() and 0xFF
        require((flags and 0x0F) == 0) { "Unsupported Decimen frame flags" }
        val sessionId = buffer.short.toInt() and 0xFFFF
        val sequence = buffer.int
        val sourceBlockCount = buffer.short.toInt() and 0xFFFF
        val blockLength = buffer.short.toInt() and 0xFFFF
        val totalLength = buffer.int
        val payloadFnv = buffer.int
        require(sourceBlockCount > 0 && blockLength > 0 && totalLength > 0) { "Malformed Decimen frame" }
        require(data.size == TransferConfig.HEADER_SIZE + blockLength) { "Incorrect Decimen frame length" }
        return FountainPacket(sessionId, sequence, sourceBlockCount, blockLength, totalLength, payloadFnv, flags, data.copyOfRange(TransferConfig.HEADER_SIZE, data.size))
    }

    fun isDecimenFrame(data: ByteArray): Boolean = data.size >= 2 && data[0] == TransferConfig.MAGIC_0 && data[1] == TransferConfig.MAGIC_1
}
