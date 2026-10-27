# MEMORY.md — Recorder by Alex

Working notes for the Android recorder app with the live dashboard.
Project: `D:\GitHub-Projects\Recorder-by-Alex` · Package: `com.alex.cam` · Target: itel S25 (Android 15)

---

## 1. What the app is

A recorder (camera video + audio-only) with a **live dashboard** served by the phone
itself over Wi-Fi. The dashboard offers exactly two things:

- **Live watch** — the camera as HLS, played with hls.js
- **Remote recording control** — *Start/Stop audio recording* and *Start/Stop video recording*

Call recording was removed completely (telephony / Shizuku / accessibility /
notification-listener all deleted): `CAPTURE_AUDIO_OUTPUT` is `signature|privileged`
on stock AOSP, so a normal app can never capture the far end of a call.

---

## 2. Build and test

```powershell
$env:GRADLE_USER_HOME = "D:\Dev\BuildTools\gradle"
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"
cd D:\GitHub-Projects\Recorder-by-Alex
.\gradlew.bat assembleRelease assembleDebug testDebugUnitTest --no-daemon --console=plain
```

- Gradle 9.1.0 wrapper · AGP 9.0.1 · JDK 21 · compileSdk/targetSdk 36 · minSdk 31
- Kotlin 2.3.20 (AGP 9's built-in Kotlin — **no** `org.jetbrains.kotlin.android` plugin)
- Dependencies: CameraX 1.6.2, media3 1.9.0 (common/muxer), Material 3, junit
- **33 unit tests in 7 suites** — always run them, they catch the box-level mistakes
- Release: R8 + resource shrinking, ~3.0 MB signed APK
- Git: `https://github.com/shahzadp/Recorder-by-Alex` (private) — this file and the
  README are the project notes, the repository is the source of truth

### Signing

| | |
|---|---|
| Keystore | `keystore/recorder-by-alex.jks` (git-ignored) |
| Alias | `recorder` |
| Password | in `keystore.properties` (git-ignored) / `RECORDER_STORE_PASSWORD` |
| Cert SHA-256 | `A4:15:7C:5B:F6:78:23:B8:2E:4B:94:37:1D:99:23:AF:0E:9E:54:26:20:11:B7:00:BE:98:76:A4:3C:C6:7C:4F` |

The keystore lives **inside** the project (`keystore/recorder-by-alex.jks` +
`keystore.properties`) and both are git-ignored. The extra copy in
`D:\Recorder-Signing-Keys\` was removed on 2026-10-21 — the project's own copy
signs a release correctly (verified: `apksigner` reports cert SHA-256
`a4157c5b…cc67c4f`, matching the table above) and a private backup is kept
elsewhere. Keep a backup **outside** the repository: without the key the app
can never be updated over an installed release.

### Deploying to the phone

```powershell
kdeconnect-cli -n "itel S25" --share "D:\GitHub-Projects\Recorder-by-Alex\Recorder-by-Alex-build0620.apk"
```

- itel S25 = `192.168.0.103` · Galaxy A16 = `192.168.0.108` (**never** push to the Galaxy)
- Always give the APK a **new name** (`build0620`, …) — several identical transfers
  were installed by mistake and cost hours.

---

## 3. Device facts (learned the hard way)

- **The itel ROM kills background apps.** KDE Connect vanished from the network
  mid-session, and the recorder app was frozen (8-second HTTP responses, then
  nothing). This is why the app has **Settings → "Keep the phone awake"**
  (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`); the user must grant it **once per
  install/package change**.
- **The camera serves ONE consumer at a time.** The recorder and the live
  analysis cannot share it — trying it broke the video recording (the file was
  never finalised). This is the root cause of "the live feed pauses while
  recording".
- A `Preview` bound **without a surface** stalls the whole camera session —
  no frames reach any other use case. The viewfinder is only bound while the
  app shows it.
- The encoder on this phone is `c2.unisoc.avc.encoder`; `MediaCodec.createEncoderByType`
  happily returns a codec that cannot be fed with byte buffers at all.
