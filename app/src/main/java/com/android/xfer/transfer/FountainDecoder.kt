package com.android.xfer.transfer

/** Peeling decoder paired with [FountainEncoder]'s deterministic carousel. */
class FountainDecoder(
    private val sessionId: Int,
    private val sourceBlockCount: Int,
    private val blockLength: Int,
    private val totalLength: Int
) {
    // Identity equality is intentional. Equations live in hash sets while
    // their unknown-index sets shrink during peeling; a data class would make
    // its hash code change after insertion, preventing removal and allowing a
    // frame to be XORed twice when parallel camera delivery reorders frames.
    private class Equation(val indexes: MutableSet<Int>, val data: ByteArray)
    private val solved = arrayOfNulls<ByteArray>(sourceBlockCount)
    private val waiting = HashMap<Int, MutableSet<Equation>>()
    private val seen = HashSet<Int>()
    var solvedCount = 0
        private set
    var framesNew = 0
        private set
    var framesRedundant = 0
        private set

    fun receive(packet: FountainPacket): Boolean {
        require(packet.sessionId == sessionId && packet.sourceBlockCount == sourceBlockCount &&
            packet.blockLength == blockLength && packet.totalLength == totalLength) { "Frame belongs to another stream" }
        if (!seen.add(packet.sequence)) return false
        framesNew++
        if (isComplete()) return true

        val indexes = DecimenWire.frameComposition(sourceBlockCount, sessionId, packet.sequence).toMutableSet()
        val data = packet.payload.copyOf()
        for (index in indexes.toList()) solved[index]?.let { known ->
            DecimenWire.xorInto(data, known)
            indexes.remove(index)
        }
        when (indexes.size) {
            0 -> framesRedundant++
            1 -> resolve(indexes.first(), data)
            else -> {
                val equation = Equation(indexes, data)
                for (index in indexes) waiting.getOrPut(index) { linkedSetOf() }.add(equation)
            }
        }
        return true
    }

    fun isComplete(): Boolean = solvedCount == sourceBlockCount
    fun progress(): Double = solvedCount.toDouble() / sourceBlockCount
    fun getChunk(index: Int): ByteArray? = solved[index]

    fun assemble(): ByteArray? {
        if (!isComplete()) return null
        val result = ByteArray(totalLength)
        for (index in solved.indices) {
            val offset = index * blockLength
            val count = minOf(blockLength, totalLength - offset)
            if (count > 0) solved[index]!!.copyInto(result, offset, 0, count)
        }
        return result
    }

    private fun resolve(firstIndex: Int, firstData: ByteArray) {
        val queue = ArrayDeque<Pair<Int, ByteArray>>()
        queue.add(firstIndex to firstData)
        while (queue.isNotEmpty()) {
            val (index, data) = queue.removeLast()
            if (solved[index] != null) continue
            solved[index] = data
            solvedCount++
            val equations = waiting.remove(index) ?: continue
            for (equation in equations) {
                DecimenWire.xorInto(equation.data, data)
                equation.indexes.remove(index)
                if (equation.indexes.size == 1) {
                    val remaining = equation.indexes.first()
                    waiting[remaining]?.remove(equation)
                    if (solved[remaining] == null) queue.add(remaining to equation.data)
                }
            }
        }
    }
}
