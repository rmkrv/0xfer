package com.android.qttransfer

import com.android.qttransfer.transfer.FileInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Out-of-band metadata frame. Fountain packets only carry sessionId /
 * chunkCount / chunkSize; the receiver also needs the file name, size and
 * SHA-256 to reassemble and verify. The sender broadcasts this frame
 * (encoded like any other QR, via QrGenerator) so a late-joining receiver can
 * lock on and build its TransferReceiver.
 *
 * Layout (big-endian), prefixed by a distinct 'QI' magic so the receiver can
 * tell a metadata frame from a 'QF' fountain packet:
 *   magic(2) version(1) sessionId(8) nameLen(4) name(UTF-8)
 *   fileSize(8) chunkCount(4) chunkSize(4) sha256(32)
 */

const val META_MAGIC_0: Byte = 0x51 // 'Q'
const val META_MAGIC_1: Byte = 0x49 // 'I'

fun encodeMetadata(info: FileInfo, sessionId: Long): ByteArray {
    val nameBytes = info.fileName.toByteArray(Charsets.UTF_8)
    val buf = ByteBuffer.allocate(2 + 1 + 8 + 4 + nameBytes.size + 8 + 4 + 4 + 32)
        .order(ByteOrder.BIG_ENDIAN)
    buf.put(META_MAGIC_0)
    buf.put(META_MAGIC_1)
    buf.put(1)
    buf.putLong(sessionId)
    buf.putInt(nameBytes.size)
    buf.put(nameBytes)
    buf.putLong(info.fileSize)
    buf.putInt(info.chunkCount)
    buf.putInt(info.chunkSize)
    buf.put(info.sha256)
    return buf.array()
}

fun decodeMetadata(bytes: ByteArray): Pair<FileInfo, Long>? {
    if (bytes.size < 2 + 1 + 8 + 4 + 8 + 4 + 4 + 32) return null
    if (bytes[0] != META_MAGIC_0 || bytes[1] != META_MAGIC_1) return null
    val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
    buf.get(); buf.get(); buf.get() // magic0, magic1, version
    val sessionId = buf.getLong()
    val nameLen = buf.getInt()
    if (buf.remaining() < nameLen + 8 + 4 + 4 + 32) return null
    val nameBytes = ByteArray(nameLen)
    buf.get(nameBytes)
    val fileName = String(nameBytes, Charsets.UTF_8)
    val fileSize = buf.getLong()
    val chunkCount = buf.getInt()
    val chunkSize = buf.getInt()
    val sha256 = ByteArray(32)
    buf.get(sha256)
    val info = FileInfo(fileName, fileSize, chunkSize, chunkCount, sha256)
    return info to sessionId
}
