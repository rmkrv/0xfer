package com.android.qttransfer.transfer

import java.io.File
import java.security.MessageDigest

data class FileInfo(
    val fileName: String,
    val fileSize: Long,
    val chunkSize: Int,
    val chunkCount: Int,
    val sha256: ByteArray,
    val mediaType: String = "application/octet-stream"
)

class FileChunker(
    private val chunkSize: Int = TransferConfig.CHUNK_SIZE
) {

    fun split(file: File, fileName: String? = null): Pair<FileInfo, List<ByteArray>> {

        require(file.isFile) {
            "Not a file: ${file.absolutePath}"
        }

        val bytes = file.readBytes()

        val chunkCount =
            (bytes.size + chunkSize - 1) / chunkSize

        val chunks = ArrayList<ByteArray>(chunkCount)

        for (i in 0 until chunkCount) {

            val start = i * chunkSize
            val end = minOf(
                start + chunkSize,
                bytes.size
            )

            val chunk = ByteArray(chunkSize)

            bytes.copyInto(
                destination = chunk,
                destinationOffset = 0,
                startIndex = start,
                endIndex = end
            )

            chunks += chunk
        }

        val info = FileInfo(
            fileName = fileName ?: file.name,
            fileSize = bytes.size.toLong(),
            chunkSize = chunkSize,
            chunkCount = chunkCount,
            sha256 = sha256(bytes)
        )

        return info to chunks
    }

    private fun sha256(data: ByteArray): ByteArray {
        return MessageDigest
            .getInstance("SHA-256")
            .digest(data)
    }
}
