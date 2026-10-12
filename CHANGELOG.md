# CHANGELOG

## 0.0.2 - reliable command exchange
- JsonStreamParser rewritten: works on bytes, honours braces/quotes/escapes inside strings, UTF-8 safe, skips garbage, drops oversized objects.
- MiniJson (reader/writer without Android dependency), CameraMessage (reply / event / malformed, tolerant `param` types, request encoding with `\/`).
- CameraApi: session token, one command in flight (queue), reply matching by msg_id, timeout per command (5 s, 15 s for file listing), one automatic token refresh on rval -4, event stream (msg_id 7), reconnect on the next command, explicit disconnect.
- CameraConnection rewritten: socket from the camera network's socket factory, reader on a background coroutine, logs connect, end of stream and read errors; the old socket is always closed before a new connect.
- Cmd enum is the only place with msg_id numbers. ConnectionState and CameraException describe the state and errors.
- TrafficLog (ring buffer, observable) and LogMasker (Wi-Fi password and serial number, also in set commands) replace the log inside the old connection class.
- Probe screen: Disconnect, Clear log, state line, raw commands go through CameraApi ("/" can be typed as is, %TOKEN% is replaced, an explicit "token" is sent unchanged to test -4).
- ProbeController keeps the log and connection across screen rotation. WifiBinder rewritten with the same behaviour; also unbinds on Disconnect.
- Tests: unit tests (parser incl. fuzz, JSON, masking, messages) and integration tests against FakeCameraServer built from real log lines: fragments, glued reply+event, -4 refresh, silent camera, late reply, dropped connection, reconnect, 8 concurrent senders, raw commands, log masking.
- tools/check_files_index.py checks docs/FILES.md against the sources and file headers.
- docs/protocol-findings.md created from the phone probe logs.
- Docs: the permission list of 0.0.1 in this file set is the four lines in the manifest (INTERNET, ACCESS_NETWORK_STATE, CHANGE_NETWORK_STATE, ACCESS_LOCAL_NETWORK); ACCESS_WIFI_STATE from the plan is not needed. Build: app/build.gradle.kts gets the coroutines dependency.

## 0.0.1 - probe app
- Android Studio project skeleton (Kotlin, Jetpack Compose).
- WifiBinder: finds the camera Wi-Fi and binds the process to it.
- CameraConnection + JsonStreamParser: TCP session to port 7878, token handling, raw commands, log.
- Probe screen: Connect, Settings (3), Battery (13), Preview (259), cd DCIM (1283), ls (1282), Share log.
- Local network permission request for Android 17+ (targetSdk 37+).
- docs/FILES.md and this changelog created.
