# FILES.md — project file map (YiCam)

Read this first. One row per project file: what it is responsible for.

## Rules (mandatory for every change)

1. Every source file (`.kt`, `.xml`, scripts) starts with a short header comment (1-3 lines):
   what the file is responsible for and what must NOT be put in it.
2. When a file is added, renamed, deleted, or its responsibility changes, update in the same change:
   (a) the header comment in the file, (b) the row below, (c) `CHANGELOG.md`.
3. One file = one responsibility. If the description needs more than two sentences, split the file.
4. The description below and the header in the file must say the same thing (word for word for files marked "yes").
5. Run `python tools/check_files_index.py` from the project root before handing over a change. It must print OK.

Header template for Kotlin:

```kotlin
/**
 * Responsible for: <one sentence>.
 * Not responsible for: <what lives elsewhere>.
 */
```

Header template for XML:

```xml
<!-- Responsible for: <one sentence>. -->
```

## Files

Paths are relative to the Android Studio project root (the folder that contains `app/`).
"Header in file": `yes` = the header exists and is checked against this table; `no header yet` = gets its header the next time the file changes.
The probe screen (ProbeScreen.kt) is a dev tool: its text is hardcoded English. The status screen (StatusScreen.kt) and every later real screen take all texts from res/values/strings.xml (English), values-es (Spanish) and values-uk (Ukrainian); a text added to one file must be added to all three.

