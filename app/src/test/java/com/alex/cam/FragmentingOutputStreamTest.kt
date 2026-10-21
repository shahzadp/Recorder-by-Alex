package com.alex.cam

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks how the fragmented MP4 is split into the pieces the dashboard serves.
 *
 * The muxer does not write a `tfdt` box, so every fragment has to get the
 * time of its first sample - without it, a player cannot put the fragments on
 * a timeline.
 */
class FragmentingOutputStreamTest {

    private fun box(type: String, body: ByteArray): ByteArray {
        val out = ByteArray(8 + body.size)
        writeInt(out, 0, out.size)
        for (i in 0 until 4) out[4 + i] = type[i].code.toByte()
        System.arraycopy(body, 0, out, 8, body.size)
        return out
    }

    private fun writeInt(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = ((value shr 24) and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 16) and 0xFF).toByte()
        bytes[offset + 2] = ((value shr 8) and 0xFF).toByte()
        bytes[offset + 3] = (value and 0xFF).toByte()
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF shl 24) or
            (bytes[offset + 1].toInt() and 0xFF shl 16) or
            (bytes[offset + 2].toInt() and 0xFF shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun typeOf(bytes: ByteArray, offset: Int) =
        String(bytes, offset + 4, 4, Charsets.US_ASCII)

    /** A movie header with a 90000 time base. */
    private fun moov(): ByteArray {
        val mdhd = ByteArray(24)
        mdhd[0] = 0 // version
        writeInt(mdhd, 12, 90000) // the timescale
        return box("moov", box("mdhd", mdhd))
    }

    /** A fragment with samples of the given duration, plus the sample data. */
    fun moofWithData(sampleDuration: Int, samples: Int = 15): ByteArray {
        val moof = moof(sampleDuration, samples)
        // The sample data follows the fragment, and the sample run points at
        // it - relative to the beginning of the fragment.
        val mdat = box("mdat", ByteArray(100))
        val dataOffset = moof.size + 8
        // The sample run is the last box of the track fragment.
        val trun = indexOf(moof, "trun")
        writeInt(moof, trun + 16, dataOffset)
        return moof + mdat
    }

    /**
     * A fragment with samples of the given duration.
     *
     * The layout is the one a real muxer writes: a version byte, the flags
     * with the data offset, the sample duration, the sample size and the
     * sample flags.
     */
    private fun moof(sampleDuration: Int, samples: Int = 15): ByteArray {
        // The track header carries the position of the sample data.
        val tfhd = ByteArray(20)
        writeInt(tfhd, 0, 0x00000001) // base data offset
        writeInt(tfhd, 4, 1) // the track
        writeInt(tfhd, 8, 100) // the base data offset
        writeInt(tfhd, 12, 100)
        val trunFlags = 0x000701 // data offset + duration + size + flags
        val trunBody = ByteArray(12 + samples * 12)
        trunBody[0] = 1 // version
        writeInt(trunBody, 0, (1 shl 24) or trunFlags)
        writeInt(trunBody, 4, samples)
        writeInt(trunBody, 8, 100) // the data offset
        var p = 12
        for (i in 0 until samples) {
            writeInt(trunBody, p, sampleDuration); p += 4
            writeInt(trunBody, p, 500); p += 4 // the size
            writeInt(trunBody, p, 0x02000000); p += 4 // the flags
        }
        val traf = box("traf", box("tfhd", tfhd) + box("trun", trunBody))
        return box("moof", box("mfhd", ByteArray(4)) + traf)
    }

    @Test
    fun everyFragmentGetsTheTimeOfItsFirstSample() {
        var init: ByteArray? = null
        val fragments = ArrayList<ByteArray>()
        val stream = FragmentingOutputStream(
            onInit = { init = it },
            onFragment = { _, bytes, _ -> fragments.add(bytes) }
        )

        stream.write(box("ftyp", ByteArray(16)))
        stream.write(moov())
        stream.write(moof(6000))
        stream.write(moof(6000))
        stream.flush()

        assertTrue("the init segment must be published", init != null)
        assertEquals("two fragments", 2, fragments.size)

        // The first fragment starts at zero, the second one behind it.
        val first = fragments[0]
        val tfdt = indexOf(first, "tfdt")
        assertTrue("the fragment must carry a tfdt", tfdt >= 0)
        assertEquals(0L, readLong(first, tfdt + 12))

        val second = fragments[1]
        val tfdt2 = indexOf(second, "tfdt")
        assertTrue("the fragment must carry a tfdt", tfdt2 >= 0)
        // 15 samples of 6000 units are one second.
        assertEquals(90000L, readLong(second, tfdt2 + 12))
    }

    @Test
    fun theInitSegmentKeepsTheMovieHeader() {
        var init: ByteArray? = null
        val stream = FragmentingOutputStream(
            onInit = { init = it },
            onFragment = { _, _, _ -> }
        )
        stream.write(box("ftyp", ByteArray(16)))
        stream.write(moov())
        stream.flush()

        assertTrue(init != null)
        assertEquals("ftyp", typeOf(init!!, 0))
        assertEquals("moov", typeOf(init!!, readInt(init, 0)))
    }

    @Test
    fun theBoxesStayTheSameSize() {
        val fragments = ArrayList<ByteArray>()
        val stream = FragmentingOutputStream(
            onInit = { },
            onFragment = { _, bytes, _ -> fragments.add(bytes) }
        )
        stream.write(box("ftyp", ByteArray(16)))
        stream.write(moov())
        stream.write(moof(6000))
        stream.flush()

        val fragment = fragments[0]
        // Every top level box has to fit into the fragment.
        var offset = 0
        val types = ArrayList<String>()
        while (offset + 8 <= fragment.size) {
            val size = readInt(fragment, offset)
            assertTrue("the box size must be sane", size >= 8 && offset + size <= fragment.size)
            types.add(typeOf(fragment, offset))
            offset += size
        }
        assertEquals(fragment.size, offset)
        assertEquals(listOf("moof"), types)
    }

    @Test
    fun theSampleDataStillPointsAtTheRightPlace() {
        // The "tfdt" makes the fragment bigger, so the offset that points to
        // the sample data has to move with it - otherwise the player reads
        // rubbish and drops the frames without a word.
        val fragments = ArrayList<ByteArray>()
        val stream = FragmentingOutputStream(
            onInit = { },
            onFragment = { _, bytes, _ -> fragments.add(bytes) }
        )
        stream.write(box("ftyp", ByteArray(16)))
        stream.write(moov())
        stream.write(moofWithData(6000))
        stream.flush()

        val fragment = fragments[0]
        val moof = indexOf(fragment, "moof")
        val mdat = indexOf(fragment, "mdat")
        val trun = indexOf(fragment, "trun")

        // The sample run points from the beginning of the fragment to the
        // sample data - that is the payload of the mdat.
        val dataOffset = readInt(fragment, trun + 16)
        assertEquals("the offset must point at the sample data", mdat + 8, moof + dataOffset)
    }

    @Test
    fun theFragmentIsAddressedRelativeToItsOwnBox() {
        // A browser refuses a stream that carries a base data offset:
        //   "TFHD base-data-offset not allowed by MSE"
        // The muxer writes one, because in a file it is the place of the
        // moof box in the stream. A browser wants the fragment to be
        // addressed relative to its own moof box, so the base data offset
        // has to go and the "default base is moof" flag takes its place.
        val fragments = ArrayList<ByteArray>()
        val stream = FragmentingOutputStream(
            onInit = { },
            onFragment = { _, bytes, _ -> fragments.add(bytes) }
        )
        stream.write(box("ftyp", ByteArray(16)))
        stream.write(moov())
        stream.write(moofWithData(6000))
        stream.flush()

        val fragment = fragments[0]
        val tfhd = indexOf(fragment, "tfhd")
        assertTrue("the fragment must carry a tfhd", tfhd >= 0)

        val flags = readInt(fragment, tfhd + 8) and 0x00FFFFFF
        assertEquals(
            "the base data offset must not be there any more",
            0, flags and 0x000001
        )
        assertEquals(
            "the base of the fragment has to be its own moof box",
            0x020000, flags and 0x020000
        )

        // The track id has to survive the change.
        assertEquals("the track id", 1, readInt(fragment, tfhd + 12))

        // And the box has to be shorter by the eight bytes of the base data
        // offset, while the moof grew by the tfdt.
        val original = moofWithData(6000)
        val originalTfhd = indexOf(original, "tfhd")
        val originalSize = readInt(original, originalTfhd)
        assertEquals("the tfhd must be eight bytes shorter", originalSize - 8, readInt(fragment, tfhd))
    }

    private fun readLong(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) {
            value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
        }
        return value
    }

    private fun indexOf(bytes: ByteArray, type: String): Int {
        for (i in 0 until bytes.size - 4) {
            if (typeOf(bytes, i) == type) return i
        }
        return -1
    }
}
