# Recorder by Alex

A recorder app for Android: camera video recording, audio-only recording, and
automatic call recording.

* Package: `com.alex.cam`
* Release label: **Recorder by Alex** · Debug label: *Recorder by Alex (debug)*

## Build

```powershell
$env:GRADLE_USER_HOME = "D:\Dev\BuildTools\gradle"
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"

.\gradlew.bat assembleRelease      # signed + R8 + resource shrinking
.\gradlew.bat assembleDebug        # debug build
.\gradlew.bat testDebugUnitTest    # unit tests
```

Outputs:

| Variant | Label | Size | Path |
|---|---|---|---|
| release | Recorder by Alex | ~2.9 MB | `app/build/outputs/apk/release/app-release.apk` |
| debug | Recorder by Alex (debug) | ~22 MB | `app/build/outputs/apk/debug/app-debug.apk` |

## Signing

The release build is signed with `keystore/recorder-by-alex.jks`.

| | |
|---|---|
| Keystore | `keystore/recorder-by-alex.jks` |
| Alias | `recorder` |
| Store / key password | *in `keystore.properties` - not in the repo* |
| Certificate SHA-256 | `A4:15:7C:5B:F6:78:23:B8:2E:4B:94:37:1D:99:23:AF:0E:9E:54:26:20:11:B7:00:BE:98:76:A4:3C:C6:7C:4F` |

> **The keystore and `keystore.properties` are git-ignored on purpose.** A
> signing key in a repository can be used to sign a fake update of this app.
> They are also *not* in version control, so keep a private backup somewhere
> else - Android identifies an app by its signing key, and without these two
> files you can never publish an update over an already installed release.
>
> The credentials can also be supplied through the environment
> (`RECORDER_STORE_FILE`, `RECORDER_STORE_PASSWORD`, `RECORDER_KEY_ALIAS`,
> `RECORDER_KEY_PASSWORD`), which is the safer option on a shared machine.

## Features

* **Video recording** - CameraX, HD (1280x720), zoom buttons on both lenses,
  pause/resume, saved to `Movies/Recorder/Camera/<Back|Front>/Faditor_Std_<timestamp>.mp4`.
* **Audio recording** - AAC 192 kbps / 48 kHz, saved to
  `Music/Recorder/Audio/Audio_Std_<timestamp>.m4a`.
* **Background recording** - a foreground service keeps recording with the screen
  off or the app in the background. The notification only appears while a
  recording is actually running.
* **M3 colours** - the app follows the user's wallpaper palette on Android 12+.

The call recording feature was removed: capturing the far end of a call is
reserved for system apps on modern Android (`CAPTURE_AUDIO_OUTPUT` is
`signature|privileged`), so it could not be made to work reliably.

## Layout

```
app/src/main/java/com/alex/cam/
  MainActivity.kt              viewfinder, mode switch, timer, zoom
  RecordingService.kt          camera + audio capture, foreground service
  RecorderApp.kt               Material 3 dynamic colours, crash log
  SettingsActivity.kt          settings, live stream, battery exemption
  RecordingStoragePaths.kt     output paths / file names
  Constants.kt                 shared constants
  LiveStreamServer.kt          the HTTP server (written from scratch)
  LiveStreamEndpoints.kt       the dashboard routes
  LiveStreamEncoder.kt         camera frames -> H.264 -> fragmented MP4
  H264Encoder.kt               the H.264 encoder (the camera's YUV planes)
  FragmentingOutputStream.kt   splits the MP4 into init segment and fragments
  HlsStreamBuffer.kt           the rolling window of fragments
  AvcCodecConfig.kt            unpacks the codec specific data of the encoder
  YuvConverter.kt              YUV_420_888 -> a flat I420 buffer
analysis/legacy-com.alex.recorder.beta/
                              the reverse-engineering archive of the old beta
```


## Live stream

**Settings → ⚙️ → `Live stream: OFF/ON`** starts a small HTTP server on the phone
(port 8080, falling back to 8080-8090) and shows the address to open, e.g.
`http://192.168.0.103:8080/`.

The dashboard it serves (`app/src/main/res/raw/dashboard.html`, written from
scratch) offers exactly two things:

* **Live watch** - the camera as HLS (`/live.m3u8`, `/init.mp4`, `/seg-N.m4s`),
  played with hls.js.
* **Remote recording control** - *Start audio recording* / *Stop audio recording*
  and *Start video recording* / *Stop video recording*, each driving its own kind
  of recording (`POST /record/audio/start|stop`, `/record/video/start|stop`).

`GET /status` returns the state as JSON: whether a recording runs and of which
kind, the live feed state, the number of buffered segments, the camera state,
the encoder that was picked, how many frames went through it, and the last
problem - so a failure on the phone can be read from a browser instead of a
logcat.