| File | Responsible for | Last changed | Header in file |
|---|---|---|---|
| `app/src/main/java/com/example/yicam/MainActivity.kt` | Responsible for: the single activity: choosing between the status, live view and probe screens, asking for the local network permission, telling the session when the app goes to the background and stopping the live view then, Share log. Not responsible for: drawing the screens (StatusScreen.kt, LiveScreen.kt, ProbeScreen.kt) or any link, protocol or video logic (CameraSession.kt, LiveViewController.kt). | 0.1.0 | yes |
| `app/src/main/java/com/example/yicam/ProbeController.kt` | Responsible for: the actions behind the probe screen (dev tool): canned probe commands, the RTSP trace (RtspProbe.kt), raw commands, log access, connect/disconnect forwarded to the session. Survives screen rotation. Not responsible for: drawing the screen or asking for permissions (MainActivity.kt, ProbeScreen.kt), the link to the camera (CameraSession.kt) or the protocol (CameraApi.kt). | 0.1.1 | yes |
| `app/src/main/java/com/example/yicam/WifiBinder.kt` | Responsible for: finding the camera's Wi-Fi network (phone address 192.168.42.x), binding the app process to it, and reporting when that network is lost (Android implementation of CameraNetwork). Not responsible for: opening sockets (CameraConnection.kt), reconnect decisions (CameraSession.kt) or asking for permissions (MainActivity.kt). | 0.0.3.1 | yes |
| `app/src/main/java/com/example/yicam/CameraConnection.kt` | Responsible for: one TCP connection to the camera port: open/close, a reader that turns bytes into JSON objects, serialized writes, connection diagnostics in the log. Not responsible for: tokens, matching replies to requests, timeouts of commands, reconnect decisions (CameraApi.kt, CameraSession.kt), or finding the Wi-Fi (WifiBinder.kt). | 0.0.3 | yes |
| `app/src/main/java/com/example/yicam/CameraApi.kt` | Responsible for: the camera session: connect/reconnect, the session token, one command in flight at a time, reply matching by msg_id, timeouts, one automatic token refresh on rval -4, and the stream of camera events. Not responsible for: sockets and framing (CameraConnection.kt), UI, Wi-Fi binding, background reconnecting and polling (CameraSession.kt), or the meaning of individual commands' replies (StatusParser.kt). | 0.0.3 | yes |
| `app/src/main/java/com/example/yicam/CameraMessage.kt` | Responsible for: the model of camera JSON messages (reply, event) and the conversion between text and these objects, including building requests. Not responsible for: sockets, timeouts, tokens or waiting for replies (CameraApi.kt). | 0.0.2 | yes |
| `app/src/main/java/com/example/yicam/MiniJson.kt` | Responsible for: a small JSON reader and writer (objects, arrays, strings, numbers, booleans, null) with no Android dependency. Not responsible for: camera message meaning (CameraMessage.kt) or cutting a stream into objects (JsonStreamParser.kt). | 0.0.2 | yes |
| `app/src/main/java/com/example/yicam/JsonStreamParser.kt` | Responsible for: cutting a continuous byte stream into complete top-level JSON objects (text), however the bytes are chunked. Not responsible for: understanding the objects (MiniJson.kt, CameraMessage.kt) or any camera commands. | 0.0.2 | yes |
| `app/src/main/java/com/example/yicam/Cmd.kt` | Responsible for: the list of camera command msg_id values (the only place with these numbers) and their reply timeouts. Not responsible for: building or sending requests (CameraMessage.kt, CameraApi.kt). | 0.0.2 | yes |
| `app/src/main/java/com/example/yicam/ConnectionState.kt` | Responsible for: the low-level state of one CameraApi connection (Disconnected, Connecting, Connected with token, Failed). Not responsible for: the state shown to the user (SessionState.kt) or changing the state (CameraApi.kt does that). | 0.0.3 | yes |
| `app/src/main/java/com/example/yicam/CameraException.kt` | Responsible for: the error types of camera communication. Not responsible for: deciding when they are thrown (CameraApi.kt, CameraConnection.kt, WifiBinder.kt) or how they are shown (UI). | 0.0.3 | yes |
| `app/src/main/java/com/example/yicam/TrafficLog.kt` | Responsible for: the in-memory ring buffer of raw traffic and events, with secrets masked, observable by the UI and exportable as text. Not responsible for: sockets, parsing, or showing the log. | 0.0.2 | yes |
| `app/src/main/java/com/example/yicam/LogMasker.kt` | Responsible for: hiding secrets (Wi-Fi password, serial number) in text that goes to the log. Not responsible for: storing or showing the log (TrafficLog.kt). | 0.0.2 | yes |
| `app/src/main/res/xml/network_security_config.xml` | Allows cleartext HTTP/RTSP only for 192.168.42.1. | 0.0.1 | no header yet |
| `app/src/main/AndroidManifest.xml` | Permissions (INTERNET, ACCESS_NETWORK_STATE, CHANGE_NETWORK_STATE, ACCESS_LOCAL_NETWORK) and network security config reference. 0.1.0 adds configChanges on the activity so rotation does not restart the video. | 0.1.0 | n/a (generated) |
| `app/build.gradle.kts` | Module build settings and dependencies; 0.0.2 added the coroutines dependency. 0.1.0 adds Media3 ExoPlayer and its RTSP module. | 0.1.0 | n/a (generated) |
| `app/src/test/java/com/example/yicam/CameraApiTest.kt` | Responsible for: integration tests of CameraApi + CameraConnection against FakeCameraServer (session, token, -4 refresh, timeouts, events, fragmentation, reconnect, serialization, raw commands, log masking). Not responsible for: unit tests of the parser, JSON or masking (their own test files). | 0.0.2 | yes |
| `app/src/test/java/com/example/yicam/FakeCameraServer.kt` | Responsible for: a scriptable fake camera (TCP server on localhost) for integration tests: session token, canned replies from real logs, and switches for faults. Not responsible for: assertions (the tests do that) or real camera behaviour beyond what the logs showed. | 0.1.0 | yes |
| `app/src/test/java/com/example/yicam/Fixtures.kt` | Responsible for: real camera messages copied from camera logs of 2026-10-12 (YDXJ firmware 1.3.0), plus small helpers that build a message of the same shape; used as test data. Not responsible for: any test logic. | 0.0.3.1 | yes |
| `app/src/test/java/com/example/yicam/JsonStreamParserTest.kt` | Responsible for: unit tests of JsonStreamParser (chunking, glued objects, braces and escapes inside strings, UTF-8, garbage, size limit, fuzz). Not responsible for: tests of JSON meaning or the camera session. | 0.0.2 | yes |
| `app/src/test/java/com/example/yicam/MiniJsonTest.kt` | Responsible for: unit tests of MiniJson (parsing, writing, escaping, errors). Not responsible for: stream splitting or camera message meaning. | 0.0.2 | yes |
| `app/src/test/java/com/example/yicam/LogMaskerTest.kt` | Responsible for: unit tests of LogMasker and TrafficLog (secret masking, line format, ring buffer). Not responsible for: sockets or camera sessions. | 0.0.2 | yes |
| `app/src/test/java/com/example/yicam/CameraMessageTest.kt` | Responsible for: unit tests of CameraMessage (reply/event classification, tolerant param types, request encoding) on real camera messages. Not responsible for: sockets or sessions. | 0.0.2 | yes |
| `tools/check_files_index.py` | Checking that docs/FILES.md lists every .kt/.xml source file and that file headers match the index. Not responsible for: building the project or checking code quality. | 0.0.2 | n/a (script) |
| `docs/FILES.md` | This index. | 0.1.3 | n/a |
| `docs/protocol-findings.md` | Facts observed on the real camera (formats, timings, events). Not responsible for: plans or decisions. | 0.1.3 | n/a |
| `CHANGELOG.md` | Change log per version. | 0.1.3 | n/a |
| `app/src/main/java/com/example/yicam/SessionState.kt` | Responsible for: the states of the camera link as shown to the user (searching Wi-Fi, connecting, connected, reconnecting, camera off, error with a reason) and the list of failure reasons. Not responsible for: changing the state (CameraSession.kt) or the low-level state of one connection (ConnectionState.kt). | 0.0.3 | yes |
| `app/src/main/java/com/example/yicam/Backoff.kt` | Responsible for: calculating how long to wait before the next reconnect attempt (1, 2, 4, 8, 16, 30 s, then 30 s, with a random spread of plus or minus 20 percent). Not responsible for: doing the waiting or deciding whether to reconnect (CameraSession.kt). | 0.0.3 | yes |
| `app/src/main/java/com/example/yicam/CameraStatus.kt` | Responsible for: the data models of what the app knows about the camera: CameraStatus (battery, SD card, mode) and SettingsSnapshot (key/value copy of the camera settings, without secrets). Not responsible for: reading them from the camera (CameraSession.kt) or parsing replies (StatusParser.kt). | 0.0.3.1 | yes |
| `app/src/main/java/com/example/yicam/StatusParser.kt` | Responsible for: turning camera replies and events (battery, SD memory, settings) into plain values, tolerant to numbers or text, and leaving secrets out. Not responsible for: sending commands or keeping state (CameraSession.kt) or the models themselves (CameraStatus.kt). | 0.0.3 | yes |
| `app/src/main/java/com/example/yicam/CameraNetwork.kt` | Responsible for: the contract "find the camera's Wi-Fi, bind to it, tell when it is lost", so the session can be tested without Android. Not responsible for: how Android does it (WifiBinder.kt) or what is done with the network (CameraSession.kt). | 0.0.3 | yes |
| `app/src/main/java/com/example/yicam/CameraSession.kt` | Responsible for: the life of the link to the camera: finding the Wi-Fi, opening the session, reading and refreshing the camera status and settings, noticing a lost link and reconnecting with growing delays, pausing in the background; it publishes SessionState, CameraStatus and SettingsSnapshot. Not responsible for: sockets and the protocol (CameraConnection.kt, CameraApi.kt), Android networking (WifiBinder.kt), drawing anything (UI files) or creating the app-wide instance (SessionProvider.kt). | 0.1.3 | yes |
| `app/src/main/java/com/example/yicam/SessionProvider.kt` | Responsible for: creating the one CameraSession of the app, so the link and the status survive screen rotation. Not responsible for: the behaviour of the session (CameraSession.kt). | 0.0.3 | yes |
| `app/src/main/java/com/example/yicam/ProbeScreen.kt` | Responsible for: the probe screen (dev tool, English text only): connection buttons, canned and raw commands, the RTSP trace button, log view, Share log, a way back to the status screen. Not responsible for: any network or protocol logic (ProbeController.kt, CameraSession.kt) or the localized status screen (StatusScreen.kt). | 0.1.1 | yes |
| `app/src/main/java/com/example/yicam/StatusScreen.kt` | Responsible for: the localized status screen: connection state with a reason, battery, SD card, camera mode, the read-only list of camera settings, Connect/Disconnect/Refresh buttons, the Live view button and the menu entry to the probe screen. Not responsible for: any link or protocol logic (CameraSession.kt), the dev probe screen (ProbeScreen.kt) or the texts themselves (the strings.xml files of values, values-es and values-uk). | 0.1.0 | yes |
| `app/src/main/res/values/strings.xml` | Responsible for: the English texts of the app (default language). Not responsible for: the Spanish and Ukrainian texts (values-es, values-uk) or any code. | 0.1.0 | yes |
| `app/src/main/res/values-es/strings.xml` | Responsible for: the Spanish texts of the app. Not responsible for: the English default texts (values) or the Ukrainian texts (values-uk). | 0.1.0 | yes |
| `app/src/main/res/values-uk/strings.xml` | Responsible for: the Ukrainian texts of the app. Not responsible for: the English default texts (values) or the Spanish texts (values-es). | 0.1.0 | yes |
| `app/src/test/java/com/example/yicam/FakeCameraNetwork.kt` | Responsible for: a fake CameraNetwork for tests: it hands out the default socket factory, can be told to fail, and can announce that the network was lost. Not responsible for: assertions or any real Android networking. | 0.0.3 | yes |
| `app/src/test/java/com/example/yicam/BackoffTest.kt` | Responsible for: unit tests of Backoff (the steps, the cap and the plus or minus 20 percent spread). Not responsible for: tests of the reconnect loop itself (CameraSessionTest.kt). | 0.0.3 | yes |
| `app/src/test/java/com/example/yicam/StatusParserTest.kt` | Responsible for: unit tests of StatusParser on real camera replies (battery, SD memory, settings, secrets left out). Not responsible for: tests of sockets or the session (CameraApiTest.kt, CameraSessionTest.kt). | 0.0.3 | yes |
| `app/src/test/java/com/example/yicam/CameraSessionTest.kt` | Responsible for: integration tests of CameraSession against FakeCameraServer and FakeCameraNetwork (status reading, polling, events, reconnect, camera off, errors that stop retrying, background pause, disconnect). Not responsible for: tests of the protocol layer (CameraApiTest.kt) or of the pure helpers (BackoffTest.kt, StatusParserTest.kt). | 0.1.2 | yes |
| `app/src/main/java/com/example/yicam/LiveState.kt` | Responsible for: the states of the live view (idle, starting the preview, connecting the stream, playing, problem with a reason) and the list of problems. Not responsible for: changing the state (LiveViewController.kt) or the texts shown for them (StatusScreen.kt, LiveScreen.kt, res/values folders). | 0.1.0 | yes |
| `app/src/main/java/com/example/yicam/StreamPlayer.kt` | Responsible for: the contract of a video player that plays one RTSP stream and reports the first picture or an error, so the live view logic can be tested without Android. Not responsible for: any player implementation (ExoRtspPlayer.kt) or the live view logic (LiveViewController.kt). | 0.1.2 | yes |
| `app/src/main/java/com/example/yicam/ExoRtspPlayer.kt` | Responsible for: playing the camera's RTSP stream with Media3 ExoPlayer (RTP over TCP, small buffers) into a SurfaceView, and logging the player's states and errors. Not responsible for: starting the preview on the camera or retrying (LiveViewController.kt) or the screen layout (LiveScreen.kt). | 0.1.2 | yes |
| `app/src/main/java/com/example/yicam/LiveViewController.kt` | Responsible for: the live view logic: checking that a preview is possible, starting it on the camera (msg_id 259), starting the player on the RTSP stream (UDP: the camera refuses TCP), waiting for the first picture, noticing a frozen or ended stream or a lost link, pausing when the camera starts recording and resuming after it, retrying, and restarting a stream that breaks. Not responsible for: the video decoding itself (StreamPlayer.kt, ExoRtspPlayer.kt), drawing (LiveScreen.kt) or the link to the camera (CameraSession.kt). | 0.1.3 | yes |
| `app/src/main/java/com/example/yicam/LiveScreen.kt` | Responsible for: the localized live view screen: the video surface (kept in the picture's aspect ratio), a status line, the technical detail of a problem, Start and Stop buttons. Not responsible for: the live view logic (LiveViewController.kt), the player (ExoRtspPlayer.kt) or the texts themselves (the strings.xml files of values, values-es and values-uk). | 0.1.0 | yes |
| `app/src/test/java/com/example/yicam/FakeStreamPlayer.kt` | Responsible for: a fake StreamPlayer for tests: it records starts and stops and lets the test announce a first picture or an error. Not responsible for: assertions or any real video playback. | 0.1.2 | yes |
| `app/src/test/java/com/example/yicam/LiveViewControllerTest.kt` | Responsible for: integration tests of LiveViewController with a real CameraSession against FakeCameraServer and a FakeStreamPlayer (preview command, blocked cases, retries, timeouts, restart after a break, stop). Not responsible for: tests of the link (CameraSessionTest.kt) or of real video playback (done by hand on a phone). | 0.1.3 | yes |
| `docs/test-plan-0.1.0.md` | Setup steps, manual phone checks and decision rules for the live view test. | 0.1.3 | n/a |
| `app/src/main/java/com/example/yicam/RtspProbe.kt` | Responsible for: a one-off RTSP trace for diagnosis: OPTIONS, DESCRIBE, SETUP (RTP over TCP, several URL guesses), PLAY and a 3-second read of the interleaved data, every step written to the log. Not responsible for: playing video (ExoRtspPlayer.kt), starting the preview on the camera (ProbeController.kt) or any decision (LiveViewController.kt). | 0.1.1 | yes |
| `docs/test-plan-0.1.0.uk.md` | Ukrainian translation of the 0.1.0 test plan. | 0.1.3 | n/a |

## Files created by Android Studio and not edited by us

The checker skips these paths (glob patterns, one per line). Remove a pattern when we start editing those files.

```generated-ignore
app/src/main/res/mipmap-*
app/src/main/res/drawable*
app/src/main/res/values/colors.xml
app/src/main/res/values/themes.xml
app/src/main/res/values-night/*
app/src/main/res/xml/backup_rules.xml
app/src/main/res/xml/data_extraction_rules.xml
app/src/main/java/com/example/yicam/ui/theme/*
app/src/test/java/com/example/yicam/ExampleUnitTest.kt
app/src/androidTest/*
```
