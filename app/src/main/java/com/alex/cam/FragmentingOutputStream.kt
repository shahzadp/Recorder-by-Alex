package com.alex.cam

import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Splits the fragmented MP4 byte stream produced by the muxer into an init
 * segment and individual media fragments.
 *
 * The muxer writes a continuous stream of ISO-BMFF boxes:
 *
 *   ftyp | moov | (styp)? moof mdat | (styp)? moof mdat | ...
 *
 * `ftyp` + `moov` form the init segment; every `styp`/`moof` pair starts a new
 * media fragment that ends when the next fragment begins.
 *
 * The muxer does not write a `tfdt` box, so a fragment carries no absolute
 * time of its own - and a player that gets the fragments one by one (instead
 * of as one continuous stream) cannot place them on a timeline. This class
 * therefore adds the missing `tfdt` to every fragment, with the time that
 * follows from the durations of the samples before it.
 */
internal class FragmentingOutputStream(
    private val onInit: (ByteArray) -> Unit,
    private val onFragment: (Int, ByteArray, Long) -> Unit
) : OutputStream() {

    private val pending = ByteArrayOutputStream()
    private val initBytes = ByteArrayOutputStream()
    private val currentFragment = ByteArrayOutputStream()
    private var initPublished = false
    private var sequence = 0
    private var lastPublishedAt = 0L

    /** The time base of the track, from the movie header. */
    private var timescale = DEFAULT_TIMESCALE

    /** The time of the first sample of the next fragment. */
    private var baseMediaDecodeTime = 0L

    /** The length of the fragment that is being collected, in milliseconds. */
    private var fragmentDuration = 0

    /** How many boxes were seen - shown on the dashboard. */
    @Volatile
    var parsedBoxes: Int = 0
        private set

    /** How many fragments were handed over. */
    @Volatile
    var publishedFragments: Int = 0
        private set

    /** The bytes that came in. */
    @Volatile
    var totalBytes: Long = 0L
        private set

    override fun write(b: Int) {
        pending.write(b)
        totalBytes++
        parse()
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        pending.write(b, off, len)
        totalBytes += len
        parse()
    }

    /** Releases the last pending fragment (it is only known to be complete at the end). */
    override fun flush() {
        super.flush()
        publishInitIfNeeded()
        publishFragmentIfNeeded()
    }

    private fun parse() {
        while (true) {
            val buffer = pending.toByteArray()
            if (buffer.size < 8) return

            var size = readInt(buffer, 0)
            val type = String(buffer, 4, 4, Charsets.US_ASCII)
            var headerSize = 8

            if (size == 1L) {
                if (buffer.size < 16) return
                size = readLong(buffer, 8)
                headerSize = 16
            } else if (size == 0L) {
                size = buffer.size.toLong()
            }
            if (size < headerSize) return
            if (buffer.size < size) return

            val box = buffer.copyOfRange(0, size.toInt())
            pending.reset()
            pending.write(buffer, size.toInt(), buffer.size - size.toInt())
            parsedBoxes++

            handleBox(type, box)
        }
    }

    private fun handleBox(type: String, box: ByteArray) {
        when (type) {
            "ftyp", "moov" -> if (!initPublished) {
                initBytes.write(box)
                if (type == "moov") timescale = readTimescale(box) ?: timescale
            }

            // A segment type box describes the fragment that follows it, so it
            // is held back until the next moof arrives.
            "styp" -> {
                publishInitIfNeeded()
                publishFragmentIfNeeded()
                pendingStyp = box
            }

            "moof" -> {
                publishInitIfNeeded()
                publishFragmentIfNeeded()
                pendingStyp?.let {
                    currentFragment.write(it)
                    pendingStyp = null
                }
                // The fragment gets the time of its first sample, so that a
                // player can put it on the timeline.
                currentFragment.write(addDecodeTime(box))
            }

            else -> if (initPublished) currentFragment.write(box)
        }
    }

    private var pendingStyp: ByteArray? = null

    private fun publishInitIfNeeded() {
        if (initPublished) return
        initPublished = true
        if (initBytes.size() > 0) onInit(initBytes.toByteArray())
    }

    private fun publishFragmentIfNeeded() {
        if (currentFragment.size() == 0) return
        val now = System.currentTimeMillis()
        val wallClock = if (lastPublishedAt == 0L) 0L else (now - lastPublishedAt).coerceAtLeast(1)
        lastPublishedAt = now
        val bytes = currentFragment.toByteArray()
        currentFragment.reset()
        publishedFragments++
        // The length of the fragment is known from the sample durations - the
        // wall clock is only a fallback.
        val duration: Long = if (fragmentDuration > 0) fragmentDuration.toLong() else wallClock
        fragmentDuration = 0
        onFragment(sequence++, bytes, duration)
    }

    /**
     * Adds the `tfdt` box with the time of the first sample.
     *
     * It goes into the track fragment, right behind the track header, and the
     * times of the following fragments follow from the sample durations.
     */
    private fun addDecodeTime(moof: ByteArray): ByteArray {
        val traf = findChild(moof, 8, moof.size, "traf")
        if (traf < 0) return moof
        val trafEnd = traf + readInt(moof, traf).toInt()
        val tfhd = findChild(moof, traf + 8, trafEnd, "tfhd")
        if (tfhd < 0) return moof
        val insertAt = tfhd + readInt(moof, tfhd).toInt()

        // The durations of the samples say how long this fragment is.
        val durations = sumSampleDurations(moof, traf, trafEnd)
        val tfdt = buildTfdt(baseMediaDecodeTime)
        baseMediaDecodeTime += durations
        // The length in milliseconds, so that the playlist can tell the player
        // how long the segment really is.
        fragmentDuration = ((durations * 1000L) / timescale).toInt()
        val out = ByteArrayOutputStream(moof.size + tfdt.size)
        out.write(moof, 0, insertAt)
        out.write(tfdt)
        out.write(moof, insertAt, moof.size - insertAt)

        val body = out.toByteArray()
        // The traf grew - the sizes have to follow.
        writeInt(body, traf, (trafEnd - traf) + tfdt.size)
        writeInt(body, 0, body.size)
        // The data of the samples moved as well: the offsets that point to it
        // have to follow, or the player reads rubbish.
        fixDataOffsets(body, traf, trafEnd + tfdt.size, tfdt.size)
        return body
    }

    /**
     * Moves the offset that points to the sample data.
     *
     * The sample run carries the offset from the beginning of the fragment to
     * the sample data - and the data moved when the "tfdt" was added.
     *
     * The track header is left alone: a base data offset of zero means "the
     * beginning of the fragment", and that is still true.
     */
    private fun fixDataOffsets(moof: ByteArray, traf: Int, trafEnd: Int, shift: Int) {
        val trun = findChild(moof, traf + 8, trafEnd, "trun")
        if (trun < 0) return
        val flags = readInt(moof, trun + 8).toInt() and 0x00FFFFFF
        if (flags and TRUN_DATA_OFFSET != 0) {
            val offset = trun + 16
            writeInt(moof, offset, (readInt(moof, offset) + shift).toInt())
        }
    }

    /** The `tfdt` box: version 1 with a 64 bit time. */
    private fun buildTfdt(time: Long): ByteArray {
        val box = ByteArray(20)
        writeInt(box, 0, 20)
        box[4] = 't'.code.toByte()
        box[5] = 'f'.code.toByte()
        box[6] = 'd'.code.toByte()
        box[7] = 't'.code.toByte()
        box[8] = 1 // version
        for (i in 0 until 8) {
            box[12 + i] = ((time ushr (8 * (7 - i))) and 0xFF).toByte()
        }
        return box
    }

    /** The time base of the track, from the movie header. */
    private fun readTimescale(moov: ByteArray): Int? {
        val mdhd = findChild(moov, 8, moov.size, "mdhd")
        if (mdhd < 0) return null
        val version = moov[mdhd + 8].toInt()
        val offset = if (version == 1) mdhd + 8 + 4 + 8 + 8 else mdhd + 8 + 4 + 4 + 4
        if (offset + 4 > moov.size) return null
        return readInt(moov, offset).toInt()
    }

    /** The sum of the sample durations of a fragment. */
    private fun sumSampleDurations(moof: ByteArray, traf: Int, trafEnd: Int): Long {
        val trun = findChild(moof, traf + 8, trafEnd, "trun")
        if (trun < 0) return 0
        // The full box holds the version in the upper byte - only the lower
        // three bytes are the flags.
        val flags = readInt(moof, trun + 8).toInt() and 0x00FFFFFF
        val count = readInt(moof, trun + 12).toInt()
        if (count <= 0 || count > 4096) return 0

        var p = trun + 16
        if (flags and TRUN_DATA_OFFSET != 0) p += 4
        if (flags and TRUN_FIRST_SAMPLE_FLAGS != 0) p += 4
        if (flags and TRUN_SAMPLE_DURATION == 0) return 0
        val hasSize = flags and TRUN_SAMPLE_SIZE != 0
        val hasFlags = flags and TRUN_SAMPLE_FLAGS != 0
        val hasOffset = flags and TRUN_SAMPLE_OFFSET != 0

        var total = 0L
        for (i in 0 until count) {
            if (p + 4 > moof.size) break
            total += readInt(moof, p)
            p += 4
            if (hasSize) p += 4
            if (hasFlags) p += 4
            if (hasOffset) p += 4
        }
        return total
    }

    /** The offset of a child box between [from] and [to], or -1. */
    private fun findChild(bytes: ByteArray, from: Int, to: Int, type: String): Int {
        var i = from
        while (i + 8 <= to && i + 8 <= bytes.size) {
            val size = readInt(bytes, i).toInt()
            if (size < 8) return -1
            if (String(bytes, i + 4, 4, Charsets.US_ASCII) == type) return i
            i += size
        }
        return -1
    }

    private fun writeInt(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = ((value ushr 24) and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        bytes[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        bytes[offset + 3] = (value and 0xFF).toByte()
    }

    private fun readInt(buffer: ByteArray, offset: Int): Long =
        ((buffer[offset].toLong() and 0xFF) shl 24) or
            ((buffer[offset + 1].toLong() and 0xFF) shl 16) or
            ((buffer[offset + 2].toLong() and 0xFF) shl 8) or
            (buffer[offset + 3].toLong() and 0xFF)

    private fun readLong(buffer: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) {
            value = (value shl 8) or (buffer[offset + i].toLong() and 0xFF)
        }
        return value
    }

    private companion object {
        /** The time base media3 uses for video. */
        const val DEFAULT_TIMESCALE = 90000

        // The flags of a "trun" box (ISO/IEC 14496-12).
        const val TRUN_DATA_OFFSET = 0x000001
        const val TRUN_FIRST_SAMPLE_FLAGS = 0x000004
        const val TRUN_SAMPLE_DURATION = 0x000100
        const val TRUN_SAMPLE_SIZE = 0x000200
        const val TRUN_SAMPLE_FLAGS = 0x000400
        const val TRUN_SAMPLE_OFFSET = 0x000800
    }
}

/**
 * The time of a media fragment.
 *
 * A playlist always counts its segments from zero, so the times inside the
 * fragments have to be relative to the oldest segment of the playlist - and
 * that changes every time the window slides. The times are therefore written
 * again whenever a fragment is added.
 */
internal object FragmentTimebase {

    /**
     * Sets the time of the first sample of a fragment.
     *
     * Returns false when the fragment carries no time.
     */
    fun setTime(fragment: ByteArray, time: Long): Boolean {
        val offset = tfdtOffset(fragment)
        if (offset < 0) return false
        for (i in 0 until 8) {
            fragment[offset + i] = ((time ushr (8 * (7 - i))) and 0xFF).toByte()
        }
        return true
    }

    /** The offset of the time field inside the "tfdt" box, or -1. */
    fun tfdtOffset(fragment: ByteArray): Int {
        var i = 0
        while (i + 8 <= fragment.size) {
            val size = readInt(fragment, i).toInt()
            if (size < 8) return -1
            val type = String(fragment, i + 4, 4, Charsets.US_ASCII)
            if (type == "moof") return tfdtInside(fragment, i, i + size)
            if (type == "mdat") return -1
            i += size
        }
        return -1
    }

    private fun tfdtInside(fragment: ByteArray, from: Int, to: Int): Int {
        var i = from + 8
        while (i + 8 <= to && i + 8 <= fragment.size) {
            val size = readInt(fragment, i).toInt()
            if (size < 8) return -1
            val type = String(fragment, i + 4, 4, Charsets.US_ASCII)
            if (type == "traf") {
                var j = i + 8
                while (j + 8 <= i + size && j + 8 <= fragment.size) {
                    val childSize = readInt(fragment, j).toInt()
                    if (childSize < 8) return -1
                    val childType = String(fragment, j + 4, 4, Charsets.US_ASCII)
                    if (childType == "tfdt") return j + 12
                    j += childSize
                }
                return -1
            }
            i += size
        }
        return -1
    }

    private fun readInt(buffer: ByteArray, offset: Int): Long =
        ((buffer[offset].toLong() and 0xFF) shl 24) or
            ((buffer[offset + 1].toLong() and 0xFF) shl 16) or
            ((buffer[offset + 2].toLong() and 0xFF) shl 8) or
            (buffer[offset + 3].toLong() and 0xFF)
}