The dashboard also shows the reason in red if anything fails, and the build id
of the app that is running, so it is always clear which APK is on the phone.

How the feed is produced: `ImageAnalysis` (YUV) → `H264Encoder` (the camera's
planes go into a hardware H.264 encoder as real YUV) → media3
`FragmentedMp4Muxer` → a `FragmentingOutputStream` that splits it into an
`ftyp`+`moov` init segment and `styp`+`moof`+`mdat` fragments, kept in a 15
segment rolling window.

A few things about that chain are worth knowing, because every one of them
cost a debugging session:

* The **encoder is picked by hand** (`MediaCodecList`) and its
  `KEY_COLOR_FORMAT` is set. Without that the codec is set up for a YUV
  buffer - and the picture has to go in as YUV as well. A canvas drawn into
  the encoder's surface is nothing a hardware encoder can read: it answers with
  a valid, but empty, video track.
* The frames are copied **plane by plane**, with the row and pixel strides the
  codec asks for (`codec.getInputImage()`). An encoder that gets misaligned
  rows answers with green bands, or with nothing at all.
* The **codec specific data** is unpacked the way media3 wants it: the SPS and
  the PPS as two entries, each behind an Annex-B start code. Android delivers
  them in its own "csd" format (`AvcCodecConfig` does the unpacking).
* The **timeline is built by the app**, not taken from the camera: the
  camera's timestamps occasionally jump, and one jump makes a sample duration
  too big for the 32 bit MP4 field.
* The **picture is turned upright by the camera**
  (`setOutputImageRotationEnabled`), so the dashboard shows it the way the
  phone is held.
* The **muxer is fed through a channel**, not through the deprecated
  `OutputStream` constructor - that one makes a seekable muxer, which holds the
  fragments back until the file is closed. A live stream never closes.
* The muxer writes **no `tfdt` box**, so a fragment has no absolute time of
  its own - and a player that receives the fragments one by one cannot place
  them on a timeline. The splitter therefore adds the missing `tfdt` to every
  fragment, with the time that follows from the sample durations before it.
* The splitter also **takes the base data offset out of the `tfhd` box**. The
  muxer writes one, because in a file it is the place of the `moof` in the
  stream; a browser refuses it with *"TFHD base-data-offset not allowed by
  MSE"*. The fragment is addressed relative to its own `moof` instead.
* A playlist counts its segments **from zero**, so the times inside the
  fragments have to be relative to the oldest segment of the window - and that
  one changes every time the window slides. The buffered fragments are
  therefore given their times again whenever a new one arrives. Without this
  the player puts the fragments outside of the seekable range and refuses
  them.
* The **player is part of the app** (`res/raw/hls_min.js`, hls.js 1.5.17,
  Apache-2.0), so the dashboard also works on a phone without an internet
  connection.

Behaviour worth knowing:

* The stream is **video only** - it never opens the microphone and never starts
  an audio recording. (The service declares the microphone foreground type so a
  recording can be started remotely while the app is closed; declaring a type
  does not open the microphone.)
* It **keeps running in the background**: the service is started (not just
  bound) while the stream is on, so leaving the app does not stop it. A
  notification shows that the stream is live and the address to open. The
  service is sticky, so it comes back by itself after the system kills it.
* The feed works in **both modes** - the camera is bound with the analysis
  alone while the app is closed. The viewfinder is only bound while the app
  actually shows it: a preview without a surface would keep the camera waiting
  for frames that never arrive.
* **The live view keeps running during a recording.** The camera is bound with
  the recorder *and* the analysis, so the dashboard shows the recording while it
  is being made. A phone that cannot serve both refuses the binding - then the
  feed waits and comes back when the recording is done, and the dashboard says
  so instead of pretending everything is fine.
* **Settings → "Keep the phone awake"** asks the system to stop putting the app
  to sleep. Without it, some phones freeze the app in the background: the
  dashboard then only answers after the app is opened. The live stream is
  restored automatically when the service starts again, so the dashboard comes
  back on its own.


Not implemented, on purpose. The audio sources that capture **both** sides of a
call (`VOICE_CALL`, `VOICE_DOWNLINK`, `REMOTE_SUBMIX`) are guarded by
`android.permission.CAPTURE_AUDIO_OUTPUT`, which Android 10+ marks
`signature|privileged` (some vendor ROMs make it role-managed, tied to the
default phone app). A normally installed app can therefore never record the far
end of a call, and the workarounds all need a rooted or system-installed build.
The feature was removed rather than shipped half-working.

## Deliberate omissions

* The app does **not** use the Accessibility API: it is the pattern Play
  Protect flags as harmful outside the Play Store, and some Android 16 devices
  refuse to install such an APK without an override.
* No telephony, notification-listener or Shizuku permissions are declared - the
  permission set is just camera, microphone, notifications, internet, foreground
  service, wake lock and the battery-optimisation exemption.