- That encoder takes **`COLOR_FormatYUV420SemiPlanar`** (21) and hands out its
  input as an image with the real strides — `getInputImage()` works, and the
  planes are copied into it one by one.
- **The camera CAN serve the recorder and the analysis at the same time** — the
  feed runs next to the recording (section 4). The older note that it could
  not was about the *surface* input, not about the number of streams.
- `Surface.setFrameTimestamp` is a **hidden API** — with a surface input the
  frame times come from the system clock and cannot be overridden. The public
  way is `EGLExt.eglPresentationTimeANDROID()` on an EGL surface.
- The logcat on the itel is flooded by system DNS spam; capture it live to a
  file rather than with `logcat -d`.

---

## 4. Architecture of the live feed

```
camera ─→ ImageAnalysis (YUV_420_888) ─→ H264Encoder (MediaCodec, byte buffer) ─→ media3 FragmentedMp4Muxer
                                                                                        │
                                                                ┌───────────────────────┴────────────────────┐
                                                            ftyp+moov (init)                        moof+mdat (fragments)
                                                                  │                                       │
                                                            /init.mp4                            /seg-N.m4s + /live.m3u8
```

- The muxer is fed through a **`WritableByteChannel`**, not the deprecated
  `Builder(OutputStream)` — that one creates a *seekable* muxer which holds the
  fragments back until the file is closed. A live stream never closes.
- `FragmentingOutputStream` splits the byte stream into the init segment and
  the fragments, and **injects the `tfdt`** (media3 never writes one) plus
  **adjusts the `trun` data offset** (the inserted box shifts the sample data).
- `HlsStreamBuffer` keeps a **6-fragment (6 s) rolling window**; the playlist is
  built from it with the **real** segment durations. The window is deliberately
  short (it used to be 15): a longer one only lets a player that fell behind keep
  replaying old fragments, so the delay piles up instead of snapping back to the
  live edge. The dashboard's hls.js player is tuned to match
  (`liveSyncDurationCount: 1`, small `maxBufferLength`), and the server sets
  `tcpNoDelay` so the small playlist/segment responses go out immediately.
- **Known issue (2026-10-27):** opening the dashboard in a browser often shows
  the loading spinner and the stream only appears after a **manual refresh a few
  seconds later**. The first `loadStream()` can run before the playlist has a
  playable segment (or with a stale `initId`), and hls.js does not always
  recover on its own. Needs a fix: wait for a non-empty playlist / retry, or
  reload the source when the init segment or playlist changes. See section 7.
- The **player is bundled** (`res/raw/hls_min.js`, hls.js 1.5.17, Apache-2.0) so
  the dashboard works on a phone without internet.

### The frames go in as YUV, not as a picture

`H264Encoder` hands the **planes of the camera frame** to the encoder, one by
one, with the row and pixel strides the codec asks for. It does **not** draw a
bitmap into the encoder's input surface any more — that was the reason for the
black screen (section 5, bug 12).

- The codec is picked by name and **its `KEY_COLOR_FORMAT` is set** — without
  that the codec is set up for a YUV buffer, and a canvas is nothing it can
  read.
- The normal path uses `codec.getInputImage()` and copies the planes into it,
  which is the only way to get the strides right. The fallback packs the frame
  by hand (NV12, or I420 for the codecs that only take the planar one).
- The **timeline is built by the app** (one frame after the other), because the
  camera's timestamps jump on this phone.

### The picture is turned upright by the camera

`ImageAnalysis.setOutputImageRotationEnabled(true)` with the target rotation
of the display: the camera hands the pictures over the way the sensor sees
them (lying on their side) and the analysis turns them. The encoder takes the
size of the first frame, so a turned picture (480x640) works without anything
else.

### The feed runs next to the recording

The camera is bound with the recorder **and** the analysis, so the dashboard
shows the recording while it is being made. A phone that cannot serve both
refuses the binding, and the fallback drops the analysis — then the feed waits
and comes back afterwards. The dashboard is told the truth: the feed is only
"paused" when the camera stopped handing out frames.

### The planned architecture (the user's request)

> "live should only start when I press start video recording"

