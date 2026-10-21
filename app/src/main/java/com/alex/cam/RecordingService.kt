package com.alex.cam

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Display
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Owns capture (audio or video) and the recording session.
 *
 * The original app kept CameraX bound to the Activity's lifecycle, so recording
 * stopped the moment the app left the foreground or the screen turned off. Here
 * capture is bound to this service's own [LifecycleRegistry] and the service runs
 * as a `camera|microphone` foreground service, so recording continues while the
 * Activity is stopped, the display is off, or the task is gone.
 *
 * Two modes share one session model (elapsed time, pause/resume, output naming):
 *  - [Mode.AUDIO] (default): microphone only, MediaRecorder -> public
 *    Music/Recorder/Audio  (m4a files). No camera is opened.
 *  - [Mode.VIDEO]: CameraX -> public Movies/Recorder/Camera  (per-lens mp4 files).
 */
class RecordingService : Service(), LifecycleOwner {

    enum class Mode { AUDIO, VIDEO }

    companion object {
        private const val TAG = "RecordingService"

        /** Port for the live dashboard. */
        private const val LIVE_PORT = 8080

        const val ACTION_START = "com.alex.cam.action.START"
        const val ACTION_START_RECORDING = "com.alex.cam.action.START_RECORDING"
        const val ACTION_START_STREAM = "com.alex.cam.action.START_STREAM"
        const val ACTION_STOP_RECORDING = "com.alex.cam.action.STOP_RECORDING"
        const val ACTION_TOGGLE_PAUSE = "com.alex.cam.action.TOGGLE_PAUSE"

        private const val CHANNEL_ID = "recorder_clone_recording"
        private const val NOTIF_ID = 1001
        private const val WAKE_LOCK_TIMEOUT_MS = 12L * 60L * 60L * 1000L

        /** How often the foreground service is renewed while the stream runs. */
        private const val KEEP_ALIVE_INTERVAL_MS = 30_000L

        /** How often a refused camera binding is tried again. */
        private const val BIND_RETRIES = 4
        private const val BIND_RETRY_DELAY_MS = 400L

        /** How often a video recording that waits for the camera is retried. */
        private const val START_RETRIES = 10
        private const val START_RETRY_DELAY_MS = 500L
        private const val ZOOM_STEP = 0.1f

        /**
         * How long the live feed may be without a frame before the dashboard
         * is told that it is paused.
         */
        private const val FEED_STALL_MS = 2_000L
    }

    /** UI-facing handles. All calls happen on the main thread. */
    interface Listener {
        fun onRecordingStateChanged(recording: Boolean, paused: Boolean)
        fun onPreviewReady(preview: Preview)
    }

    inner class LocalBinder : Binder() {
        fun service(): RecordingService = this@RecordingService
    }

    private val binder = LocalBinder()
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    // Camera / video
    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var preview: Preview? = null
    private var camera: Camera? = null
    private var videoRecording: Recording? = null
    private var cameraBound = false

    // Audio
    private var audioRecorder: MediaRecorder? = null
    private var audioUri: Uri? = null
    private var audioPfd: ParcelFileDescriptor? = null

    private var mode = Mode.AUDIO

    /** The kind of the recording that is running right now (null when idle). */
    @Volatile
    private var activeRecordingMode: Mode? = null

    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private val quality: Quality = Quality.HD
    private var linearZoom = 0f

    // Timing: elapsedRealtime is monotonic, so it is immune to wall-clock changes.
    private var recordStartRealtime = 0L
    private var pausedAtRealtime = 0L
    private var totalPausedMs = 0L
    private var paused = false

    /** True between the tap and the moment capture is really running. */
    @Volatile
    private var starting = false

    private var boundClients = 0
    private var listener: Listener? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /** True while the service shows its foreground notification. */
    @Volatile
    private var isForeground = false

    /** The foreground service type that was applied last. */
    private var lastForegroundType = 0

    private val serviceStartMs = System.currentTimeMillis()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val streamExecutor = Executors.newSingleThreadExecutor()

    /**
     * Runs the recording commands that arrive from the dashboard.
     *
     * It is separate from the stream executor, which is busy with the camera
     * frames, and separate from the main thread, which can be busy with the
     * camera as well.
     */
    private val controlExecutor = Executors.newSingleThreadExecutor()

    // Live stream: the dashboard, the HLS feed and the remote recording
    // control.
    private var liveServer: LiveStreamServer? = null
    private var liveEndpoints: LiveStreamEndpoints? = null
    private var streamEncoder: LiveStreamEncoder? = null
    private var imageAnalysis: ImageAnalysis? = null
    private val hlsBuffer = HlsStreamBuffer()
    @Volatile
    private var streamEnabled = false
    @Volatile
    private var streamUnavailable = false
    @Volatile
    private var serverError: String? = null
    /** How many times the live encoder was started - one number per session. */
    @Volatile
    private var streamSession = 0

