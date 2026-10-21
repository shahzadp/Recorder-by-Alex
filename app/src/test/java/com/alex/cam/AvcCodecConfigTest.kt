package com.alex.cam

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins down how the codec specific data of an H.264 encoder is unpacked.
 *
 * Android delivers the SPS and the PPS in its own "csd" format, media3 wants
 * them in Annex-B form. Getting this wrong breaks the muxer with a null
 * pointer somewhere deep inside it.
 */
class AvcCodecConfigTest {

    /** Android's format: a start code, a two byte length and the NAL unit. */
    private fun csd(nal: ByteArray): ByteArray {
        val out = ByteArray(4 + 2 + nal.size)
        out[3] = 1
        out[4] = (nal.size shr 8).toByte()
        out[5] = nal.size.toByte()
        System.arraycopy(nal, 0, out, 6, nal.size)
        return out
    }

    private val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1e)
    private val pps = byteArrayOf(0x68, 0xce.toByte(), 0x3c, 0x80.toByte())

    @Test
    fun readsTheSpsAndThePpsFromTwoBuffers() {
        val split = AvcCodecConfig.split(csd(sps), csd(pps))

        assertNotNull("the data must be understood", split)
        assertArrayEquals(sps, split!!.first)
        assertArrayEquals(pps, split.second)
    }

    @Test
    fun readsTheSpsAndThePpsFromOneBuffer() {
        val both = csd(sps) + csd(pps)

        val split = AvcCodecConfig.split(both, null)

        assertNotNull("the data must be understood", split)
        assertArrayEquals(sps, split!!.first)
        assertArrayEquals(pps, split.second)
    }

    @Test
    fun theResultCarriesTheStartCode() {
        // media3 reads the NAL units out of the buffer, so it needs the
        // start code in front of them.
        val withStart = AvcCodecConfig.withStartCode(sps)

        assertArrayEquals(
            byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0x00, 0x1e),
            withStart
        )
        // And the NAL unit has to be found right behind the start code.
        assertArrayEquals(sps, AvcCodecConfig.parseCsd(withStart))
    }

    @Test
    fun rubbishIsRejected() {
        assertNull("no data at all", AvcCodecConfig.split(null, null))
        assertNull("no start code", AvcCodecConfig.split(byteArrayOf(1, 2, 3, 4), null))
    }

    @Test
    fun aStartCodeWithoutALengthStillWorks() {
        val raw = byteArrayOf(0, 0, 0, 1) + sps

        val parsed = AvcCodecConfig.parseCsd(raw)

        assertNotNull(parsed)
        assertArrayEquals(sps, parsed)
    }

    @Test
    fun aBareNalUnitIsTakenAsItIs() {
        // Some encoders hand the NAL unit over without a start code.
        val parsed = AvcCodecConfig.parseCsd(sps)

        assertNotNull(parsed)
        assertArrayEquals(sps, parsed)
    }

    @Test
    fun aThreeByteStartCodeIsUnderstood() {
        val raw = byteArrayOf(0, 0, 1) + sps

        val parsed = AvcCodecConfig.parseCsd(raw)

        assertNotNull(parsed)
        assertArrayEquals(sps, parsed)
    }
}
