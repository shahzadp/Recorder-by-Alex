package com.alex.cam

import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.camera.core.ImageProxy
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.muxer.FragmentedMp4Muxer
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.WritableByteChannel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Encodes the camera frames into HLS (fMP4) fragments for the live dashboard.
 *
 *   CameraX ImageAnalysis (YUV_420_888)
 *        -> converted to I420 and queued into a MediaCodec H.264 encoder that
 *           runs in byte buffer mode
 *        -> media3 FragmentedMp4Muxer writes fMP4 into a FragmentingOutputStream
 *        -> that stream splits it into an init segment (ftyp+moov) and a series
 *           of media fragments (styp+moof+mdat)
 *
 * The server then serves /init.mp4, /seg-<n>.m4s and the rolling /live.m3u8.
 */
class LiveStreamEncoder(
    /**
     * The number of the encoder session.
     *
     * Every session brings its own init segment (with its own picture
     * parameters), so the fragments of an old session must not be mixed up
     * with the new ones.
     */
    val sessionId: Int,
    private val callback: Callback
) {

    interface Callback {
        /** ftyp + moov: required once before any fragment can be played. */
        fun onInitSegment(bytes: ByteArray)

        /** One media fragment, numbered from 0. */
        fun onFragment(sequence: Int, bytes: ByteArray, durationMs: Long, sessionId: Int)

        /** A camera frame arrived - the live feed is really running. */
        fun onFrame() {}

        fun onError(error: Throwable)
    }

    companion object {
        private const val TAG = "LiveStreamEncoder"

        const val WIDTH = 640
        const val HEIGHT = 480
        const val FPS = 15
        const val BITRATE = 800_000
        const val FRAGMENT_DURATION_MS = 1_000L

        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    }

    private val running = AtomicBoolean(false)
    private val lock = Any()
    private val drainExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var codec: MediaCodec? = null
    private var muxer: FragmentedMp4Muxer? = null
    private var fragmentStream: FragmentingOutputStream? = null
    private var trackIndex = -1

    private var width = 0
    private var height = 0
    private var i420 = ByteArray(0)
    private var rowScratch = ByteArray(0)

    /** The row stride and the height the codec wants for one frame. */
    private var stride = 0
    private var sliceHeight = 0

    /** The encoder that was picked, and the colour format it takes. */
    private var encoderName: String? = null
    private var colorFormat = 0

    /** True when the codec wants the chroma planes woven together (NV12). */
    private var semiPlanar = false

    /** True once the init segment has been produced. */
    @Volatile
    var hasInitSegment: Boolean = false
        private set

    /** Whether a key frame has been seen - media3 drops everything else. */
    private var sawKeyFrame = false

    /** The frame counter that makes the timeline of the live feed. */
    private var frameIndex = 0L

    /** How many key frames the encoder produced. */
    @Volatile
    var keyFrames: Int = 0
        private set

    /**
     * The average brightness of the last frame.
     *
     * A value of zero means the camera delivers black pictures - which would
     * be invisible in the live feed, so it is worth showing.
     */
    @Volatile
    var averageLuma: Int = -1
        private set

    /**
     * Starts the encoder.
     *
     * The codec itself is created with the first frame, because its size has
     * to match what the camera delivers.
     */
    fun start() {
        running.set(true)
    }

    /**
     * Feeds one camera frame into the encoder. The encoder is created with the
     * real frame size the first time, so it always matches the camera.
     */
    fun submitFrame(image: ImageProxy) {
        if (!running.get()) return
        try {
            if (codec == null) configure(image.width, image.height)
            val surface = inputSurface ?: return
            // The camera hands over more frames than the live feed needs, and
            // turning every frame into a picture is expensive - so only the
            // frames the encoder really wants are passed on.
            val now = SystemClock.elapsedRealtime()
            if (now - lastFedAt < 1000L / FPS) return
            lastFedAt = now
            // The picture is drawn into the encoder's surface. The camera
            // hands over YUV, and CameraX knows how to turn that into a
            // picture.
            val bitmap = runCatching { image.toBitmap() }.getOrNull() ?: return
            val canvas = lockCanvas(surface) ?: return
            try {
                canvas.drawBitmap(bitmap, 0f, 0f, null)
            } finally {
                runCatching { surface.unlockCanvasAndPost(canvas) }
            }
            measureLuma(bitmap)
            frameIndex++
            callback.onFrame()
        } catch (e: Exception) {
            Log.w(TAG, "submitFrame failed: ${e.message}")
            reportOnce("the picture could not be encoded: ${e.message}")
        }
    }

    /** When the last picture was handed to the encoder. */
    private var lastFedAt = 0L

    /**
     * Locks the surface of the encoder.
     *
     * A surface of the encoder only works with the hardware canvas - the
     * plain one is not allowed on it.
     */
    private fun lockCanvas(surface: Surface): Canvas? {
        return try {
            surface.lockHardwareCanvas()
        } catch (e: Exception) {
            Log.w(TAG, "the hardware canvas is not available: ${e.message}")
            runCatching { surface.lockCanvas(null) }.getOrNull()
        }
    }

    /** The average brightness of the picture that was just encoded. */
    private fun measureLuma(bitmap: Bitmap) {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        var sum = 0L
        var i = 0
        while (i < pixels.size) {
            val p = pixels[i]
            sum += ((p shr 16 and 0xFF) + (p shr 8 and 0xFF) + (p and 0xFF)) / 3
            i += 16
        }
        averageLuma = (sum / (pixels.size / 16 + 1)).toInt()
    }


    /**
     * Locks the surface of the encoder.
     *
     * A surface of the encoder only works with the hardware canvas - the
     * plain one is not allowed on it.
     */

    /** The average brightness of the picture that was just encoded. */

    /** Reports a problem once, so the dashboard does not repeat it. */
    private var reportedProblem: String? = null

    private fun reportOnce(message: String) {
        if (reportedProblem == message) return
        reportedProblem = message
        callback.onError(IllegalStateException(message))
    }

    private fun configure(frameWidth: Int, frameHeight: Int) {
        width = frameWidth
        height = frameHeight
        i420 = ByteArray(width * height * 3 / 2)
        rowScratch = ByteArray(width)

        // The encoder is picked by hand, because
        // `createEncoderByType` happily returns a codec that cannot be fed
        // with images at all.
        val choice = pickEncoder()
        if (choice == null) {
            val message = "this phone has no H.264 encoder"
            Log.e(TAG, message)
            callback.onError(IllegalStateException(message))
            return
        }
        val (info, chosenFormat) = choice
        encoderName = info.name
        colorFormat = chosenFormat

        val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            // No B frames: they would be stored in the order they are decoded,
            // and the run of samples carries no information about that - the
            // player would show the pictures in the wrong order and the
            // reference pictures would be missing.
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        }

        try {
            val encoder = MediaCodec.createByCodecName(info.name)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            // The picture goes into the encoder through a surface - the way
            // every camera app does it. Handing the raw YUV planes to the codec
            // instead depends on a colour format the codec only pretends to
            // understand, and then the picture comes out black.
            val surface = encoder.createInputSurface()
            encoder.start()
            codec = encoder
            inputSurface = surface
            val inputFormat = runCatching { encoder.inputFormat }.getOrNull()
            stride = inputFormat?.getInteger(MediaFormat.KEY_STRIDE, width) ?: width
            sliceHeight = inputFormat?.getInteger(MediaFormat.KEY_SLICE_HEIGHT, height) ?: height
        } catch (e: Exception) {
            Log.e(TAG, "the encoder refused the format: ${e.message}", e)
            callback.onError(e)
            return
        }

        trackIndex = -1
        hasInitSegment = false
        sawKeyFrame = false
        frameIndex = 0L
        keyFrames = 0
        running.set(true)
        createMuxer()
        drainExecutor.execute { drainLoop() }
        Log.w(TAG, "encoder started ($encoderName, colour $chosenFormat, ${width}x$height@$FPS)")
    }

    /** The surface the picture is drawn into. */
    private var inputSurface: Surface? = null

    /**
     * Finds an H.264 encoder that takes image buffers.
     *
     * The order matters: planar YUV is what the conversion produces, the
     * flexible format accepts the same layout, and semi planar needs the
     * chroma planes woven together.
     */
    private fun pickEncoder(): Pair<MediaCodecInfo, Int>? {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val preferred = intArrayOf(
            // The layout most hardware encoders really want: the chroma
            // planes woven together. An encoder that gets a layout it only
            // pretends to understand produces a black picture without saying
            // anything.
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
        )
        for (info in list.codecInfos) {
            if (!info.isEncoder) continue
            for (type in info.supportedTypes) {
                if (!type.equals(MIME, ignoreCase = true)) continue
                val formats = runCatching {
                    info.getCapabilitiesForType(type).colorFormats
                }.getOrNull() ?: continue
                for (candidate in preferred) {
                    if (formats.contains(candidate)) return info to candidate
                }
            }
        }
        return null
    }

    /** The average brightness of the picture that was just converted. */

    /** The encoder and its layout - shown on the dashboard. */
    fun describe(): String =
        "${encoderName ?: "none"} colour=$colorFormat ${width}x$height" +
            " boxes=${fragmentStream?.parsedBoxes ?: 0}" +
            " frags=${fragmentStream?.publishedFragments ?: 0}" +
            " key=$keyFrames" +
            " luma=$averageLuma" +
            " stride=$stride/$sliceHeight"

    private fun createMuxer() {
        try {
            val stream = FragmentingOutputStream(
                onInit = { bytes ->
                    hasInitSegment = true
                    callback.onInitSegment(bytes)
                },
                onFragment = { sequence, bytes, duration ->
                    callback.onFragment(sequence, bytes, duration, sessionId)
                }
            )
            fragmentStream = stream
            // The channel is used on purpose: the OutputStream constructor of
            // media3 makes a seekable muxer, and that one holds the fragments
            // back until the file is closed - which never happens while the
            // stream is running.
            muxer = FragmentedMp4Muxer.Builder(StreamChannel(stream))
                .setFragmentDurationMs(FRAGMENT_DURATION_MS)
                // The codec reuses its output buffers, so the samples have to
                // be copied before they are handed over.
                .setSampleCopyingEnabled(true)
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "muxer creation failed: ${e.message}")
            callback.onError(e)
        }
    }

    /** Feeds the muxer without asking it to be seekable. */
    private class StreamChannel(private val stream: OutputStream) : WritableByteChannel {
        private var open = true

        override fun isOpen(): Boolean = open

        override fun close() {
            open = false
        }

        override fun write(src: ByteBuffer): Int {
            val length = src.remaining()
            val bytes = ByteArray(length)
            src.get(bytes)
            stream.write(bytes)
            return length
        }
    }

    private fun drainLoop() {
        val codecInfo = MediaCodec.BufferInfo()
        while (running.get()) {
            val encoder = codec ?: break
            try {
                var index = encoder.dequeueOutputBuffer(codecInfo, 10_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val format = buildTrackFormat(encoder.outputFormat)
                    if (format != null) {
                        muxer?.let { trackIndex = it.addTrack(format) }
                        Log.w(TAG, "encoder track added (${format.width}x${format.height})")
                    }
                    index = encoder.dequeueOutputBuffer(codecInfo, 10_000)
                }
                while (index >= 0) {
                    val buffer = encoder.getOutputBuffer(index)
                    if (buffer != null && codecInfo.size > 0 && trackIndex >= 0) {
                        try {
                            buffer.position(codecInfo.offset)
                            buffer.limit(codecInfo.offset + codecInfo.size)
                            // The very first sample has to be a key frame:
                            // media3 throws away every video sample until one
                            // arrives, and then the feed would stay empty.
                            var flags = codecInfo.flags
                            if (!sawKeyFrame) {
                                flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
                            }
                            if ((flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                                sawKeyFrame = true
                                keyFrames++
                            }
                            // media3's BufferInfo is immutable, so a new one is
                            // needed for every sample.
                            val muxInfo = androidx.media3.muxer.BufferInfo(
                                codecInfo.presentationTimeUs,
                                codecInfo.size,
                                flags
                            )
                            muxer?.writeSampleData(trackIndex, buffer, muxInfo)
                        } catch (e: Exception) {
                            // One bad sample must not kill the whole feed.
                            Log.w(TAG, "a sample could not be written: ${e.message}")
                            reportOnce("a frame could not be muxed: ${e.message}")
                        }
                    }
                    encoder.releaseOutputBuffer(index, false)
                    index = encoder.dequeueOutputBuffer(codecInfo, 10_000)
                }
            } catch (e: Exception) {
                if (running.get()) {
                    Log.w(TAG, "encoder drain failed: ${e.message}")
                    callback.onError(e)
                }
                break
            }
        }
    }

    /** Converts YUV_420_888 into the layout the encoder asked for. */

    /**
     * Builds a media3 [Format] from the encoder output.
     *
     * media3 wants the SPS and the PPS as two entries in the initialization
     * data, each one in Annex-B form: a start code followed by the raw NAL
     * unit. Android delivers them in its own "csd" format, so they are
     * unpacked here.
     */
    private fun buildTrackFormat(outputFormat: MediaFormat): Format? {
        val trackWidth = outputFormat.getInteger(MediaFormat.KEY_WIDTH, width)
        val trackHeight = outputFormat.getInteger(MediaFormat.KEY_HEIGHT, height)

        val csd0 = outputFormat.getByteBuffer("csd-0")?.let { readAll(it) }
        val csd1 = outputFormat.getByteBuffer("csd-1")?.let { readAll(it) }

        val split = AvcCodecConfig.split(csd0, csd1)
        if (split == null) {
            val message = "the encoder did not provide a usable SPS and PPS"
            Log.e(TAG, message)
            callback.onError(IllegalStateException(message))
            return null
        }

        return Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setWidth(trackWidth)
            .setHeight(trackHeight)
            // The frame rate is the time base of the track: with it every
            // frame lasts exactly one unit.
            .setFrameRate(FPS.toFloat())
            .setInitializationData(
                listOf(
                    AvcCodecConfig.withStartCode(split.first),
                    AvcCodecConfig.withStartCode(split.second)
                )
            )
            .build()
    }

    private fun readAll(buffer: ByteBuffer): ByteArray {
        val data = ByteArray(buffer.remaining())
        buffer.get(data)
        return data
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        runCatching { muxer?.close() }
        muxer = null
        runCatching { fragmentStream?.flush() }
        fragmentStream = null
        trackIndex = -1
        hasInitSegment = false
    }
}