    /** The number of the encoder session that is running. */
    @Volatile
    private var initSegmentId = 0

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        mode = Mode.AUDIO
        createNotificationChannel()
        // Warm up the camera provider in the background so switching to Video
        // mode does not have to wait for it to initialise.
        ProcessCameraProvider.getInstance(this)
        // The live stream outlives the app: if it was on, it is started again
        // here. Without this the dashboard would stay dead after the system
        // killed the app, until the user opened it again.
        val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        // If the app died before, say so - a crash is otherwise invisible
        // from the outside.
        RecorderApp.lastCrash(this)?.let { crash ->
            Log.e(TAG, "the app crashed before: $crash")
            lastError = "the app crashed: $crash"
        }
        if (prefs.getBoolean(Constants.PREF_STREAMING_ENABLED, false)) {
            streamEnabled = true
            val started = startLiveServer()
            startStreamEncoder()
            goForeground()
            acquireWakeLock()
            mainHandler.removeCallbacks(keepAlive)
            mainHandler.post(keepAlive)
            Log.w(TAG, "live stream restored (server started=$started)")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                ACTION_START_RECORDING -> {
                    // The service becomes visible only while a recording runs.
                    goForeground()
                    acquireWakeLock()
                    startRecording()
                }
                ACTION_STOP_RECORDING -> {
                    stopRecording()
                    maybeStopIfIdle()
                }
                ACTION_TOGGLE_PAUSE -> {
                    if (paused) resumeRecording() else pauseRecording()
                    maybeStopIfIdle()
                }
                ACTION_START_STREAM -> {
                    // Started only so the live stream keeps running when the app is
                    // left. The stream itself is video only.
                    acquireWakeLock()
                    if (streamEnabled) goForeground()
                }
                else -> {
                    // Started without a recording request (or restarted by the
                    // system after it was killed): stay invisible, unless the
                    // live stream is supposed to be running.
                    acquireWakeLock()
                    if (streamEnabled) {
                        startLiveServer()
                        startStreamEncoder()
                        goForeground()
                    }
                    if (mode == Mode.VIDEO) ensureCamera()
                }
            }
        } catch (t: Throwable) {
            // A crash here would take the live stream (and with it the
            // dashboard) down, so it is logged instead.
            Log.e(TAG, "onStartCommand failed", t)
            lastError = "the service could not handle the request: ${t.message}"
        }
        // Sticky: when the system kills the service (it happens on some
        // phones when the app sits in the background for a while), it is
        // started again - and the live stream comes back with it.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        boundClients++
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        if (mode == Mode.VIDEO) ensureCamera()
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        boundClients = (boundClients - 1).coerceAtLeast(0)
        listener = null
        maybeStopIfIdle()
        return true
    }

    override fun onRebind(intent: Intent?) {
        super.onRebind(intent)
        boundClients++
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    override fun onDestroy() {
        if (videoRecording != null) runCatching { videoRecording?.stop() }
        videoRecording = null
        releaseAudioRecorder(finalize = true)
        leaveForeground()
        runCatching { cameraProvider?.unbindAll() }
        camera = null
        cameraBound = false
        releaseWakeLock()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        Log.w(TAG, "service destroyed")
        super.onDestroy()
    }

    // --------------------------------------------------------------------- mode

    fun mode(): Mode = mode

    /** Switch capture mode. Ignored while a recording is in progress. */
    fun setMode(newMode: Mode) {
        if (isRecording() || newMode == mode) return
        mode = newMode
        if (newMode == Mode.VIDEO) {
            ensureCamera()
            preview?.let { listener?.onPreviewReady(it) }
            // The camera is available now, so the live feed can start.
            if (streamEnabled) startStreamEncoder()
        } else {
            // In audio mode the camera is only needed for the live stream -
            // without the stream it is released completely.
            if (streamEnabled) ensureCamera() else releaseCamera()
            // Detach the viewfinder surface so the hidden preview stops rendering.
            runCatching { preview?.setSurfaceProvider(null) }
        }
        notifyState()
        updateNotification()
    }

    // ------------------------------------------------------------------- camera

    private fun ensureCamera() {
        try {
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
            Log.i(TAG, "ensureCamera (provider=${cameraProvider != null}, bound=$cameraBound, mode=$mode)")

            if (cameraProvider != null) {
                if (preview == null) buildPreview()
                if (videoCapture == null) buildCapture()
                bindUseCases()
                if (pendingStart == Mode.VIDEO && videoCapture != null) {
                    pendingStart = null
                    startRecordingOf(Mode.VIDEO)
                }
                return
            }

            val future = ProcessCameraProvider.getInstance(this)
            future.addListener({
                try {
                    cameraProvider = future.get()
                } catch (e: Exception) {
                    Log.e(TAG, "camera provider unavailable", e)
                    return@addListener
                }
                if (preview == null) buildPreview()
                if (videoCapture == null) buildCapture()
                bindUseCases()
                preview?.let { listener?.onPreviewReady(it) }
                // A recording that was requested before the camera was ready can
                // start now.
                if (pendingStart == Mode.VIDEO && videoCapture != null) {
                    pendingStart = null
                    startRecordingOf(Mode.VIDEO)
                }
            }, ContextCompat.getMainExecutor(this))
        } catch (t: Throwable) {
            Log.e(TAG, "ensureCamera failed", t)
            lastError = "the camera could not be opened: ${t.message}"
            notifyState()
        }
    }

    private fun releaseCamera() {
        runCatching { cameraProvider?.unbindAll() }
        camera = null
        cameraBound = false
    }

    private fun buildCapture() {
        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(quality, FallbackStrategy.higherQualityOrLowerThan(Quality.SD))
            )
            .build()
        videoCapture = VideoCapture.withOutput(recorder)
    }

    /**
     * Creates the viewfinder.
     *
     * The preview is always bound - on many phones the camera only starts when
     * a preview is part of the session. The app hands it a real surface while
     * the viewfinder is on screen.
     */
    private fun buildPreview() {
        preview = Preview.Builder().build()
    }

    /**
     * Watches what the camera does.
     *
     * When the camera cannot be opened - some phones refuse to open it for a
     * background app - the reason is put on the dashboard, otherwise the
     * buttons would simply do nothing.
     */
    private fun observeCameraState(bound: Camera) {
        if (observedCamera === bound) return
        observedCamera = bound
        runCatching {
            bound.cameraInfo.cameraState.observe(this) { state ->
                cameraState = state.type.name
                val error = state.error
                if (error != null && error.code != 0) {
                    val reason = error.cause?.message ?: cameraErrorText(error.code)
                    Log.e(TAG, "camera error ${error.code}: $reason")
                    lastError = "camera error: $reason"
                }
                notifyState()
            }
        }
    }

    /** What the CameraX error codes mean. */
    private fun cameraErrorText(code: Int): String = when (code) {
        CameraState.ERROR_CAMERA_IN_USE -> "the camera is in use by another app"
        CameraState.ERROR_MAX_CAMERAS_IN_USE -> "too many cameras are in use"
        CameraState.ERROR_CAMERA_DISABLED -> "the camera is switched off by the system"
        CameraState.ERROR_CAMERA_FATAL_ERROR -> "the camera failed"
        CameraState.ERROR_OTHER_RECOVERABLE_ERROR -> "the camera had a recoverable problem"
        CameraState.ERROR_STREAM_CONFIG -> "the camera streams could not be set up"
        CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED -> "do not disturb is switched on"
        CameraState.ERROR_CAMERA_REMOVED -> "the camera was removed"
        else -> "error $code"
    }

    private var observedCamera: Camera? = null

    /** What the camera is doing right now (for the dashboard). */
    @Volatile
    private var cameraState: String = "none"

    /** How many bytes the running video recording has produced so far. */
    @Volatile
    private var recordedBytes = 0L

    /** How many frames the live encoder has received. */
    @Volatile
    private var streamFrames = 0L

    /** How many frames the camera handed to the analysis. */
    @Volatile
    private var analyzedFrames = 0L

    private fun bindUseCases(attempt: Int = 0) {
        // Never disturb an active recording: unbinding the camera would
        // finalise the file.
        if (isRecording()) return
        val provider = cameraProvider ?: return
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        val analysis = imageAnalysis
        val capture = videoCapture
        // The recorder and the live feed both want the camera. A phone that
        // can serve two streams gets both of them - then the dashboard shows
        // the recording while it is being made. A phone that cannot refuses
        // the binding, and the recording wins: the feed waits and comes back
        // when the recording is done.
        val wantCapture = mode == Mode.VIDEO
        val wantPreview = wantCapture || (viewfinderVisible && mode == Mode.VIDEO)

        val useCases = ArrayList<UseCase>(3)
        val viewfinder = preview
        if (wantPreview && viewfinder != null) useCases.add(viewfinder)
        if (analysis != null) useCases.add(analysis)
        if (wantCapture && capture != null) useCases.add(capture)
        if (useCases.isEmpty()) {
            camera = null
            cameraBound = false
            return
        }

        try {
            provider.unbindAll()
            // The phone may have been turned since the last binding, so the
            // pictures are told again which way is up.
            imageAnalysis?.setTargetRotation(currentDisplayRotation())
            val bound = try {
                provider.bindToLifecycle(this, selector, *useCases.toTypedArray())
            } catch (e: Exception) {
                if (analysis == null || !wantCapture) throw e
                // The camera cannot serve the analysis and the recorder at the
                // same time - the recording wins.
                Log.w(TAG, "stream and recording cannot run together: ${e.message}")
                streamUnavailable = true
                val withoutAnalysis = useCases.filter { it !== analysis }
                if (withoutAnalysis.isEmpty()) throw e
                provider.bindToLifecycle(this, selector, *withoutAnalysis.toTypedArray())
            }
            camera = bound
            cameraBound = true
            observeCameraState(bound)
            Log.i(
                TAG,
                "camera bound (preview=${useCases.any { it === preview }}, " +
                    "analysis=${analysis != null}, recorder=$wantCapture)"
            )
            runCatching { bound.cameraControl.setLinearZoom(linearZoom) }
        } catch (e: Exception) {
            camera = null
            cameraBound = false
            Log.e(TAG, "camera bind failed (attempt $attempt)", e)
            // Some devices refuse a new binding right after the old one was
            // released - a couple of tries usually do the trick.
            if (attempt < BIND_RETRIES) {
                mainHandler.postDelayed({ bindUseCases(attempt + 1) }, BIND_RETRY_DELAY_MS)
            } else {
                lastError = "the camera could not be opened: ${e.message}"
                notifyState()
            }
        }
    }

    // ------------------------------------------------------------- live stream

    /**
     * Turns the live dashboard (and the HLS feed) on or off.
     *
     * Returns false when the server could not be started, so the UI can tell
     * the user instead of silently pretending it is on.
     */
    fun setLiveStreamEnabled(enabled: Boolean): Boolean {
        streamEnabled = enabled
        getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(Constants.PREF_STREAMING_ENABLED, enabled)
            .apply()
        if (enabled) {
            val started = startLiveServer()
            startStreamEncoder()
            // Keep the service in the foreground so the stream survives leaving
            // the app.
            goForeground()
            acquireWakeLock()
            mainHandler.removeCallbacks(keepAlive)
            mainHandler.post(keepAlive)
            return started
        }
        mainHandler.removeCallbacks(keepAlive)
        stopStreamEncoder()
        liveServer?.stop()
        liveServer = null
        liveEndpoints = null
        serverError = null
        return true
    }

    /**
     * Keeps the foreground service (and the wake lock) alive while the live
     * stream runs.
     *
     * Some phones take the foreground state away from a background app after
     * a while. Saying it again every half minute brings it back.
     */
    private val keepAlive = object : Runnable {
        override fun run() {
            if (!streamEnabled) return
            runCatching { goForeground() }
            runCatching { acquireWakeLock() }
            mainHandler.postDelayed(this, KEEP_ALIVE_INTERVAL_MS)
        }
    }

    fun isLiveStreamEnabled(): Boolean = streamEnabled

    /** The last server problem, if any. */
    fun liveServerError(): String? = serverError

    private fun startLiveServer(): Boolean {
        if (liveServer != null) return true
        val endpoints = LiveStreamEndpoints(
            { resources.openRawResource(R.raw.dashboard).use { it.readBytes() } },
            { resources.openRawResource(R.raw.hls_min).use { it.readBytes() } },
            liveHost
        )
        for (port in LIVE_PORT..LIVE_PORT + 10) {
            val server = LiveStreamServer(port) { request -> endpoints.handle(request) }
            if (server.start()) {
                liveEndpoints = endpoints
                liveServer = server
                serverError = null
                Log.w(TAG, "live dashboard on port ${server.boundPort}")
                return true
            }
            server.stop()
        }
        serverError = "no free port (${LIVE_PORT}-${LIVE_PORT + 10})"
        Log.w(TAG, "could not start the live server: $serverError")
        return false
    }

    /** The address a browser on the same Wi-Fi can open. */
    fun liveServerUrl(): String {
        val server = liveServer ?: return ""
        return "http://${localIpAddress()}:${server.boundPort}/"
    }

    private fun startStreamEncoder() {
        if (streamEncoder != null) return
        if (isRecording()) return
        streamUnavailable = false
        // Every run of the encoder is a session of its own: it brings its own
        // init segment and its own picture parameters.
        streamSession++

        val encoder = LiveStreamEncoder(sessionId = streamSession, callback = object : LiveStreamEncoder.Callback {
            override fun onInitSegment(bytes: ByteArray) {
                hlsBuffer.setInitSegment(bytes)
                initSegmentId++
                Log.w(TAG, "the live feed got a new init segment (session $streamSession)")
            }

            override fun onFrame() {
                streamFrames++
                lastStreamFrameAt = SystemClock.elapsedRealtime()
            }

            override fun onFragment(sequence: Int, bytes: ByteArray, durationMs: Long, sessionId: Int) {
                // A fragment of an older session belongs to another track -
                // the player would mix the pictures of two sessions and show
                // nothing.
                if (sessionId != streamSession) return
                // Every encoder session numbers its fragments on its own, so
                // the number gets the session in front of it - that way the
                // fragments of two sessions can never be mixed up.
                hlsBuffer.addFragment(
                    this@RecordingService.initSegmentId * 1000 + sequence,
                    bytes,
                    durationMs.toInt()
                )
                // A hiccup that the feed recovered from should not keep
                // worrying the dashboard.
                if (lastError?.startsWith("the live feed failed") == true) {
                    lastError = null
                    notifyState()
                }
            }

            override fun onError(error: Throwable) {
                Log.w(TAG, "stream encoder error: ${error.message}")
                // The place where it happened is the useful part.
                val where = error.stackTrace.take(3).joinToString(" <- ") { "${it.className}.${it.methodName}" }
                lastError = "the live feed failed: ${error.message} ($where)"
                notifyState()
            }
        })
        streamEncoder = encoder
        hlsBuffer.clear()
        // The encoder waits for the first frame to learn the frame size.
        encoder.start()

        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(LiveStreamEncoder.WIDTH, LiveStreamEncoder.HEIGHT),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                        )
                    )
                    .build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            // The camera hands the pictures over the way the sensor sees
            // them - lying on their side. The rotation turns them the way
            // the phone is held, so the dashboard shows them upright.
            .setOutputImageRotationEnabled(true)
            .setTargetRotation(currentDisplayRotation())
            .build()
        analysis.setAnalyzer(streamExecutor) { image ->
            // Counted before the encoder, so it is clear whether the camera
            // hands out frames at all.
            analyzedFrames++
            try {
                encoder.submitFrame(image)
            } finally {
                image.close()
            }
        }
        imageAnalysis = analysis
        // The camera is needed for the live feed, no matter which mode the app
        // is in - the dashboard has to work in either one. ensureCamera also
        // brings the service's lifecycle back up, without it the camera stays
        // closed and no frame ever arrives.
        ensureCamera()
    }

    private fun stopStreamEncoder() {
        streamEncoder?.stop()
        streamEncoder = null
        imageAnalysis = null
        hlsBuffer.clear()
        // The feed is stopped on purpose here (a recording needs the camera),
        // so a leftover problem of the feed is not worth showing any more.
        if (lastError?.startsWith("the live feed failed") == true) {
            lastError = null
            notifyState()
        }
        if (!isRecording()) bindUseCases()
    }

    /**
     * The way the display is turned right now.
     *
     * The camera hands the pictures over the way the sensor sees them, and
     * the sensor lies on its side. This is the turn that brings them the way
     * the phone is held - and the way the recording is turned.
     */
    private fun currentDisplayRotation(): Int {
        val displays = getSystemService(DisplayManager::class.java) ?: return Surface.ROTATION_0
        return displays.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
    }

    private fun localIpAddress(): String {        val wifi = runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.name.startsWith("wlan") || it.name.startsWith("ap") }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<java.net.Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress
        }.getOrNull()
        if (wifi != null) return wifi

        return runCatching {
            java.net.DatagramSocket().use { socket ->
                socket.connect(java.net.InetAddress.getByName("8.8.8.8"), 1002)
                socket.localAddress?.hostAddress ?: "127.0.0.1"
            }
        }.getOrDefault("127.0.0.1")
    }

    private val liveHost = object : LiveStreamEndpoints.Host {
        override fun statusJson(): String {
            val json = JSONObject()
            val running = activeRecordingMode
            json.put("recording", isRecording())
            json.put("recordingMode", when {
                !isRecording() || running == null -> ""
                running == Mode.AUDIO -> "audio"
                else -> "video"
            })
            json.put("paused", paused)
            json.put("streaming", isLiveStreamRunning())
            // The feed runs next to the recording now, so "a recording is
            // running" does not mean "the feed is paused" any more. Only the
            // frames tell the truth.
            json.put(
                "feed",
                when {
                    isFeedActuallyPaused() -> "paused"
                    isLiveStreamRunning() -> "running"
                    else -> "stopped"
                }
            )
            json.put("mode", if (mode == Mode.VIDEO) "video" else "audio")
            json.put("fragments", hlsBuffer.fragmentCount())
            json.put("initId", initSegmentId)
            json.put("streamUnavailable", streamUnavailable)
            json.put("serverError", serverError ?: "")
            json.put("error", lastError ?: "")
            json.put("bytes", recordedBytes)
            json.put("frames", streamFrames)
            json.put("analyzed", analyzedFrames)
            json.put("camera", cameraState)
            json.put("encoder", streamEncoder?.describe() ?: "")
            json.put("build", Constants.BUILD_ID)
            json.put("url", liveServerUrl())
            return json.toString()
        }

        override fun startAudioRecording(): Boolean {
            mainHandler.post { this@RecordingService.startRecordingOfMode(RecordingService.Mode.AUDIO) }
            return true
        }

        override fun startVideoRecording(): Boolean {
            mainHandler.post { this@RecordingService.startRecordingOfMode(RecordingService.Mode.VIDEO) }
            return true
        }

        override fun stopRecording(): Boolean {
            mainHandler.post {
                try {
                    this@RecordingService.stopRecording()
                } catch (t: Throwable) {
                    Log.e(TAG, "remote stop failed", t)
                    lastError = "the recording could not be stopped: ${t.message}"
                    notifyState()
                }
            }
            return true
        }

        override fun isRecording(): Boolean = this@RecordingService.isRecording()

        override fun isStreaming(): Boolean = isLiveStreamRunning()

        override fun isFeedPaused(): Boolean = isFeedActuallyPaused()

        override fun initSegment(): ByteArray? = hlsBuffer.initSegment()

        override fun fragment(sequence: Int): ByteArray? = hlsBuffer.fragment(sequence)

        override fun sequences(): List<Int> = hlsBuffer.sequences()

        override fun duration(sequence: Int): Int = hlsBuffer.duration(sequence)
    }

    private fun isLiveStreamRunning(): Boolean =
        streamEncoder != null && hlsBuffer.initSegment() != null

    /**
     * True when the feed really is not running.
     *
     * A recording used to switch the feed off, so "a recording is running"
     * meant "the feed is paused". Now the feed keeps running next to the
     * recording, so the dashboard is told the truth: the feed is only paused
     * when the camera stopped handing out frames.
     */
    private fun isFeedActuallyPaused(): Boolean {
        if (!isRecording()) return false
        val last = lastStreamFrameAt
        if (last == 0L) return true
        return SystemClock.elapsedRealtime() - last > FEED_STALL_MS
    }

    /** When the live encoder got its last frame. */
    @Volatile
    private var lastStreamFrameAt = 0L

    // --------------------------------------------------------------------- zoom

    fun setLinearZoom(linear: Float) {
        linearZoom = linear.coerceIn(0f, 1f)
        runCatching { camera?.cameraControl?.setLinearZoom(linearZoom) }
    }

    fun zoomIn() = setLinearZoom(linearZoom + ZOOM_STEP)

    fun zoomOut() = setLinearZoom(linearZoom - ZOOM_STEP)

    fun linearZoom(): Float = linearZoom

    fun zoomRatio(): Float =
        runCatching { camera?.cameraInfo?.zoomState?.value?.zoomRatio }.getOrNull() ?: 1f

    // ---------------------------------------------------------------- session

    fun isRecording(): Boolean = videoRecording != null || audioRecorder != null

    fun isPaused(): Boolean = paused

    /** True while the encoder is still warming up and the clock is not running yet. */
    fun isStarting(): Boolean = starting

    /** True while the app shows the viewfinder. */
    @Volatile
    private var viewfinderVisible = false

    /**
     * Tells the service whether the app is showing the viewfinder.
     *
     * The viewfinder stays bound either way - on this phone the camera only
     * starts when a preview is bound. While the app is not on screen the
     * preview simply has no surface, and the camera copes with that.
     */
    fun setViewfinderVisible(visible: Boolean) {
        if (viewfinderVisible == visible) return
        viewfinderVisible = visible
        mainHandler.post {
            if (visible) {
                if (mode == Mode.VIDEO) ensureCamera() else bindUseCases()
            } else {
                bindUseCases()
            }
        }
    }

    fun previewOrNull(): Preview? = preview

    fun setListener(l: Listener?) {
        listener = l
    }

    /** Milliseconds of recorded (non-paused) footage so far. */
    fun elapsedMillis(): Long {
        if (!isRecording() || recordStartRealtime == 0L) return 0L
        val end = if (paused) pausedAtRealtime else SystemClock.elapsedRealtime()
        return (end - recordStartRealtime - totalPausedMs).coerceAtLeast(0L)
    }

    /**
     * Starts a recording of a specific kind - audio or video - regardless of
     * what the phone is currently doing. This is what the dashboard buttons use.
     *
     * An audio recording only needs the microphone, which works from any
     * thread - so it starts straight away, even when the app is in the
     * background and the main thread is busy with the camera. A video
     * recording needs the camera, so it goes through the main thread.
     */
    fun startRecordingOfMode(target: Mode) {
        val requestedAt = SystemClock.elapsedRealtime()
        if (target == Mode.VIDEO || isRecording()) {
            mainHandler.post { remoteStart(target, requestedAt) }
        } else {
            controlExecutor.execute { remoteStart(target, requestedAt) }
        }
    }

    private fun remoteStart(target: Mode, requestedAt: Long) {
        val waited = SystemClock.elapsedRealtime() - requestedAt
        Log.w(TAG, "remote start requested: $target (waited ${waited}ms)")
        try {
            if (isRecording()) {
                // A video recording releases itself asynchronously, so the new
                // recording has to wait for it. The finalize callback picks it
                // up.
                queuedStart = target
                stopRecording()
                if (audioRecorder == null && videoRecording == null) {
                    // The audio recorder is gone already - nothing to wait for.
                    queuedStart = null
                    beginRecording(target)
                }
                return
            }
            beginRecording(target)
        } catch (t: Throwable) {
            // Never let this take the service (and with it the dashboard) down -
            // report the problem instead.
            Log.e(TAG, "remote start failed", t)
            lastError = "the recording could not be started: ${t.message}"
            notifyState()
        }
    }

    /** A recording that was asked for before the camera was ready. */
    private var pendingStart: Mode? = null

    /** A recording that has to wait for the running one to be released. */
    private var queuedStart: Mode? = null

    /** The last capture problem, shown on the dashboard. */
    @Volatile
    var lastError: String? = null
        private set

    /** Actually starts the recording - the camera may still need a moment. */
    private fun beginRecording(target: Mode) {
        lastError = null

        if (target == Mode.VIDEO) {
            // The recorder needs the camera for itself: this phone cannot
            // serve the recorder and the live feed at the same time, and when
            // it tries, the recording is the one that breaks. The feed waits
            // and comes back when the recording is done.
            mode = Mode.VIDEO
            if (streamEncoder != null) {
                // The live feed keeps running. The camera is bound again with
                // the recorder *and* the analysis, so the dashboard shows the
                // recording while it is being made. A phone that cannot serve
                // both refuses the binding, and the fallback in bindUseCases
                // drops the analysis - then the feed waits.
                streamUnavailable = false
                bindUseCases()
            }
            if (videoCapture == null) {
                // The camera is still coming up - start as soon as it is ready.
                pendingStart = Mode.VIDEO
                ensureCamera()
                scheduleStartRetry()
                return
            }
            if (!cameraBound || camera == null) {
                // The camera did not come up (yet). Try again in a moment
                // instead of failing silently.
                pendingStart = Mode.VIDEO
                ensureCamera()
                scheduleStartRetry()
                return
            }
            pendingStart = null
            startRecordingOf(Mode.VIDEO)
        } else {
            // An audio recording needs no camera at all: the live stream keeps
            // running and the app stays in its current mode.
            pendingStart = null
            startRecordingOf(Mode.AUDIO)
        }
    }

    /**
     * Tries the video recording again a moment later.
     *
     * The camera needs a second to open, and on some phones the first attempt
     * right after the live stream was stopped is refused.
     */
    private fun scheduleStartRetry() {
        if (startRetries++ > START_RETRIES) {
            pendingStart = null
            lastError = "the camera could not be opened for the video recording"
            Log.e(TAG, "giving up on the video recording: no camera")
            notifyState()
            return
        }
        mainHandler.postDelayed({
            if (pendingStart == Mode.VIDEO && !isRecording()) beginRecording(Mode.VIDEO)
        }, START_RETRY_DELAY_MS)
    }

    /** How often a video recording that waits for the camera is retried. */
    private var startRetries = 0

    /** Starts a recording in the app's current mode. */
    fun startRecording() = startRecordingOf(mode)
    /**
     * Starts a recording of a specific kind.
     *
     * The kind of the running recording is tracked separately from the app's
     * mode, so a recording that is started from the dashboard is reported
     * correctly - even when the app itself is in the other mode.
     */
    private fun startRecordingOf(target: Mode) {
        if (isRecording()) return
        try {
            // The live feed and the recorder both want the camera, so the
            // camera is bound with both of them (see bindUseCases). A phone
            // that cannot serve both drops the feed - the recording wins.
            // An audio recording needs no camera, so the stream can keep
            // running.
            // Set the service type up for what is about to be captured.
            goForeground(target)
            acquireWakeLock()

            paused = false
            pausedAtRealtime = 0L
            totalPausedMs = 0L
            // Provisional start; rebased once the capture is really running so
            // the timer always matches the length of the saved file.
            recordStartRealtime = SystemClock.elapsedRealtime()
            starting = true
            activeRecordingMode = target

            if (target == Mode.AUDIO) {
                startAudioRecording()
            } else {
                startVideoRecording()
            }
            notifyState()
            updateNotification()
        } catch (t: Throwable) {
            // A failure here must not take the service down - the live stream
            // lives in the same process.
            Log.e(TAG, "startRecording failed", t)
            lastError = "the recording could not be started: ${t.message}"
            starting = false
            activeRecordingMode = null
            notifyState()
        }
    }

    fun stopRecording() {
        starting = false
        if (audioRecorder != null) {
            activeRecordingMode = null
            releaseAudioRecorder(finalize = true)
            recordStartRealtime = 0L
            paused = false
            totalPausedMs = 0L
            notifyState()
            updateNotification()
            // The audio recording is done, so the notification can go.
            maybeStopIfIdle()
            // The camera is free again - bring the live feed back. That has to
            // happen on the main thread, the camera does not allow anything else.
            if (streamEnabled) {
                mainHandler.post {
                    if (!isRecording() && pendingStart == null) startStreamEncoder()
                }
            }
        } else if (videoRecording != null) {
            runCatching { videoRecording?.stop() } // cleanup in Finalize
        }
    }

    fun pauseRecording() {
        if (!isRecording() || paused) return
        val ok = if (audioRecorder != null) {
            runCatching { audioRecorder?.pause() }.isSuccess
        } else {
            runCatching { videoRecording?.pause() }.isSuccess
        }
        if (!ok) return
        paused = true
        pausedAtRealtime = SystemClock.elapsedRealtime()
        notifyState()
        updateNotification()
    }

    fun resumeRecording() {
        if (!isRecording() || !paused) return
        val ok = if (audioRecorder != null) {
            runCatching { audioRecorder?.resume() }.isSuccess
        } else {
            runCatching { videoRecording?.resume() }.isSuccess
        }
        if (!ok) return
        totalPausedMs += SystemClock.elapsedRealtime() - pausedAtRealtime
        paused = false
        notifyState()
        updateNotification()
    }

    fun switchCamera(): Boolean {
        if (isRecording()) return false
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        linearZoom = 0f // zoom ranges differ per lens
        if (cameraProvider != null) bindUseCases()
        return true
    }

    // ------------------------------------------------------------- video backend

    /** The CameraX error codes, in words. */
    private fun videoErrorText(error: Int): String = when (error) {
        VideoRecordEvent.Finalize.ERROR_NONE -> "no error"
        VideoRecordEvent.Finalize.ERROR_UNKNOWN -> "unknown error"
        VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED -> "the file grew too large"
        VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE -> "not enough storage space"
        VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE -> "the camera was taken away"
        VideoRecordEvent.Finalize.ERROR_INVALID_OUTPUT_OPTIONS -> "the output is not valid"
        VideoRecordEvent.Finalize.ERROR_ENCODING_FAILED -> "the encoder failed"
        VideoRecordEvent.Finalize.ERROR_RECORDER_ERROR -> "the recorder failed"
        VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA -> "no video data was produced"
        VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED -> "the recording was too long"
        VideoRecordEvent.Finalize.ERROR_RECORDING_GARBAGE_COLLECTED -> "the recording was dropped"
        else -> "error $error"
    }

    private fun startVideoRecording() {
        val capture = videoCapture ?: run {
            lastError = "the camera is not ready yet"
            Log.w(TAG, "startVideoRecording before camera ready")
            notifyState()
            return
        }
        if (camera == null) {
            lastError = "the camera could not be opened"
            Log.w(TAG, "startVideoRecording without a bound camera")
            notifyState()
            return
        }
        val front = lensFacing == CameraSelector.LENS_FACING_FRONT
        val fileName = RecordingStoragePaths.newFileName()
        val options = RecordingStoragePaths.outputOptions(this, front, fileName)

        try {
            var pending = capture.output.prepareRecording(this, options)
            if (hasAudioPermission()) {
                pending = pending.withAudioEnabled()
            }

            videoRecording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        // CameraX starts the encoder asynchronously, so the file
                        // only begins now - not when the user tapped Record.
                        // Rebase the clock here, otherwise the timer overshoots the
                        // real length of the recording by the warm-up time.
                        recordStartRealtime = SystemClock.elapsedRealtime()
                        starting = false
                        lastError = null
                        startRetries = 0
                        Log.w(TAG, "video recording started")
                        notifyState()
                        updateNotification()
                    }
                    is VideoRecordEvent.Pause,
                    is VideoRecordEvent.Resume -> {
                        notifyState()
                        updateNotification()
                    }
                    is VideoRecordEvent.Status -> {
                        // Shows whether the camera really delivers frames.
                        recordedBytes = event.recordingStats.numBytesRecorded
                        notifyState()
                    }
                    is VideoRecordEvent.Finalize -> {
                        val err = event.error
                        videoRecording = null
                        activeRecordingMode = null
                        paused = false
                        starting = false
                        recordStartRealtime = 0L
                        totalPausedMs = 0L
                        if (err == VideoRecordEvent.Finalize.ERROR_NONE) {
                            Log.w(TAG, "video finalized: ${event.outputResults.outputUri}")
                            lastError = null
                        } else {
                            // Say what went wrong - otherwise the dashboard
                            // looks like the button simply does nothing.
                            val cause = runCatching { event.cause }.getOrNull()
                            lastError = "the video recording failed (${videoErrorText(err)})" +
                                (cause?.message?.let { ": $it" } ?: "")
                            Log.e(TAG, "video finalized with error $err", cause)
                        }
                        notifyState()
                        updateNotification()
                        // A recording that was requested while this one was still
                        // being released starts now that the camera is free.
                        val queued = queuedStart
                        queuedStart = null
                        if (queued != null) {
                            beginRecording(queued)
                        } else {
                            maybeStopIfIdle()
                            // The camera is free again - bring the live feed back.
                            if (streamEnabled) startStreamEncoder()
                        }
                    }
                    else -> Unit
                }
            }
        } catch (t: Throwable) {
            // CameraX refuses to record when the camera cannot be opened - say
            // so instead of failing silently.
            lastError = "the video recording could not be started: ${t.message}"
            Log.e(TAG, "startVideoRecording failed", t)
            videoRecording = null
            activeRecordingMode = null
            starting = false
            notifyState()
        }
    }

    // ------------------------------------------------------------- audio backend

    private fun startAudioRecording() {
        val name = RecordingStoragePaths.newAudioFileName()
        val values = RecordingStoragePaths.audioContentValues(name).apply {
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }

        val uri = runCatching {
            contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
        }.getOrNull()
        if (uri == null) {
            lastError = "audio: could not create the file"
            activeRecordingMode = null
            Log.e(TAG, "audio: MediaStore insert failed")
            return
        }

        val pfd = runCatching { contentResolver.openFileDescriptor(uri, "w") }.getOrNull()
        if (pfd == null) {
            runCatching { contentResolver.delete(uri, null, null) }
            lastError = "audio: could not open the output file"
            activeRecordingMode = null
            Log.e(TAG, "audio: could not open output")
            return
        }

        val recorder = MediaRecorder(this)
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioEncodingBitRate(Constants.DEFAULT_AUDIO_BITRATE)
            recorder.setAudioSamplingRate(Constants.DEFAULT_AUDIO_SAMPLING_RATE)
            recorder.setOutputFile(pfd.fileDescriptor)
            recorder.prepare()
            recorder.start()
        } catch (e: Exception) {
            // The microphone is refused when the app is in the background
            // without a microphone foreground service - say so instead of
            // leaving an empty file behind.
            lastError = "audio: the microphone is not available (${e.message})"
            activeRecordingMode = null
            Log.e(TAG, "audio: recorder start failed", e)
            runCatching { recorder.release() }
            runCatching { pfd.close() }
            runCatching { contentResolver.delete(uri, null, null) }
            notifyState()
            return
        }

        audioRecorder = recorder
        audioUri = uri
        audioPfd = pfd
        // The recorder is running synchronously, so the file starts now.
        recordStartRealtime = SystemClock.elapsedRealtime()
        starting = false
        lastError = null
        Log.w(TAG, "audio recording started")
    }

    /** Stops the audio recorder and, when [finalize], publishes the MediaStore row. */
    private fun releaseAudioRecorder(finalize: Boolean) {
        val recorder = audioRecorder
        audioRecorder = null

        if (recorder != null) {
            runCatching { recorder.stop() }
            runCatching { recorder.reset() }
            runCatching { recorder.release() }
        }

        val uri = audioUri
        audioUri = null
        runCatching { audioPfd?.close() }
        audioPfd = null

        if (finalize && uri != null) {
            val clear = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
            runCatching { contentResolver.update(uri, clear, null, null) }
            Log.w(TAG, "audio finalized: $uri")
        }
    }

    // ------------------------------------------------------------------- misc

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun notifyState() {
        listener?.onRecordingStateChanged(isRecording(), paused)
    }

    private fun maybeStopIfIdle() {
        // The live stream keeps the service alive on its own, so it survives
        // leaving the app.
        if (streamEnabled) {
            goForeground()
            return
        }
        if (!isRecording()) {
            // Nothing is being recorded: take the notification away again.
            leaveForeground()
        }
        if (!isRecording() && boundClients <= 0) {
            stopSelf()
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RecorderByAlex:recording").apply {
            setReferenceCounted(false)
            try {
                acquire(WAKE_LOCK_TIMEOUT_MS)
            } catch (e: Exception) {
                Log.e(TAG, "wake lock acquire failed", e)
            }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) runCatching { it.release() }
        }
        wakeLock = null
    }

    /**
     * The foreground service types actually in use.
     *
     * Both types are declared for as long as the service is in the foreground:
     * the live stream needs the camera, and a recording that is started from
     * the dashboard can need the microphone (or the camera) at any moment -
     * also while the app is in the background. Declaring a type does not open
     * the microphone or the camera, that only happens in a real recording.
     *
     * Keeping the types stable also avoids a foreground service restart, which
     * the platform can refuse while the app is in the background.
     */
    private fun foregroundType(targetMode: Mode): Int {
        var type = 0
        if (hasCameraPermission()) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (hasAudioPermission()) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        return if (type == 0) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else type
    }

    private fun goForeground(targetMode: Mode = mode) {
        val type = foregroundType(targetMode)
        isForeground = true
        lastForegroundType = type
        val result = runCatching {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
        }
        if (result.isFailure) {
            // This must never take the service down: the live stream (and with
            // it the dashboard) lives in this process. Report it instead.
            val cause = result.exceptionOrNull()
            Log.e(TAG, "startForeground failed", cause)
            lastError = "the foreground service could not be started: ${cause?.message}"
            // Try again without a type, so the service stays alive at least.
            runCatching {
                startForeground(NOTIF_ID, buildNotification())
            }
        }
    }

    /** Removes the foreground notification again (nothing is being recorded). */
    private fun leaveForeground() {
        if (!isForeground) return
        isForeground = false
        runCatching {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        // Make sure no leftover notification stays in the shade.
        runCatching {
            getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
        }
    }

    private fun updateNotification() {
        // Only while a recording is running, otherwise the notification would
        // linger in the shade even though nothing is being captured.
        if (!isForeground) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.areNotificationsEnabled()) {
            nm.notify(NOTIF_ID, buildNotification())
        }
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val recordingNow = isRecording()

        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, RecordingService::class.java).setAction(ACTION_STOP_RECORDING),
            PendingIntent.FLAG_IMMUTABLE
        )
        val pauseIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, RecordingService::class.java).setAction(ACTION_TOGGLE_PAUSE),
            PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_record)
            .setContentTitle(getString(R.string.app_name))
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(openIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        val recText = if (mode == Mode.AUDIO) R.string.notif_recording_audio else R.string.notif_recording
        when {
            recordingNow && paused -> builder.setContentText(getString(R.string.notif_paused))
            recordingNow -> {
                builder.setContentText(getString(recText))
                builder.setUsesChronometer(true)
                builder.setWhen(System.currentTimeMillis() - elapsedMillis())
            }
            // No recording, but the live stream is running - say so, and show
            // the address so it is always at hand.
            streamEnabled -> {
                val url = liveServerUrl()
                builder.setContentText(
                    if (url.isBlank()) {
                        getString(R.string.notif_streaming)
                    } else {
                        getString(R.string.notif_streaming_url, url)
                    }
                )
            }
            else -> builder.setContentText(getString(R.string.notif_ready))
        }

        if (recordingNow) {
            builder.addAction(
                R.drawable.ic_stat_record,
                getString(if (paused) R.string.resume else R.string.pause),
                pauseIntent
            )
            builder.addAction(
                R.drawable.ic_stat_record,
                getString(R.string.notif_stop),
                stopIntent
            )
        }
        return builder.build()
    }
}
