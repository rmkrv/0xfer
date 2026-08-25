package com.android.xfer.transfer

import java.io.File
import java.security.MessageDigest

class FileAssembler(
    private val info: FileInfo
) {

    fun assemble(
        decoder: FountainDecoder,
        output: File
    ) {

        require(decoder.isComplete()) {
            "Transfer is not complete"
        }

        output.outputStream().use { outputStream ->

            var remaining =
                info.fileSize

            for (i in 0 until info.chunkCount) {

                val chunk =
                    decoder.getChunk(i)
                        ?: error(
                            "Missing chunk $i"
                        )

                val count =
                    minOf(
                        remaining,
                        chunk.size.toLong()
                    ).toInt()

                outputStream.write(
                    chunk,
                    0,
                    count
                )

                remaining -= count
            }
        }

        verify(output)
    }

    private fun verify(file: File) {

        val digest =
            MessageDigest.getInstance("SHA-256")

        file.inputStream().use { input ->

            val buffer =
                ByteArray(64 * 1024)

            while (true) {

                val count =
                    input.read(buffer)

                if (count == -1)
                    break

                digest.update(
                    buffer,
                    0,
                    count
                )
            }
        }

        val actual = digest.digest()

        check(
            actual.contentEquals(info.sha256)
        ) {
            "SHA-256 verification failed"
        }
    }
}
