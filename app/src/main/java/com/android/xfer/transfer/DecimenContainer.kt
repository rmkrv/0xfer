package com.android.xfer.transfer

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** DCF2 file container used inside Decimen fountain frames. */
object DecimenContainer {
    private const val HEADER_LENGTH = 49
    private val magic = byteArrayOf(0x44, 0x43, 0x46, 0x32) // DCF2
    private const val SNIPPET_TYPE = "application/vnd.decimen.snippet"

    data class Unpacked(val info: FileInfo, val bytes: ByteArray)

    fun pack(name: String, type: String, bytes: ByteArray): ByteArray {
        require(bytes.isNotEmpty()) { "Cannot transfer an empty file" }
        val nameBytes = safeName(name).toByteArray(Charsets.UTF_8)
        val typeBytes = type.toByteArray(Charsets.UTF_8)
        require(nameBytes.size <= 0xFFFF && typeBytes.size <= 0xFFFF)
        val out = ByteArray(HEADER_LENGTH + nameBytes.size + typeBytes.size + bytes.size)
        out[0] = magic[0]; out[1] = magic[1]; out[2] = magic[2]; out[3] = magic[3]
        out[4] = 0 // uncompressed. DCF2 receivers can still receive compressed Decimen streams.
        putU16(out, 5, nameBytes.size)
        putU16(out, 7, typeBytes.size)
        putU32(out, 9, bytes.size)
        putU32(out, 13, bytes.size)
        MessageDigest.getInstance("SHA-256").digest(bytes).copyInto(out, 17)
        nameBytes.copyInto(out, HEADER_LENGTH)
        typeBytes.copyInto(out, HEADER_LENGTH + nameBytes.size)
        bytes.copyInto(out, HEADER_LENGTH + nameBytes.size + typeBytes.size)
        return out
    }

    fun unpack(container: ByteArray): Unpacked {
        require(container.size >= HEADER_LENGTH && magic.indices.all { container[it] == magic[it] }) { "Bad DCF2 container" }
        val compressed = container[4].toInt() and 0xFF
        require(compressed in 0..1) { "Unsupported DCF2 compression" }
        val nameLength = getU16(container, 5)
        val typeLength = getU16(container, 7)
        val fileLength = getU32(container, 9)
        val transmittedLength = getU32(container, 13)
        val offset = HEADER_LENGTH + nameLength + typeLength
        require(fileLength > 0 && transmittedLength > 0 && offset + transmittedLength == container.size) { "Malformed DCF2 lengths" }
        val name = safeName(container.copyOfRange(HEADER_LENGTH, HEADER_LENGTH + nameLength).toString(Charsets.UTF_8))
        val type = container.copyOfRange(HEADER_LENGTH + nameLength, offset).toString(Charsets.UTF_8)
        val transmitted = container.copyOfRange(offset, container.size)
        val bytes = if (compressed == 0) transmitted else GZIPInputStream(ByteArrayInputStream(transmitted)).use { it.readBytes() }
        require(bytes.size == fileLength) { "DCF2 file length mismatch" }
        val sha256 = container.copyOfRange(17, 49)
        require(MessageDigest.getInstance("SHA-256").digest(bytes).contentEquals(sha256)) { "DCF2 checksum mismatch" }
        return Unpacked(
            FileInfo(name, bytes.size.toLong(), 0, 0, sha256, type.ifBlank { "application/octet-stream" }),
            bytes
        )
    }

    fun textType(): String = SNIPPET_TYPE

    private fun safeName(value: String): String = value.substringAfterLast('/').substringAfterLast('\\')
        .filter { it.code !in 0..31 && it.code != 127 }.trim().let { if (it.isBlank() || it == "." || it == "..") "transfer.bin" else it }
    private fun putU16(out: ByteArray, offset: Int, value: Int) { out[offset] = value.toByte(); out[offset + 1] = (value ushr 8).toByte() }
    private fun putU32(out: ByteArray, offset: Int, value: Int) { for (i in 0..3) out[offset + i] = (value ushr (8 * i)).toByte() }
    private fun getU16(data: ByteArray, offset: Int): Int = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
    private fun getU32(data: ByteArray, offset: Int): Int = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8) or ((data[offset + 2].toInt() and 0xFF) shl 16) or ((data[offset + 3].toInt() and 0xFF) shl 24)
}
