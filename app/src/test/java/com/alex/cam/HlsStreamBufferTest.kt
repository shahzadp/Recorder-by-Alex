package com.alex.cam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks how the buffered fragments are kept.
 *
 * A player takes the position of a fragment from the time inside it and its
 * length from the playlist. Rewriting the time of a fragment that was already
 * handed out would break the player, so the times stay as the encoder wrote
 * them.
 */
class HlsStreamBufferTest {

    /** A fragment with a "tfdt" box, like the splitter writes them. */
    private fun fragment(baseTime: Long): ByteArray {
        val tfdt = ByteArray(20)
        tfdt[0] = 0; tfdt[1] = 0; tfdt[2] = 0; tfdt[3] = 20
        "tfdt".forEachIndexed { i, c -> tfdt[4 + i] = c.code.toByte() }
        tfdt[8] = 1 // version
        for (i in 0 until 8) {
            tfdt[12 + i] = ((baseTime ushr (8 * (7 - i))) and 0xFF).toByte()
        }
        val traf = ByteArray(8 + tfdt.size)
        traf[0] = 0; traf[1] = 0; traf[2] = 0; traf[3] = (traf.size).toByte()
        "traf".forEachIndexed { i, c -> traf[4 + i] = c.code.toByte() }
        System.arraycopy(tfdt, 0, traf, 8, tfdt.size)
        val moof = ByteArray(8 + traf.size)
        moof[0] = 0; moof[1] = 0; moof[2] = 0; moof[3] = (moof.size).toByte()
        "moof".forEachIndexed { i, c -> moof[4 + i] = c.code.toByte() }
        System.arraycopy(traf, 0, moof, 8, traf.size)
        return moof
    }

    private fun timeOf(bytes: ByteArray): Long {
        val offset = FragmentTimebase.tfdtOffset(bytes)
        assertTrue("the fragment must carry a tfdt", offset >= 0)
        var value = 0L
        for (i in 0 until 8) {
            value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
        }
        return value
    }

    @Test
    fun theTimesOfTheFragmentsAreNotTouched() {
        // A player takes the position of a fragment from the time inside it and
        // its length from the playlist. Rewriting the time of a fragment that
        // was already handed out would break the player, so the times stay as
        // the encoder wrote them.
        val buffer = HlsStreamBuffer(capacity = 3)
        buffer.addFragment(10, fragment(500_000), 2000)
        buffer.addFragment(11, fragment(680_000), 2000)

        assertEquals(500_000L, timeOf(buffer.fragment(10)!!))
        assertEquals(680_000L, timeOf(buffer.fragment(11)!!))
    }

    @Test
    fun theWindowSlides() {
        val buffer = HlsStreamBuffer(capacity = 3)
        buffer.addFragment(10, fragment(0), 2000)
        buffer.addFragment(11, fragment(180_000), 2000)
        buffer.addFragment(12, fragment(360_000), 2000)
        // The oldest one falls out of the window.
        buffer.addFragment(13, fragment(540_000), 2000)

        assertEquals(null, buffer.fragment(10))
        assertNotNull(buffer.fragment(11))
        assertNotNull(buffer.fragment(13))
    }

    @Test
    fun theDurationIsKept() {
        val buffer = HlsStreamBuffer(capacity = 3)
        buffer.addFragment(1, fragment(0), 2345)
        assertEquals(2345, buffer.duration(1))
    }

    @Test
    fun theDefaultWindowIsShortEnoughForLive() {
        // The live feed is one-second fragments: a short window keeps the
        // player at the live edge instead of letting an old delay survive.
        val buffer = HlsStreamBuffer()
        for (sequence in 0 until 20) {
            buffer.addFragment(sequence, fragment(sequence * 90_000L), 1000)
        }

        assertEquals(HlsStreamBuffer.DEFAULT_CAPACITY, buffer.fragmentCount())
        assertEquals(
            (20 - HlsStreamBuffer.DEFAULT_CAPACITY until 20).toList(),
            buffer.sequences()
        )
    }

    @Test
    fun aNewInitSegmentClearsTheOldFragments() {
        // The fragments of the old session belong to a different track and must
        // not be mixed with the new ones.
        val buffer = HlsStreamBuffer(capacity = 5)
        buffer.addFragment(1, fragment(0), 2000)
        buffer.setInitSegment(byteArrayOf(1, 2, 3))

        assertEquals(null, buffer.fragment(1))
        assertEquals(0, buffer.fragmentCount())
    }
}
