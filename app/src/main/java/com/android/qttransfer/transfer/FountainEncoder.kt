package com.android.qttransfer.transfer

/**
 * Kotlin implementation of Decimen's v2+ systematic-carousel fountain code.
 * A cycle sends K direct source blocks, followed by K deterministic repair
 * blocks. A clean capture therefore completes in exactly K frames.
 */
class FountainEncoder(
    payload: ByteArray,
    private val blockLength: Int,
    private val sessionId: Int,
    private val flags: Int = 0
) {
    val sourceBlockCount = ((payload.size + blockLength - 1) / blockLength).coerceAtLeast(1)
    private val blocks = Array(sourceBlockCount) { block ->
        ByteArray(blockLength).also { out ->
            val start = block * blockLength
            val end = minOf(start + blockLength, payload.size)
            if (start < end) payload.copyInto(out, 0, start, end)
        }
    }
    private val totalLength = payload.size
    private val payloadFnv = DecimenWire.fnv1a(payload)
    private var sequence = 0

    init {
        require(payload.isNotEmpty()) { "Cannot transfer an empty payload" }
        require(blockLength in 1..0xFFFF)
        require(sourceBlockCount <= TransferConfig.MAX_SOURCE_BLOCKS) { "Too many source blocks" }
    }

    fun nextPacket(): FountainPacket {
        val current = sequence++
        val out = ByteArray(blockLength)
        for (index in DecimenWire.frameComposition(sourceBlockCount, sessionId, current)) {
            DecimenWire.xorInto(out, blocks[index])
        }
        return FountainPacket(sessionId, current, sourceBlockCount, blockLength, totalLength, payloadFnv, flags, out)
    }
}

internal object DecimenWire {
    private const val REPAIR_DEGREE_MIN = 4
    private const val REPAIR_DEGREE_MAX = 24

    fun frameComposition(k: Int, sessionId: Int, sequence: Int): IntArray {
        val position = (sequence.toLong() and 0xFFFF_FFFFL).rem((2L * k)).toInt()
        if (position < k) return intArrayOf(position)
        val random = SplitMix32(frameSeed(sessionId, sequence))
        // JavaScript's bitwise values are signed, but `%` in the reference is
        // applied to an unsigned 32-bit result. Kotlin Int.rem() would return
        // a negative value for half of the PRNG output.
        val degree = minOf(
            k,
            REPAIR_DEGREE_MIN + (random.nextUInt().toLong() and 0xFFFF_FFFFL)
                .rem(REPAIR_DEGREE_MAX - REPAIR_DEGREE_MIN + 1).toInt()
        )
        val result = IntArray(degree)
        val selected = HashSet<Int>(degree * 2)
        var count = 0
        while (count < degree) {
            val value = ((random.nextUInt().toLong() and 0xFFFF_FFFFL).rem(k)).toInt()
            if (selected.add(value)) result[count++] = value
        }
        return result
    }

    fun fnv1a(bytes: ByteArray): Int {
        var hash = 0x811C9DC5.toInt()
        for (byte in bytes) hash = imul(hash xor (byte.toInt() and 0xFF), 0x01000193)
        return hash
    }

    fun xorInto(target: ByteArray, source: ByteArray) {
        for (i in target.indices) target[i] = (target[i].toInt() xor source[i].toInt()).toByte()
    }

    private fun frameSeed(sessionId: Int, sequence: Int): Int {
        var hash = imul(sessionId + 1, 0x9E3779B1.toInt()) xor (sequence + 0x85EBCA6B.toInt())
        hash = imul(hash xor (hash ushr 13), 0xC2B2AE35.toInt())
        return hash xor (hash ushr 16)
    }

    private fun imul(a: Int, b: Int): Int = (a.toLong() * b.toLong()).toInt()

    private class SplitMix32(seed: Int) {
        private var state = seed
        fun nextUInt(): Int {
            state += 0x9E3779B9.toInt()
            var value = state xor (state ushr 16)
            value = imul(value, 0x21F0AAAD)
            value = value xor (value ushr 15)
            value = imul(value, 0x735A2D97)
            return value xor (value ushr 15)
        }
    }
}
