package com.alex.cam

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

/**
 * The H.264 encoder of the live feed and of the recording.
 *
 * The camera frames go into the encoder as **real YUV** - the same planes the
 * camera delivers - and not as a picture that is drawn into the encoder's
 * input surface.
 *
 * That difference is the whole reason the live feed used to stay black.
 * The encoder was picked for a YUV layout, but the picture was drawn into it
 * as if it were a screen: a hardware encoder cannot read a canvas, so it
 * produced a valid, but empty, video track - and the player showed black.
 *
 * The planes are copied with the strides the codec asks for, because an
 * encoder that gets misaligned rows answers with green bands or with nothing
 * at all.
 *
 * The encoder is created for the size of the first frame, so the planes of
 * the camera and the layout of the encoder always fit together.
 */
internal class H264Encoder(
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val bitRate: Int
) {

    companion object {
        private const val TAG = "H264Encoder"
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    }

    /** The format of the encoded track, as the codec reports it. */
    var outputFormat: MediaFormat? = null
        private set

    /** How many frames were handed to the encoder. */
    @Volatile
    var frames: Int = 0
        private set

    /** How many key frames came out of the encoder. */
    @Volatile
    var keyFrames: Int = 0
        private set

    /** The name of the encoder that was picked. */
    @Volatile
    var encoderName: String? = null
        private set

    /** The colour layout the encoder takes. */
    @Volatile
    var colorFormat: Int = 0
        private set

    /**
     * True when the codec wants the chroma planes woven together (NV12).
     *
     * Only the fallback path needs to know: the normal path copies the
     * planes one by one and lets the codec say how they are laid out.
     */
    private var semiPlanar = false

    private var codec: MediaCodec? = null
    private var running = false
    private var sawKeyFrame = false

    /** The buffer the fallback path packs the YUV into. */
    private var packed: ByteArray? = null

    /** Reused row buffers - one read per row, never per pixel. */
    private var sourceRow = ByteArray(0)
    private var targetRow = ByteArray(0)
    private var uRow = ByteArray(0)
    private var vRow = ByteArray(0)

    /**
     * Picks an encoder that really takes the frames.
     *
     * `MediaCodec.createEncoderByType` happily answers with a codec that
     * cannot be fed at all, so the codecs are looked at by name and only
     * those that offer a YUV layout are taken.
     *
     * The layout is NV12 (the chroma woven together) unless the codec only
     * offers the planar one - the two are told apart clearly, which the
     * fallback path needs.
     */
    @Suppress("DEPRECATION")
    private fun pickEncoder(): Pair<MediaCodecInfo, Int>? {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val planar = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
        val semiPlanar = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
        for (info in list.codecInfos) {
            if (!info.isEncoder) continue
            if (info.supportedTypes.none { it.equals(MIME, ignoreCase = true) }) continue
            val formats = runCatching {
                info.getCapabilitiesForType(MIME).colorFormats
            }.getOrNull() ?: continue
            if (!formats.contains(planar) && !formats.contains(semiPlanar)) continue
            // The first codec that fits the wanted size wins; among the ones
            // that fit, the one with the biggest frames is the most likely to
            // be the hardware encoder.
            val sizeOk = runCatching {
                val caps = info.getCapabilitiesForType(MIME).videoCapabilities
                caps?.supportedWidths?.contains(width) == true &&
                    caps?.supportedHeights?.contains(height) == true
            }.getOrDefault(true)
            if (!sizeOk) continue
            val format = if (formats.contains(semiPlanar)) semiPlanar else planar
            return info to format
        }
        return null
    }

    /** Starts the encoder. Returns false when the phone has no usable encoder. */
    fun start(): Boolean {
        val choice = pickEncoder()
        if (choice == null) {
            Log.e(TAG, "no H.264 encoder for ${width}x$height")
            return false
        }
        val (info, format) = choice
        encoderName = info.name
        colorFormat = format
        // The layout the fallback path has to produce. The normal path does
        // not care: it copies the planes one by one.
        @Suppress("DEPRECATION")
        semiPlanar = format != MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar

        val mediaFormat = MediaFormat.createVideoFormat(MIME, width, height).apply {
            // This is the line the old code was missing. Without it the codec
            // is set up for a YUV buffer, and a canvas that is drawn into it
            // is nothing the encoder can read.
            setInteger(MediaFormat.KEY_COLOR_FORMAT, format)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            // Without B frames the pictures come out in the order they were
            // taken, which is what a player expects.
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        }

        return try {
            val encoder = MediaCodec.createByCodecName(info.name)
            encoder.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            codec = encoder
            running = true
            Log.w(TAG, "encoder started (${info.name}, colour $format, ${width}x$height@$frameRate)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "the encoder refused the format: ${e.message}", e)
            codec = null
            false
        }
    }

    /**
     * Hands one camera frame to the encoder.
     *
     * @param image the frame of the camera
     * @param ptsUs the time of the frame, in microseconds
     * @return true when the frame went into the encoder
     */
    fun submit(image: ImageProxy, ptsUs: Long): Boolean {
        val encoder = codec ?: return false
        if (!running) return false
        try {
            val index = encoder.dequeueInputBuffer(10_000)
            if (index < 0) return false

            // The normal path: the codec hands out an image with its own
            // strides, and the planes are copied into it one by one.
            val target = encoder.getInputImage(index)
            if (target != null && copyPlanes(image, target)) {
                encoder.queueInputBuffer(index, 0, sizeOfFrame(), ptsUs, 0)
                frames++
                return true
            }

            // The fallback for the codecs that do not hand out an image: the
            // frame is packed into a flat buffer by hand.
            val buffer = encoder.getInputBuffer(index) ?: return false
            val data = packedFrame(image)
            buffer.clear()
            buffer.put(data, 0, data.size)
            encoder.queueInputBuffer(index, 0, data.size, ptsUs, 0)
            frames++
            return true
        } catch (e: Exception) {
            Log.w(TAG, "a frame could not be encoded: ${e.message}")
            return false
        }
    }

    /** The number of bytes one frame takes in the encoder. */
    private fun sizeOfFrame(): Int = width * height * 3 / 2

    /**
     * Copies the three planes of the camera frame into the codec's image.
     *
     * Every plane is copied with its own row and pixel stride, because the
     * codec's picture is usually padded and the chroma of many cameras is
     * stored with a pixel stride of two.
     *
     * Returns false when the codec's image does not have the three planes -
     * then the caller packs the frame by hand instead.
     */
    private fun copyPlanes(source: ImageProxy, target: Image): Boolean {
        val sourcePlanes = source.planes
        val targetPlanes = target.planes
        if (sourcePlanes.size < 3 || targetPlanes.size < 3) return false

        copyPlane(sourcePlanes[0], targetPlanes[0], source.width, source.height)

        val chromaWidth = source.width / 2
        val chromaHeight = source.height / 2
        copyPlane(sourcePlanes[1], targetPlanes[1], chromaWidth, chromaHeight)
        copyPlane(sourcePlanes[2], targetPlanes[2], chromaWidth, chromaHeight)
        return true
    }

    /**
     * Copies one plane, row by row.
     *
     * The row of the source is read in one go and then stepped through in
     * the array - reading the buffer byte by byte is what makes this slow
     * enough to drop frames.
     */
    private fun copyPlane(source: ImageProxy.PlaneProxy, target: Image.Plane, width: Int, height: Int) {
        val sourceBuffer = source.buffer
        val targetBuffer = target.buffer
        val sourceRowStride = source.rowStride
        val targetRowStride = target.rowStride
        val sourcePixelStride = source.pixelStride
        val targetPixelStride = target.pixelStride

        if (sourceRow.size < sourceRowStride) sourceRow = ByteArray(sourceRowStride)
        if (targetRow.size < targetRowStride) targetRow = ByteArray(targetRowStride)
        val read = sourceRow
        val write = targetRow

        for (row in 0 until height) {
            sourceBuffer.position(row * sourceRowStride)
            val toRead = minOf(sourceRowStride, sourceBuffer.remaining())
            if (toRead <= 0) break
            sourceBuffer.get(read, 0, toRead)

            targetBuffer.position(row * targetRowStride)
            if (sourcePixelStride == 1 && targetPixelStride == 1) {
                val toWrite = minOf(width, targetBuffer.remaining())
                targetBuffer.put(read, 0, toWrite)
                continue
            }
            // The target row keeps the bytes that are not written, so the
            // neighbours of a chroma byte are not lost.
            val toWrite = minOf(width * targetPixelStride, targetRowStride, targetBuffer.remaining())
            if (toWrite <= 0) continue
            targetBuffer.get(write, 0, toWrite)
            targetBuffer.position(row * targetRowStride)
            val limit = minOf(width * targetPixelStride, targetRowStride)
            for (col in 0 until width) {
                val targetIndex = col * targetPixelStride
                val sourceIndex = col * sourcePixelStride
                if (sourceIndex < toRead && targetIndex < limit) {
                    write[targetIndex] = read[sourceIndex]
                }
            }
            targetBuffer.put(write, 0, toWrite)
        }
    }

    /**
     * Packs the frame into a flat buffer - the fallback for the codecs that
     * do not hand out an input image.
     */
    private fun packedFrame(source: ImageProxy): ByteArray {
        val size = sizeOfFrame()
        var buffer = packed
        if (buffer == null || buffer.size != size) {
            buffer = ByteArray(size)
            packed = buffer
        }
        packYuv420(source, buffer)
        return buffer
    }

    /**
     * YUV_420_888 into a flat NV12 or I420 buffer.
     *
     * The chroma is woven together for NV12 and kept in two planes for I420 -
     * which of the two it is depends on the codec that was picked.
     */
    private fun packYuv420(source: ImageProxy, out: ByteArray) {
        val planes = source.planes
        if (planes.size < 3) return

        val luma = planes[0]
        val chromaU = planes[1]
        val chromaV = planes[2]

        if (sourceRow.size < luma.rowStride) sourceRow = ByteArray(luma.rowStride)
        val read = sourceRow
        val lumaBuffer = luma.buffer

        var offset = 0
        if (luma.pixelStride == 1) {
            for (row in 0 until height) {
                lumaBuffer.position(row * luma.rowStride)
                val toRead = minOf(luma.rowStride, lumaBuffer.remaining())
                if (toRead <= 0) break
                lumaBuffer.get(read, 0, toRead)
                val toWrite = minOf(width, toRead)
                System.arraycopy(read, 0, out, offset, toWrite)
                offset += width
            }
        } else {
            for (row in 0 until height) {
                val base = row * luma.rowStride
                for (col in 0 until width) {
                    val index = base + col * luma.pixelStride
                    if (index >= lumaBuffer.limit()) break
                    out[offset + col] = lumaBuffer.get(index)
                }
                offset += width
            }
        }

        val chromaWidth = width / 2
        val chromaHeight = height / 2
        if (uRow.size < chromaU.rowStride) uRow = ByteArray(chromaU.rowStride)
        if (vRow.size < chromaV.rowStride) vRow = ByteArray(chromaV.rowStride)
        val uRead = uRow
        val vRead = vRow

        if (semiPlanar) {
            // NV12: the chroma bytes follow each other.
            offset = width * height
            for (row in 0 until chromaHeight) {
                chromaU.buffer.position(row * chromaU.rowStride)
                chromaV.buffer.position(row * chromaV.rowStride)
                val uToRead = minOf(chromaU.rowStride, chromaU.buffer.remaining())
                val vToRead = minOf(chromaV.rowStride, chromaV.buffer.remaining())
                if (uToRead <= 0 || vToRead <= 0) break
                chromaU.buffer.get(uRead, 0, uToRead)
                chromaV.buffer.get(vRead, 0, vToRead)
                for (col in 0 until chromaWidth) {
                    out[offset++] = uRead[col * chromaU.pixelStride]
                    out[offset++] = vRead[col * chromaV.pixelStride]
                }
            }
        } else {
            // I420: the whole U plane, then the whole V plane.
            var uOffset = width * height
            var vOffset = width * height + chromaWidth * chromaHeight
            for (row in 0 until chromaHeight) {
                chromaU.buffer.position(row * chromaU.rowStride)
                chromaV.buffer.position(row * chromaV.rowStride)
                val uToRead = minOf(chromaU.rowStride, chromaU.buffer.remaining())
                val vToRead = minOf(chromaV.rowStride, chromaV.buffer.remaining())
                if (uToRead <= 0 || vToRead <= 0) break
                chromaU.buffer.get(uRead, 0, uToRead)
                chromaV.buffer.get(vRead, 0, vToRead)
                for (col in 0 until chromaWidth) {
                    out[uOffset++] = uRead[col * chromaU.pixelStride]
                    out[vOffset++] = vRead[col * chromaV.pixelStride]
                }
            }
        }
    }

    /** Takes the next encoded sample out of the codec. */
    fun dequeueOutput(info: MediaCodec.BufferInfo, timeoutUs: Long): Int {
        val encoder = codec ?: return MediaCodec.INFO_TRY_AGAIN_LATER
        val index = encoder.dequeueOutputBuffer(info, timeoutUs)
        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            outputFormat = encoder.outputFormat
        } else if (index >= 0) {
            if ((info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                sawKeyFrame = true
                keyFrames++
            }
        }
        return index
    }

    /** The buffer of an encoded sample. */
    fun outputBuffer(index: Int): ByteBuffer? = codec?.getOutputBuffer(index)

    /** Gives an encoded sample back to the codec. */
    fun releaseOutput(index: Int) {
        runCatching { codec?.releaseOutputBuffer(index, false) }
    }

    /** True when a key frame has been seen - media3 drops everything else. */
    fun hasKeyFrame(): Boolean = sawKeyFrame

    /** Stops the encoder. */
    fun stop() {
        running = false
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        outputFormat = null
    }
}
