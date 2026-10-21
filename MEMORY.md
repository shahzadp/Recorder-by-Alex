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
- **31 unit tests in 5 suites** — always run them, they catch the box-level mistakes
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
- `Surface.setFrameTimestamp` is a **hidden API** — with a surface input the
  frame times come from the system clock and cannot be overridden.
- The logcat on the itel is flooded by system DNS spam; capture it live to a
  file rather than with `logcat -d`.

---

## 4. Architecture of the live feed

```
camera ─→ ImageAnalysis ─→ MediaCodec (H.264, surface input) ─→ media3 FragmentedMp4Muxer
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
- `HlsStreamBuffer` keeps a 15-segment rolling window; the playlist is built
  from it with the **real** segment durations.
- The **player is bundled** (`res/raw/hls_min.js`, hls.js 1.5.17, Apache-2.0) so
  the dashboard works on a phone without internet.

### The planned architecture (the user's request)

> "live should only start when I press start video recording"

Meaning: the live feed **is** the recording — one encoder, one set of frames,
written to the file *and* to the fragments. Then the feed shows exactly what is
being recorded, never pauses, and only exists while recording. `LiveRecorder.kt`
is written for this (video + AAC audio → one muxer → teed to file + splitter);
it is **not wired into the service yet**.

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

Tools that made this possible: **ffprobe** (container/track inspection),
**ffmpeg `-bsf:v trace_headers`** (SPS field by field), and the **browser
tool** (load the dashboard, read hls.js's own log, decode a frame into a canvas
and measure its brightness — the only way to tell "black" from "not playing").

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
- The feed restarts cleanly after a recording, with a fresh init segment ✅
- The package rename to `com.alex.cam`, the file prefix `Recorder_Std_*.mp4` ✅
- The crash log (`RecorderApp`) and the status diagnostics
  (`camera`, `encoder`, `frames`, `analyzed`, `luma`, `boxes`, `frags`, `feed`) ✅

### Broken / open

- **The live preview is black.** The container, the track ids, the data offset
  and the SPS were all verified correct with ffprobe/ffmpeg; the raw H.264 still
  will not decode. The `csd` fix (build0620) is the newest attempt — **it has
  not been tested on the phone yet.**
- **The feed pauses during a recording** — a consequence of the camera serving
  one consumer. The fix is the app owning the encoder (section 4).
- The old `com.alex.recorder` app may still be installed on the phone: uninstall
  it, it keeps port 8080 and the old battery exemption.

---

## 7. To-do

1. **Install `build0620`** and check the live preview. If the picture is there,
   the `csd` fix was it.
2. If it is still black: implement the **app's own recorder**
   (`LiveRecorder` → `RecordingService`):
   - the analysis feeds the encoder, the camera binds **only** the analysis
   - the mic via `AudioRecord` → the AAC encoder → the same muxer
   - the muxer's bytes tee to the MediaStore file **and** the HLS splitter
   - the feed starts with the recording and stops with it (the user's request)
   - keep CameraX's recorder as a fallback until the new path is proven
3. Grant **"Keep the phone awake"** on the new package.
4. Move the live feed from `LiveStreamEncoder` to the recorder (the feed must not
   start when the server starts).
5. Re-check the rotation (the app's own encoder has to rotate the picture
   itself) and the pause/resume of the recording.
6. Delete the stale APKs from the project folder; keep only the newest.

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
