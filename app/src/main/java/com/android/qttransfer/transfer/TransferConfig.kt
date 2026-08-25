package com.android.qttransfer.transfer

object TransferConfig {

    const val CHUNK_SIZE = 120
    const val QR_VERSION = 25
    const val HEADER_SIZE = 22
    const val WIRE_VERSION = 3
    const val MAGIC_0: Byte = 0xD1.toByte()
    const val MAGIC_1: Byte = 0xC3.toByte()
    const val MAX_SOURCE_BLOCKS = 0xFFFF
}
