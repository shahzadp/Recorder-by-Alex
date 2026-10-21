package com.alex.cam

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import androidx.camera.core.ImageProxy
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.muxer.FragmentedMp4Muxer
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
 *        -> H264Encoder: the planes of the frame go into a hardware H.264
 *           encoder as real YUV
 *        -> media3 FragmentedMp4Muxer writes fMP4 into a FragmentingOutputStream
 *        -> that stream splits it into an init segment (ftyp+moov) and a series
 *           of media fragments (styp+moof+mdat)
 *
 * The server then serves /init.mp4, /seg-<n>.m4s and the rolling /live.m3u8.
 *
 * The frames used to be drawn into the encoder's input surface as a picture.
 * That does not work: a hardware encoder reads YUV, not a canvas, so it
 * produced an empty video track and the dashboard showed black. The encoder
 * now takes the planes of the camera frame directly - see [H264Encoder].
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
    private val drainExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var encoder: H264Encoder? = null
    private var muxer: FragmentedMp4Muxer? = null
    private var fragmentStream: FragmentingOutputStream? = null
    private var trackIndex = -1

    /** The frame the encoder was created for. */
    private var frameWidth = 0
    private var frameHeight = 0

    /** True once the init segment has been produced. */
    @Volatile
    var hasInitSegment: Boolean = false
        private set

    /** The frame counter that makes the timeline of the live feed. */
    private var frameIndex = 0L

    /**
     * The time of the last frame, in microseconds.
     *
     * The camera's own timestamps jump on this phone, and one jump makes a
     * sample duration too big for the 32 bit field of the MP4 - so the
     * timeline is built here, one frame after the other.
     */
    private var lastPtsUs = -1L

    /**
     * The average brightness of the last frame.
     *
     * A value of zero means the camera delivers black pictures - which would
     * be invisible in the live feed, so it is worth showing.
     */
    @Volatile
    var averageLuma: Int = -1
        private set

    /** How many frames the camera handed over. */
    @Volatile
    var submittedFrames: Int = 0
        private set

    /** How many frames were dropped because the encoder was busy. */
    @Volatile
    var droppedFrames: Int = 0
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
     * Feeds one camera frame into the encoder. The encoder is created with
     * the real frame size the first time, so it always matches the camera.
     */
    fun submitFrame(image: ImageProxy) {
        if (!running.get()) return
        submittedFrames++
        try {
            if (encoder == null) {
                if (!createEncoder(image.width, image.height)) return
            }
            val active = encoder ?: return

            // The camera hands over more frames than the live feed needs, and
            // turning every frame into a picture is expensive - so only the
            // frames the encoder really wants are passed on.
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastFedAt < 1000L / FPS) {
                droppedFrames++
                return
            }
            lastFedAt = now

            val ptsUs = nextTimestamp()
            if (!active.submit(image, ptsUs)) {
                droppedFrames++
                return
            }
            measureLuma(image)
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
     * The time of the next frame.
     *
     * The frames are one step of the frame rate apart, so every sample gets
     * the same length and the player sees a steady picture.
     */
    private fun nextTimestamp(): Long {
        val step = 1_000_000L / FPS
        if (lastPtsUs < 0) lastPtsUs = 0
        val next = lastPtsUs + step
        lastPtsUs = next
        return next
    }

    /** The average brightness of the picture that was just encoded. */
    private fun measureLuma(image: ImageProxy) {
        try {
            val plane = image.planes.getOrNull(0) ?: return
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val step = (image.width * image.height) / 4096
            if (step < 1) return
            var sum = 0L
            var count = 0
            var index = 0
            while (index < buffer.limit() && count < 4096) {
                sum += buffer.get(index).toInt() and 0xFF
                count++
                // The luma plane is the first one, so the samples of the
                // chroma are simply not reached.
                index += step
            }
            if (count > 0) averageLuma = (sum / count).toInt()
        } catch (e: Exception) {
            // The brightness is only a hint for the dashboard.
        }
    }

    /** Reports a problem once, so the dashboard does not repeat it. */
    private var reportedProblem: String? = null

    private fun reportOnce(message: String) {
        if (reportedProblem == message) return
        reportedProblem = message
        callback.onError(IllegalStateException(message))
    }

    /**
     * Creates the encoder for the size the camera delivers.
     *
     * The encoder is created for the real frame size, so the planes of the
     * camera and the layout of the encoder always fit together.
     */
    private fun createEncoder(width: Int, height: Int): Boolean {
        frameWidth = width
        frameHeight = height
        val active = H264Encoder(width, height, FPS, BITRATE)
        if (!active.start()) {
            val message = "this phone has no H.264 encoder for ${width}x$height"
            Log.e(TAG, message)
            callback.onError(IllegalStateException(message))
            return false
        }
        encoder = active
        trackIndex = -1
        hasInitSegment = false
        frameIndex = 0L
        lastPtsUs = -1L
        running.set(true)
        createMuxer()
        drainExecutor.execute { drainLoop() }
        return true
    }

    /** The encoder and its layout - shown on the dashboard. */
    fun describe(): String =
        "${encoder?.encoderName ?: "none"} colour=${encoder?.colorFormat ?: 0} " +
            "${frameWidth}x$frameHeight" +
            " boxes=${fragmentStream?.parsedBoxes ?: 0}" +
            " frags=${fragmentStream?.publishedFragments ?: 0}" +
            " key=${encoder?.keyFrames ?: 0}" +
            " luma=$averageLuma" +
            " sent=$submittedFrames dropped=$droppedFrames"

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
            val active = encoder ?: break
            try {
                var index = active.dequeueOutput(codecInfo, 10_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val format = buildTrackFormat(active.outputFormat)
                    if (format != null) {
                        muxer?.let { trackIndex = it.addTrack(format) }
                        Log.w(TAG, "encoder track added (${format.width}x${format.height})")
                    }
                    index = active.dequeueOutput(codecInfo, 10_000)
                }
                while (index >= 0) {
                    val buffer = active.outputBuffer(index)
                    if (buffer != null && codecInfo.size > 0 && trackIndex >= 0) {
                        try {
                            buffer.position(codecInfo.offset)
                            buffer.limit(codecInfo.offset + codecInfo.size)
                            // media3's BufferInfo is immutable, so a new one
                            // is needed for every sample.
                            val muxInfo = androidx.media3.muxer.BufferInfo(
                                codecInfo.presentationTimeUs,
                                codecInfo.size,
                                codecInfo.flags
                            )
                            muxer?.writeSampleData(trackIndex, buffer, muxInfo)
                        } catch (e: Exception) {
                            // One bad sample must not kill the whole feed.
                            Log.w(TAG, "a sample could not be written: ${e.message}")
                            reportOnce("a frame could not be muxed: ${e.message}")
                        }
                    }
                    active.releaseOutput(index)
                    index = active.dequeueOutput(codecInfo, 10_000)
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

    /**
     * Builds a media3 [Format] from the encoder output.
     *
     * media3 wants the SPS and the PPS as two entries in the initialization
     * data, each one in Annex-B form: a start code followed by the raw NAL
     * unit. Android delivers them in its own "csd" format, so they are
     * unpacked here.
     */
    private fun buildTrackFormat(outputFormat: MediaFormat?): Format? {
        if (outputFormat == null) return null
        val format = AvcCodecConfig.buildVideoFormat(outputFormat, MimeTypes.VIDEO_H264)
        if (format == null) {
            val message = "the encoder did not provide a usable SPS and PPS"
            Log.e(TAG, message)
            callback.onError(IllegalStateException(message))
            return null
        }
        return format
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { encoder?.stop() }
        encoder = null
        runCatching { muxer?.close() }
        muxer = null
        runCatching { fragmentStream?.flush() }
        fragmentStream = null
        trackIndex = -1
        hasInitSegment = false
    }
}
