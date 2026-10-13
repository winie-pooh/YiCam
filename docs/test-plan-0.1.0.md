# Test plan 0.1.0 - live view

Written for the person who tests on the phone and for the AI that reads the report.
Nothing in 0.1.0 has been compiled or run by the author (no Kotlin compiler where it was written): run `./gradlew testDebugUnitTest` first.

## Setup (once)
1. `app/build.gradle.kts`, inside `dependencies { ... }`, add:
   `implementation("androidx.media3:media3-exoplayer:1.8.0")`
   `implementation("androidx.media3:media3-exoplayer-rtsp:1.8.0")`
   Sync Gradle. (1.8.0 is a version known to exist; a newer 1.x may be used.)
2. `AndroidManifest.xml`, in the `<activity ...>` tag add the attribute
   `android:configChanges="orientation|screenSize|smallestScreenSize|screenLayout|keyboardHidden"`
   so that rotating the phone does not restart the video.
3. Unzip the 0.1.0 files over the project, run the unit tests, then run the app.

## Manual checks (phone, camera on, camera Wi-Fi, SD card inserted, camera NOT recording)
| # | Do | Expect | Write down |
|---|---|---|---|
| M1 | Connect, press "Live view" | Picture appears, text shows "Live - started in N ms" | N, and whether the picture is sharp, smooth, correctly proportioned |
| M2 | Point the camera at a running stopwatch (on a PC screen), put the phone next to it, photograph both | Delay is the difference between the two clocks | Delay in ms |
| M3 | Rotate the phone twice | The picture keeps playing (may flicker once) | Yes/no |
| M4 | Stop, Start - five times | Picture returns each time | How many of 5 worked, time to picture each time |
| M5 | Leave the app for 30 s (Home), come back | Live view shows Idle; Start brings the picture back | Yes/no |
| M6 | Back to status, Live view again | Works | Yes/no |
| M7 | Keep the picture for 3 minutes | Battery level on the status screen keeps updating; no [WARN]/[ERR] in the log | Any warnings |
| M8 | Switch the camera off while playing | Picture stops; a problem text appears; no crash | What the screen and the log said |
| M9 | Start a video recording with the camera's own button, then open Live view | Message "not available while recording" (or a picture - write down which) | The app_status value in the log |
| M10 | Open Live view with the camera in photo mode | Works or a clear message | Result |

## If the picture does not appear
1. Read the technical line under the picture: it now shows the whole chain of causes (for example `RtspPlaybackException <- ... <- SocketException: ...`), not only "Source error". The first attempt uses RTP over TCP, the second lets the player choose (UDP first); both are written to the log as `[LIVE]` lines.
2. On the Probe screen (menu, three dots, Probe) press Connect, then **RTSP trace**. The app starts the preview and writes the full RTSP conversation with the camera to the log (`[RTSP]` lines: OPTIONS, DESCRIBE with the SDP, SETUP with several address guesses, PLAY, and how many media frames arrived in 3 s).
3. Press Share log and send the text. Optionally also send the Logcat of the app filtered by `RtspClient|ExoPlayer|ExoRtspPlayer`.
Known risk: the camera's RTSP reply has a broken `Content-Base: rtsp:///live/` header; if Media3 stops with an RTSP/source error, the next step is a libVLC test, then a small own RTSP client.

## Decision rules (after the report)
- Picture within 5 s and delay under 1.5 s: keep Media3.
- Picture works but delay above 1.5 s: try smaller buffers, then compare with libVLC.
- No picture with Media3: try libVLC; if that fails too, write an own RTSP client (OPTIONS, DESCRIBE, SETUP over TCP, PLAY) and decode H.264 with MediaCodec.

## Report format
Android version; result of unit tests (count from `app/build/test-results/testDebugUnitTest/*.xml`); table M1-M10; what was verified on the phone, what only by tests, what not at all.
