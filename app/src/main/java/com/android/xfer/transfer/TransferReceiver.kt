package com.android.xfer.transfer

class TransferReceiver(firstFrame: FountainPacket) {
    private val identity = StreamIdentity(firstFrame)
    private val decoder = FountainDecoder(firstFrame.sessionId, firstFrame.sourceBlockCount, firstFrame.blockLength, firstFrame.totalLength)

    val blockLength: Int get() = identity.blockLength
    val sourceBlockCount: Int get() = identity.sourceBlockCount

    fun belongsTo(packet: FountainPacket): Boolean = identity == StreamIdentity(packet)
    fun receive(packet: FountainPacket): Boolean = decoder.receive(packet)
    fun progress(): Double = decoder.progress()
    fun isComplete(): Boolean = decoder.isComplete()
    fun receivedFrameCount(): Int = decoder.framesNew - decoder.framesRedundant

    fun unpack(): DecimenContainer.Unpacked {
        val container = decoder.assemble() ?: error("Transfer is not complete")
        require(DecimenWire.fnv1a(container) == identity.payloadFnv) { "Fountain payload checksum mismatch" }
        return DecimenContainer.unpack(container)
    }

    private data class StreamIdentity(
        val sessionId: Int,
        val sourceBlockCount: Int,
        val blockLength: Int,
        val totalLength: Int,
        val payloadFnv: Int,
        val criticalFlags: Int
    ) {
        constructor(packet: FountainPacket) : this(packet.sessionId, packet.sourceBlockCount, packet.blockLength, packet.totalLength, packet.payloadFnv, packet.flags and 0x0F)
    }
}
