package com.alex.cam

/**
 * Mirror of the original app's Constants (subset relevant to camera recording + gallery).
 * Values were recovered from the decompiled original (com.alex.recorder.Constants).
 */
object Constants {
    const val RECORDING_DIRECTORY = "Recorder"
    const val RECORDING_FILE_EXTENSION = "mp4"

    // The prefix of a video recording: "Recorder_Std_"
    const val RECORDING_FILE_PREFIX_STANDARD = "Recorder_Std_"

    const val RECORDING_SUBDIR_CAMERA = "Camera"
    const val RECORDING_SUBDIR_CAMERA_BACK = "Back"
    const val RECORDING_SUBDIR_CAMERA_FRONT = "Front"

    // Audio-only recordings (new mode added by the clone).
    const val RECORDING_SUBDIR_AUDIO = "Audio"
    const val AUDIO_FILE_EXTENSION = "m4a"

    // Audio recordings use their own prefix (NOT "Recorder_Std_").
    const val RECORDING_FILE_PREFIX_AUDIO_STANDARD = "Audio_Std_"

    // Audio defaults recovered from Constants.java
    const val DEFAULT_AUDIO_BITRATE = 192_000
    const val DEFAULT_AUDIO_SAMPLING_RATE = 48_000

    // SharedPreferences (original PREFS_NAME = "app_prefs")
    const val PREFS_NAME = "app_prefs"

    // The live stream server is opt-in from the Settings screen.
    const val PREF_STREAMING_ENABLED = "pref_streaming_enabled"

    /**
     * Marks this build.
     *
     * It is shown on the dashboard, so it is always clear which APK is
     * actually running on the phone.
     */
    const val BUILD_ID = "2026-10-22 02:35 rotfix"
}
