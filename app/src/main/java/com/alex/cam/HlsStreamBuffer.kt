package com.alex.cam

/**
 * Rolling window of the HLS media fragments, so a browser that joins late can
 * still catch up with the live edge.
 */
class HlsStreamBuffer(private val capacity: Int = 15) {

    private val lock = Any()
    private val segments = LinkedHashMap<Int, ByteArray>()
    private val durations = LinkedHashMap<Int, Int>()
    private var initSegment: ByteArray? = null
    private var newestSequence = -1
    private var oldestSequence = -1

    fun setInitSegment(bytes: ByteArray) = synchronized(lock) {
        // A new encoder session: the fragments of the old one belong to a
        // different track and must not be mixed with the new ones.
        segments.clear()
        durations.clear()
        newestSequence = -1
        oldestSequence = -1
        initSegment = bytes
    }

    fun initSegment(): ByteArray? = synchronized(lock) { initSegment }

    /**
     * Adds a fragment.
     *
     * The duration is the real one, in milliseconds - the playlist has to
     * tell the player how long a segment really is, otherwise the stream
     * would play at the wrong speed.
     */
    fun addFragment(sequence: Int, bytes: ByteArray, durationMs: Int) = synchronized(lock) {
        if (oldestSequence >= 0 && sequence < oldestSequence) return
        segments[sequence] = bytes
        durations[sequence] = durationMs
        newestSequence = sequence
        oldestSequence = sequence - capacity + 1
        val stale = segments.keys.filter { it < oldestSequence }
        stale.forEach {
            segments.remove(it)
            durations.remove(it)
        }
    }

    fun fragment(sequence: Int): ByteArray? = synchronized(lock) { segments[sequence] }

    /** How long the fragment is, in milliseconds. */
    fun duration(sequence: Int): Int = synchronized(lock) { durations[sequence] ?: 1000 }

    /** Sequence numbers currently available, oldest first. */
    fun sequences(): List<Int> = synchronized(lock) { segments.keys.sorted() }

    fun fragmentCount(): Int = synchronized(lock) { segments.size }

    fun bufferedBytes(): Long = synchronized(lock) { segments.values.sumOf { it.size.toLong() } }

    fun clear() = synchronized(lock) {
        segments.clear()
        durations.clear()
        initSegment = null
        newestSequence = -1
        oldestSequence = -1
    }
}
