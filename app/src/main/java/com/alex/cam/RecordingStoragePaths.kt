package com.alex.cam

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import androidx.camera.video.MediaStoreOutputOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Where the recordings go and what they are called.
 *
 *   Movies/Recorder/Camera/Back/Recorder_Std_yyyyMMdd_HHmmss.mp4
 *   Music/Recorder/Audio/Audio_Std_yyyyMMdd_HHmmss.m4a
 *
 * The recordings go into the public MediaStore collections, so they are
 * ordinary user files: visible in Google Photos / Files, over USB/MTP, and
 * readable by any other app.
 */
object RecordingStoragePaths {

    /** Top-level public folder under the device's shared "Movies" collection. */
    const val PUBLIC_ROOT = "Movies"

    fun relativePath(front: Boolean): String {
        val source = if (front) {
            Constants.RECORDING_SUBDIR_CAMERA_FRONT
        } else {
            Constants.RECORDING_SUBDIR_CAMERA_BACK
        }
        return "$PUBLIC_ROOT/${Constants.RECORDING_DIRECTORY}/" +
            "${Constants.RECORDING_SUBDIR_CAMERA}/$source/"
    }

    fun newFileName(): String {
        return "${Constants.RECORDING_FILE_PREFIX_STANDARD}${timestamp()}." +
            Constants.RECORDING_FILE_EXTENSION
    }

    /** Public folder for audio-only recordings: /sdcard/Music/Recorder/Audio/ */
    fun audioRelativePath(): String =
        "Music/${Constants.RECORDING_DIRECTORY}/${Constants.RECORDING_SUBDIR_AUDIO}/"

    fun newAudioFileName(): String {
        return "${Constants.RECORDING_FILE_PREFIX_AUDIO_STANDARD}${timestamp()}." +
            Constants.AUDIO_FILE_EXTENSION
    }

    fun audioContentValues(displayName: String): ContentValues = ContentValues().apply {
        put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
        put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
        put(MediaStore.Audio.Media.RELATIVE_PATH, audioRelativePath())
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    /**
     * CameraX output options that publish the recording straight into the public
     * MediaStore. On API 29+ no storage permission is needed to add your own
     * media, and CameraX clears IS_PENDING for us when the file is finalized.
     */
    fun outputOptions(context: Context, front: Boolean, displayName: String): MediaStoreOutputOptions {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, relativePath(front))
        }
        return MediaStoreOutputOptions
            .Builder(context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values)
            .build()
    }
}