Meaning: the live feed **is** the recording — one encoder, one set of frames,
written to the file *and* to the fragments. Then the feed shows exactly what is
being recorded, never pauses, and only exists while recording. `LiveRecorder.kt`
is written for this (video + AAC audio → one muxer → teed to file + splitter);
it is **not wired into the service yet**.

### What the reference project does differently

`github.com/DigitallyRefined/android-ip-camera` was read for this. It solves
the same problem and is worth knowing:

- The camera renders **directly into the encoder's surface** through a GL pipe
  (`CameraGlPipe`: SurfaceTexture → EGL → the encoder's input surface), with
  `EGLExt.eglPresentationTimeANDROID()` for the timestamps. That is the public
  answer to the "hidden API" `Surface.setFrameTimestamp` in section 3.
- Its byte-buffer path is the one this app now uses: `getInputImage()` plus a
  stride-aware copy, with NV12/I420 chosen from the codec's own caps. Its
  comment says it plainly: *"tight-packed buffer input is exactly what some
  hardware encoders reject (misaligned rows, green/garbled bands)"*.
- It serves **raw Annex-B H.264 over HTTP** and plays it with **WebCodecs**
  (`VideoDecoder`), not with HLS. No container, no MSE, no mp4box.js — which is
  why it never ran into the `tfhd` problem below. The price is that it needs
  HTTPS (WebCodecs needs a secure context).
- Its recording and its live stream are **the same encoder**: the recording
  taps the drain loop, so the camera only ever serves one stream. This app
  keeps CameraX's recorder for the file and binds the analysis next to it,
  which is the same idea without rewriting the recording.


---

## 5. The bug hunt that produced the current state

Every one of these was a real bug, found by byte-level inspection:

1. **Duplicate HTML id `video`** — the `<video>` player and the *Start video
   recording* button shared it, so `getElementById` returned the player and the
   button had **no click handler at all**. → `player` / `videoBtn` / `audioBtn`.
2. **Surface-less `Preview`** stalled the camera session → no frames anywhere.
3. **`createEncoderByType`** returned a codec that only takes a surface.
4. **The `csd` layout** differs between encoder sessions (4-byte start code +
   2-byte length / 3-byte start code / bare NAL) — `AvcCodecConfig.parseCsd`
   now understands all three.
5. **The seekable muxer** (`Builder(OutputStream)`) held every fragment back.
6. **The camera's timestamps jump** → the 32-bit sample duration overflowed →
   the app now builds its own timeline.
7. **media3 writes no `tfdt`** → the player had no timeline at all.
8. **Inserting the `tfdt` shifted the sample data** → the `trun` offset has to
   move with it.
9. **Fragments of an old session mixed with a new init segment** (the init
   carries its own picture parameters) → every session is now numbered and the
   stragglers are dropped.
10. **Re-basing the `tfdt` after publishing** mutated fragments the player had
    already taken → removed; a player takes the position from the `tfdt` and the
    length from `EXTINF`.
11. **`init_id` / `trex` / track id** mismatches made the player refuse the
    stream.
12. **The picture was drawn into the encoder's input surface.** The codec was
    picked for a YUV layout but never told about it (`KEY_COLOR_FORMAT` was
    missing), and a hardware encoder cannot read a canvas — so it produced a
    valid, but empty, video track. → `H264Encoder` feeds the real YUV planes.
13. **The `tfhd` box carried a base data offset.** media3 writes it, because in
    a file it is the place of the `moof` in the stream. A browser refuses it:
    *"TFHD base-data-offset not allowed by MSE"*. The media source extensions
    want the fragment addressed relative to its own `moof`. → the flag is
    cleared, the field is dropped, and "default base is moof" takes its place.

**Bug 13 was the one that kept the preview black.** The container, the track
ids, the data offset and the SPS had all been verified correct, and the raw
H.264 was perfect — but the browser never got past the `moof`. The only way to
find it was to let the browser say it: the `video` element carries the reason
in `video.error`, and it names the box.

Tools that made this possible: **ffprobe** (container/track inspection),
**ffmpeg `-bsf:v trace_headers`** (SPS field by field), **a box dumper** (the
`moof` byte by byte, to see the `tfhd` flags and the `trun` data offset), and
the **browser tool** (load the dashboard and read `video.error` — the browser
names the box that it does not accept, which is what finally ended the hunt).

---

## 6. Status

### The old beta — `com.alex.recorder.beta`

The predecessor of this app, statically reverse-engineered with REA. The whole
archive now lives in the project under
`analysis/legacy-com.alex.recorder.beta/` — **read its `README.md` first**.
It is the only record of features the rewrite dropped: FFmpegKit, OpenCV and
TFLite motion detection, screen recording, dual-camera PiP, and the beta's much
larger web dashboard (`app-assets/web/`). The `bulk/` folder there holds the
pulled APK and the decompiled third-party libraries; it is not in git.

The old package is **not** the current one — installing it keeps port 8080 and
its own battery exemption, so uninstall it before testing this build.

### Working

- Dashboard → **audio recording** ✅ (app closed, file written)
- Dashboard → **video recording** ✅ (app closed, ~6.5 MB per 10 s, clean finalize)
- Remote start/stop, the HLS server, the playlist, the segments, the init segment ✅
- **The live preview shows the picture** ✅ — the black screen is gone (the
  `tfhd` base data offset was the reason, section 5 bug 13)
- **The feed runs while a video recording runs** ✅ — the camera is bound with
  the recorder and the analysis together
- The picture is **upright** ✅ (the analysis turns it, section 4)
- The feed restarts cleanly after a recording, with a fresh init segment ✅
- The package rename to `com.alex.cam`, the file prefix `Recorder_Std_*.mp4` ✅
- The crash log (`RecorderApp`) and the status diagnostics
  (`camera`, `encoder`, `frames`, `analyzed`, `luma`, `boxes`, `frags`, `feed`) ✅

### Broken / open

- Once, right after the app was updated, the status showed **"a frame could
  not be muxed: ...getClass() on a null object reference"** and no fragment was
  published. It did not come back and the feed worked after that, so it was
  probably the encoder being rebuilt while the service restarted — but it was
  **not explained**. Watch for it: a single bad sample must not stop the feed.
- The old `com.alex.recorder` app may still be installed on the phone: uninstall
  it, it keeps port 8080 and the old battery exemption.
- **The dashboard needs a manual refresh on first open** (minor, noted
  2026-10-27): the browser sits on the loading state for a few seconds and only
  plays after the user refreshes. Not fixed yet.

---

## 7. To-do

1. **Watch the live feed during a long recording** — the camera is bound with
   two streams now, and the itel has to serve both.
2. The **app's own recorder** (`LiveRecorder` → `RecordingService`) is still not
   wired in: the recording is still CameraX's own, at 720p, while the feed is
   480p. One encoder for both would make the feed and the file the same
   pictures — and would let the feed start and stop with the recording (the
   user's request, section 4).
3. Grant **"Keep the phone awake"** on the new package.
4. The **pause/resume of the recording** while the feed runs.
5. Delete the stale APKs from the project folder; keep only the newest.
6. **Fix the first-load stall of the dashboard** — make the player wait for a
   playable playlist and retry/reload by itself instead of needing a manual
   browser refresh (see section 6).

---

## 8. Working with the phone from the host

The phone serves its own API on `http://192.168.0.103:8080` — reachable from
the development machine, which makes the whole loop testable without touching
the phone:

```powershell
# the state of the recorder and of the feed
Invoke-WebRequest "http://192.168.0.103:8080/status" -UseBasicParsing

# start / stop a recording from the outside
Invoke-WebRequest "http://192.168.0.103:8080/record/video/start" -Method POST
Invoke-WebRequest "http://192.168.0.103:8080/record/stop"      -Method POST
```

`tools/remote-test*.ps1` drive exactly that and print the result; they wait for
a given `build` id in the status before they start.

The browser tool can load the same dashboard and decode a frame into a canvas to
measure whether the picture is real — the fastest way to tell a broken stream
from a broken player.
