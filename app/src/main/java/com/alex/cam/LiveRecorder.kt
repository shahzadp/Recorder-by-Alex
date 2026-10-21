package com.alex.cam

import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.camera.core.ImageProxy
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.muxer.FragmentedMp4Muxer
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.WritableByteChannel
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Records the camera and writes the file and the live feed from **the same
 * pictures**.
 *
 * The phone's camera can only serve one consumer at a time, so the live feed
 * and the recording have to come out of one encoder:
 *
 *   camera -> ImageAnalysis -> MediaCodec (H.264) -> muxer -+-> the file
 *                                                            +-> HLS fragments
 *
 * The audio is recorded at the same time and muxed in as a second track, so
 * the file and the live feed both have the sound.
 */
internal class LiveRecorder(
    private val videoMime: String = MimeTypes.VIDEO_H264,
    private val audioMime: String = MimeTypes.AUDIO_AAC,
    private val onInitSegment: (ByteArray) -> Unit,
    private val onFragment: (Int, ByteArray, Long) -> Unit
) {

    companion object {
        private const val TAG = "LiveRecorder"
        private const val VIDEO_WIDTH = 640
        private const val VIDEO_HEIGHT = 480
        private const val VIDEO_BITRATE = 1_500_000
        private const val VIDEO_FPS = 15
        private const val AUDIO_SAMPLE_RATE = 44_100
        private const val AUDIO_BITRATE = 96_000
        private const val AUDIO_CHANNELS = 1
    }

    /** The muxer writes here - the file and the live feed both get the bytes. */
    private var fileOutput: OutputStream? = null

    private val running = AtomicBoolean(false)
    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var muxer: FragmentedMp4Muxer? = null
    private var videoTrack = -1
    private var audioTrack = -1
    private var videoFrame = 0L
    private var lastFedAt = 0L
    private var audioSamples = 0L
    private val drainExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** The time base of the video track. */
    private val timescale = 90_000L

    /**
     * Starts the recorder.
     *
     * @param output where the finished file goes
     */
    fun start(output: OutputStream): Boolean {
        if (running.get()) return true
        return try {
            fileOutput = output
            createVideoEncoder()
            createAudioEncoder()
            createMuxer()
            running.set(true)
            startDrain()
            Log.w(TAG, "the recorder started")
            true
        } catch (e: Exception) {
            Log.e(TAG, "the recorder could not start", e)
            release()
            false
        }
    }

    // ------------------------------------------------------------------ video

    private fun createVideoEncoder() {
        val format = MediaFormat.createVideoFormat(videoMime, VIDEO_WIDTH, VIDEO_HEIGHT).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            // Without B frames the pictures come out in the order they were
            // taken, which is what a player expects.
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        }
        val encoder = MediaCodec.createEncoderByType(videoMime)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = encoder.createInputSurface()
        encoder.start()
        videoCodec = encoder
        inputSurface = surface
    }

    private fun createAudioEncoder() {
        val format = MediaFormat.createAudioFormat(audioMime, AUDIO_SAMPLE_RATE, AUDIO_CHANNELS).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        val encoder = MediaCodec.createEncoderByType(audioMime)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        audioCodec = encoder
    }

    /**
     * Hands one camera frame to the encoder.
     *
     * The picture is drawn into the encoder's surface - the way every camera
     * app does it.
     */
    fun submitFrame(image: ImageProxy, rotation: Int) {
        if (!running.get()) return
        val surface = inputSurface ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastFedAt < 1000L / VIDEO_FPS) return
        lastFedAt = now
        try {
            val bitmap: Bitmap = image.toBitmap() ?: return
            val canvas = lockCanvas(surface) ?: return
            try {
                if (rotation != 0) {
                    canvas.save()
                    canvas.translate(bitmap.width / 2f, bitmap.height / 2f)
                    canvas.rotate(rotation.toFloat())
                    canvas.translate(-bitmap.width / 2f, -bitmap.height / 2f)
                }
                canvas.drawBitmap(bitmap, 0f, 0f, null)
                if (rotation != 0) canvas.restore()
            } finally {
                runCatching { surface.unlockCanvasAndPost(canvas) }
            }
            videoFrame++
        } catch (e: Exception) {
            Log.w(TAG, "a frame could not be encoded: ${e.message}")
        }
    }

    private fun lockCanvas(surface: Surface): Canvas? = try {
        surface.lockHardwareCanvas()
    } catch (e: Exception) {
        runCatching { surface.lockCanvas(null) }.getOrNull()
    }

    // ------------------------------------------------------------------ audio

    /**
     * Hands the recorded sound to the encoder.
     *
     * The time of the samples is counted here, so that the sound starts at
     * zero like the pictures do.
     */
    fun submitAudio(data: ByteArray) {
        if (!running.get()) return
        val encoder = audioCodec ?: return
        try {
            var offset = 0
            while (offset < data.size) {
                val index = encoder.dequeueInputBuffer(10_000)
                if (index < 0) return
                val buffer = encoder.getInputBuffer(index) ?: return
                val chunk = minOf(buffer.remaining(), data.size - offset)
                buffer.put(data, offset, chunk)
                val ptsUs = audioSamples * 1_000_000L / AUDIO_SAMPLE_RATE
                encoder.queueInputBuffer(index, 0, chunk, ptsUs, 0)
                audioSamples += chunk / 2
                offset += chunk
            }
        } catch (e: Exception) {
            Log.w(TAG, "the sound could not be encoded: ${e.message}")
        }
    }

    // ------------------------------------------------------------------- muxing

    private fun createMuxer() {
        val channel = TeeChannel(
            onBytes = { bytes ->
                // The live feed is fed from the very same bytes.
                runCatching { onRawBytes?.invoke(bytes) }
            }
        )
        muxer = FragmentedMp4Muxer.Builder(channel)
            .setFragmentDurationMs(1000)
            .setSampleCopyingEnabled(true)
            .build()
    }

    /** Receives every byte the muxer writes. */
    var onRawBytes: ((ByteArray) -> Unit)? = null

    /**
     * Writes the bytes to the file and passes them on.
     *
     * The file and the live feed are the same stream - that is the whole
     * point: the feed shows what is being recorded.
     */
    private inner class TeeChannel(
        private val onBytes: (ByteArray) -> Unit
    ) : WritableByteChannel {
        private var open = true

        override fun isOpen(): Boolean = open

        override fun close() {
            open = false
        }

        override fun write(src: ByteBuffer): Int {
            val length = src.remaining()
            val bytes = ByteArray(length)
            src.get(bytes)
            runCatching { fileOutput?.write(bytes) }
            onBytes(bytes)
            return length
        }
    }

    /**
     * Adds the track of the video.
     *
     * The picture format is filled in by the encoder.
     */
    fun addVideoTrack(format: Format): Int {
        val track = muxer?.addTrack(format) ?: -1
        videoTrack = track
        return track
    }

    /** Adds the track of the sound. */
    fun addAudioTrack(format: Format): Int {
        val track = muxer?.addTrack(format) ?: -1
        audioTrack = track
        return track
    }

    /**
     * Takes the encoded samples out of the two codecs and writes them into the
     * muxer - and from there into the file and the live feed.
     */
    private fun startDrain() {
        drainExecutor.execute {
            val info = MediaCodec.BufferInfo()
            while (running.get()) {
                val video = videoCodec
                if (video != null) {
                    try {
                        val index = video.dequeueOutputBuffer(info, 10_000)
                        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            addVideoTrack(
                                AvcCodecConfig.buildVideoFormat(video.outputFormat) ?: Format.Builder()
                                    .setSampleMimeType(videoMime)
                                    .setWidth(VIDEO_WIDTH)
                                    .setHeight(VIDEO_HEIGHT)
                                    .build()
                            )
                        } else if (index >= 0) {
                            val buffer = video.getOutputBuffer(index)
                            if (buffer != null && info.size > 0) {
                                if (videoTrack < 0) {
                                    addVideoTrack(
                                        AvcCodecConfig.buildVideoFormat(video.outputFormat) ?: Format.Builder()
                                            .setSampleMimeType(videoMime)
                                            .setWidth(VIDEO_WIDTH)
                                            .setHeight(VIDEO_HEIGHT)
                                            .build()
                                    )
                                }
                                writeSample(videoTrack, buffer, info)
                            }
                            video.releaseOutputBuffer(index, false)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "the video output failed: ${e.message}")
                    }
                }

                val audio = audioCodec
                if (audio != null) {
                    try {
                        val index = audio.dequeueOutputBuffer(info, 10_000)
                        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            addAudioTrack(
                                Format.Builder()
                                    .setSampleMimeType(audioMime)
                                    .setSampleRate(AUDIO_SAMPLE_RATE)
                                    .setChannelCount(AUDIO_CHANNELS)
                                    .setInitializationData(
                                        listOf(
                                            audio.outputFormat.getByteBuffer("csd-0")?.let { readAll(it) }
                                                ?: ByteArray(0)
                                        )
                                    )
                                    .build()
                            )
                        } else if (index >= 0) {
                            val buffer = audio.getOutputBuffer(index)
                            if (buffer != null && info.size > 0) {
                                if (audioTrack < 0) {
                                    addAudioTrack(
                                        Format.Builder()
                                            .setSampleMimeType(audioMime)
                                            .setSampleRate(AUDIO_SAMPLE_RATE)
                                            .setChannelCount(AUDIO_CHANNELS)
                                            .build()
                                    )
                                }
                                writeSample(audioTrack, buffer, info)
                            }
                            audio.releaseOutputBuffer(index, false)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "the sound output failed: ${e.message}")
                    }
                }
            }
        }
    }

    private fun readAll(buffer: java.nio.ByteBuffer): ByteArray {
        val data = ByteArray(buffer.remaining())
        buffer.get(data)
        return data
    }

    /** Writes one encoded picture into the file and the feed. */
    fun writeVideoSample(data: ByteBuffer, info: MediaCodec.BufferInfo) {
        writeSample(videoTrack, data, info)
    }

    /** Writes one encoded sound sample into the file and the feed. */
    fun writeAudioSample(data: ByteBuffer, info: MediaCodec.BufferInfo) {
        writeSample(audioTrack, data, info)
    }

    private fun writeSample(track: Int, data: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (track < 0) return
        try {
            val copy = data.duplicate()
            copy.position(info.offset)
            copy.limit(info.offset + info.size)
            val bufferInfo = androidx.media3.muxer.BufferInfo(
                info.presentationTimeUs,
                info.size,
                info.flags
            )
            muxer?.writeSampleData(track, copy, bufferInfo)
        } catch (e: Exception) {
            Log.w(TAG, "a sample could not be written: ${e.message}")
        }
    }

    /** Finishes the recording. */
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { muxer?.close() }
        runCatching { videoCodec?.stop() }
        runCatching { videoCodec?.release() }
        runCatching { audioCodec?.stop() }
        runCatching { audioCodec?.release() }
        runCatching { inputSurface?.release() }
        runCatching { fileOutput?.flush() }
        muxer = null
        videoCodec = null
        audioCodec = null
        inputSurface = null
        fileOutput = null
        Log.w(TAG, "the recorder stopped")
    }

    private fun release() {
        running.set(false)
        runCatching { videoCodec?.release() }
        runCatching { audioCodec?.release() }
        runCatching { inputSurface?.release() }
        videoCodec = null
        audioCodec = null
        inputSurface = null
    }
}
