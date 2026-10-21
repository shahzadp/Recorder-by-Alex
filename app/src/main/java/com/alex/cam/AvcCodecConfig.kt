package com.alex.cam

import android.media.MediaFormat
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes

/**
 * Unpacks the codec specific data of an H.264 encoder.
 *
 * Android hands the data over in its own "csd" format: a start code, a two
 * byte length and then the NAL unit. media3 wants the SPS and the PPS as two
 * separate entries in the initialization data, each one in Annex-B form - a
 * start code followed by the raw NAL unit.
 */
object AvcCodecConfig {

    /**
     * Builds the track description of a video track for the muxer.
     *
     * Returns null when the encoder did not give usable picture parameters.
     */
    fun buildVideoFormat(outputFormat: MediaFormat, mimeType: String = MimeTypes.VIDEO_H264): Format? {
        val csd0 = outputFormat.getByteBuffer("csd-0")?.let { readAll(it) }
        val csd1 = outputFormat.getByteBuffer("csd-1")?.let { readAll(it) }
        val split = split(csd0, csd1) ?: return null
        return Format.Builder()
            .setSampleMimeType(mimeType)
            .setWidth(outputFormat.getInteger(MediaFormat.KEY_WIDTH, 0))
            .setHeight(outputFormat.getInteger(MediaFormat.KEY_HEIGHT, 0))
            .setInitializationData(listOf(withStartCode(split.first), withStartCode(split.second)))
            .build()
    }

    private fun readAll(buffer: java.nio.ByteBuffer): ByteArray {
        val data = ByteArray(buffer.remaining())
        buffer.get(data)
        return data
    }

    /**
     * Splits the codec specific data into the SPS and the PPS.
     *
     * Returns null when the data cannot be understood.
     */
    fun split(csd0: ByteArray?, csd1: ByteArray?): Pair<ByteArray, ByteArray>? {
        if (csd0 != null && csd1 != null) {
            val sps = parseCsd(csd0) ?: return null
            val pps = parseCsd(csd1) ?: return null
            return sps to pps
        }
        if (csd0 != null) {
            // Some encoders put both NAL units into "csd-0".
            val second = findStartCode(csd0, 4)
            if (second > 0) {
                val sps = parseCsd(csd0.copyOfRange(0, second)) ?: return null
                val pps = parseCsd(csd0.copyOfRange(second, csd0.size)) ?: return null
                return sps to pps
            }
            return null
        }
        return null
    }

    /**
     * Unpacks one "csd" buffer.
     *
     * Android hands the data over in a few different shapes: with a start code
     * and a two byte length in front of the NAL unit, with a start code
     * only, or as the bare NAL unit. All of them have to be understood - one
     * byte too many or too few and the player gets a broken picture.
     */
    fun parseCsd(data: ByteArray): ByteArray? {
        if (data.isEmpty()) return null
        val start = findStartCode(data, 0)
        if (start < 0) {
            // No start code: the data is the NAL unit itself.
            return data
        }
        // The start code is three bytes ("00 00 01") or four ("00 00 00 01");
        // in both cases the data starts three bytes after the "00 00 01" that
        // was found.
        val payload = start + 3
        if (payload + 2 <= data.size) {
            val length =
                ((data[payload].toInt() and 0xFF) shl 8) or (data[payload + 1].toInt() and 0xFF)
            if (length > 0 && payload + 2 + length <= data.size) {
                return data.copyOfRange(payload + 2, payload + 2 + length)
            }
        }
        return data.copyOfRange(payload, data.size)
    }

    /** The index of the next Annex-B start code, or -1. */
    fun findStartCode(data: ByteArray, from: Int): Int {
        var i = from
        while (i + 3 <= data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                return i
            }
            i++
        }
        return -1
    }

    /** media3 reads the NAL units from the buffer, so it needs the start code. */
    fun withStartCode(nal: ByteArray): ByteArray {
        val out = ByteArray(nal.size + 4)
        out[3] = 1
        System.arraycopy(nal, 0, out, 4, nal.size)
        return out
    }
}
