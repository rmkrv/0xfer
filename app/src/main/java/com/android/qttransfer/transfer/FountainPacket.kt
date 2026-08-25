package com.android.qttransfer.transfer

/** A self-describing Decimen v3 fountain frame. All integer fields are wire-size values. */
data class FountainPacket(
    val sessionId: Int,
    val sequence: Int,
    val sourceBlockCount: Int,
    val blockLength: Int,
    val totalLength: Int,
    val payloadFnv: Int,
    val flags: Int = 0,
    val payload: ByteArray
)
