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
The probe screen (MainActivity.kt) is a dev tool: its text is hardcoded English. Localized strings (EN/ES/UK in resources) start with the first real screen.

| File | Responsible for | Last changed | Header in file |
|---|---|---|---|
| `app/src/main/java/com/example/yicam/MainActivity.kt` | Responsible for: the probe screen (dev tool, English text only): buttons, raw command field, log view, local-network permission request, Share log. Not responsible for: any network or protocol logic (ProbeController.kt, CameraApi.kt). | 0.0.2 | yes |
| `app/src/main/java/com/example/yicam/ProbeController.kt` | Responsible for: the state and actions behind the v0.0.1/0.0.2 probe screen: connect via Wi-Fi binding, canned probe commands, raw commands, log access. Survives screen rotation. Not responsible for: drawing the screen or asking for permissions (MainActivity.kt) or the protocol itself (CameraApi.kt). | 0.0.2 | yes |
| `app/src/main/java/com/example/yicam/WifiBinder.kt` | Responsible for: finding the camera's Wi-Fi network (phone address 192.168.42.x) and binding the app process to it. Not responsible for: opening sockets (CameraConnection.kt) or asking for permissions (MainActivity.kt). | 0.0.2 | yes |
| `app/src/main/java/com/example/yicam/CameraConnection.kt` | Responsible for: one TCP connection to the camera port: open/close, a reader that turns bytes into JSON objects, serialized writes, connection diagnostics in the log. Not responsible for: tokens, matching replies to requests, timeouts of commands, reconnect decisions (CameraApi.kt), or finding the Wi-Fi (WifiBinder.kt). | 0.0.2 | yes |
| `app/src/main/java/com/example/yicam/CameraApi.kt` | Responsible for: the camera session: connect/reconnect, the session token, one command in flight at a time, reply matching by msg_id, timeouts, one automatic token refresh on rval -4, and the stream of camera events. Not responsible for: sockets and framing (CameraConnection.kt), UI, Wi-Fi binding, or the meaning of individual commands' replies (later versions). | 0.0.2 | yes |
| `app/src/main/java/com/example/yicam/CameraMessage.kt` | Responsible for: the model of camera JSON messages (reply, event) and the conversion between text and these objects, including building requests. Not responsible for: sockets, timeouts, tokens or waiting for replies (CameraApi.kt). | 0.0.2 | yes |
| `app/src/main/java/com/example/yicam/MiniJson.kt` | Responsible for: a small JSON reader and writer (objects, arrays, strings, numbers, booleans, null) with no Android dependency. Not responsible for: camera message meaning (CameraMessage.kt) or cutting a stream into objects (JsonStreamParser.kt). | 0.0.2 | yes |
| `app/src/main/java/com/example/yicam/JsonStreamParser.kt` | Responsible for: cutting a continuous byte stream into complete top-level JSON objects (text), however the bytes are chunked. Not responsible for: understanding the objects (MiniJson.kt, CameraMessage.kt) or any camera commands. | 0.0.2 | yes |
| `app/src/main/java/com/example/yicam/Cmd.kt` | Responsible for: the list of camera command msg_id values (the only place with these numbers) and their reply timeouts. Not responsible for: building or sending requests (CameraMessage.kt, CameraApi.kt). | 0.0.2 | yes |
| `app/src/main/java/com/example/yicam/ConnectionState.kt` | Responsible for: the possible states of the camera connection, as shown to the UI. Not responsible for: changing the state (CameraApi.kt does that). | 0.0.2 | yes |
| `app/src/main/java/com/example/yicam/CameraException.kt` | Responsible for: the error types of camera communication. Not responsible for: deciding when they are thrown (CameraApi.kt, CameraConnection.kt) or how they are shown (UI). | 0.0.2 | yes |
| `app/src/main/java/com/example/yicam/TrafficLog.kt` | Responsible for: the in-memory ring buffer of raw traffic and events, with secrets masked, observable by the UI and exportable as text. Not responsible for: sockets, parsing, or showing the log. | 0.0.2 | yes |
| `app/src/main/java/com/example/yicam/LogMasker.kt` | Responsible for: hiding secrets (Wi-Fi password, serial number) in text that goes to the log. Not responsible for: storing or showing the log (TrafficLog.kt). | 0.0.2 | yes |
| `app/src/main/res/xml/network_security_config.xml` | Allows cleartext HTTP/RTSP only for 192.168.42.1. | 0.0.1 | no header yet |
| `app/src/main/AndroidManifest.xml` | Permissions (INTERNET, ACCESS_NETWORK_STATE, CHANGE_NETWORK_STATE, ACCESS_LOCAL_NETWORK) and network security config reference. | 0.0.1 | n/a (generated) |
| `app/build.gradle.kts` | Module build settings and dependencies; 0.0.2 added the coroutines dependency. | 0.0.2 | n/a (generated) |
| `app/src/test/java/com/example/yicam/CameraApiTest.kt` | Responsible for: integration tests of CameraApi + CameraConnection against FakeCameraServer (session, token, -4 refresh, timeouts, events, fragmentation, reconnect, serialization, raw commands, log masking). Not responsible for: unit tests of the parser, JSON or masking (their own test files). | 0.0.2 | yes |
| `app/src/test/java/com/example/yicam/FakeCameraServer.kt` | Responsible for: a scriptable fake camera (TCP server on localhost) for integration tests: session token, canned replies from real logs, and switches for faults. Not responsible for: assertions (the tests do that) or real camera behaviour beyond what the logs showed. | 0.0.2 | yes |
| `app/src/test/java/com/example/yicam/Fixtures.kt` | Responsible for: real camera messages copied from phone logs of 2026-10-12 (YDXJ firmware 1.3.0), used as test data. Not responsible for: any test logic. | 0.0.2 | yes |
| `app/src/test/java/com/example/yicam/JsonStreamParserTest.kt` | Responsible for: unit tests of JsonStreamParser (chunking, glued objects, braces and escapes inside strings, UTF-8, garbage, size limit, fuzz). Not responsible for: tests of JSON meaning or the camera session. | 0.0.2 | yes |
| `app/src/test/java/com/example/yicam/MiniJsonTest.kt` | Responsible for: unit tests of MiniJson (parsing, writing, escaping, errors). Not responsible for: stream splitting or camera message meaning. | 0.0.2 | yes |
| `app/src/test/java/com/example/yicam/LogMaskerTest.kt` | Responsible for: unit tests of LogMasker and TrafficLog (secret masking, line format, ring buffer). Not responsible for: sockets or camera sessions. | 0.0.2 | yes |
| `app/src/test/java/com/example/yicam/CameraMessageTest.kt` | Responsible for: unit tests of CameraMessage (reply/event classification, tolerant param types, request encoding) on real camera messages. Not responsible for: sockets or sessions. | 0.0.2 | yes |
| `tools/check_files_index.py` | Checking that docs/FILES.md lists every .kt/.xml source file and that file headers match the index. Not responsible for: building the project or checking code quality. | 0.0.2 | n/a (script) |
| `docs/FILES.md` | This index. | 0.0.2 | n/a |
| `docs/protocol-findings.md` | Facts observed on the real camera (formats, timings, events). Not responsible for: plans or decisions. | 0.0.2 | n/a |
| `CHANGELOG.md` | Change log per version. | 0.0.2 | n/a |

## Files created by Android Studio and not edited by us

The checker skips these paths (glob patterns, one per line). Remove a pattern when we start editing those files.

```generated-ignore
app/src/main/res/mipmap-*
app/src/main/res/drawable*
app/src/main/res/values*
app/src/main/res/xml/backup_rules.xml
app/src/main/res/xml/data_extraction_rules.xml
app/src/main/java/com/example/yicam/ui/theme/*
app/src/test/java/com/example/yicam/ExampleUnitTest.kt
app/src/androidTest/*
```
