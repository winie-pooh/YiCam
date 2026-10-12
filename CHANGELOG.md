# CHANGELOG

## 0.0.3 - camera status and a link that repairs itself
- CameraSession (new): owns the link to the camera for the whole app. Finds the camera Wi-Fi, opens the session, reads settings (3), battery (13) and SD memory (5 total/free), then polls battery every 20 s and free space every 60 s; battery events (msg_id 7) update the level at once. Polling and reconnect attempts wait while the app is in the background (MainActivity onStop/onStart).
- Reconnect: delays 1, 2, 4, 8, 16, 30 s (+-20 %, Backoff.kt). A lost socket, a second timeout in a row, or a lost Wi-Fi network ends the link and starts a new attempt. If the camera Wi-Fi does not come back within 5 s the state becomes CameraOff and the app waits for the Wi-Fi (one attempt per 30 s, instant reaction when the Wi-Fi returns).
- No retrying when: the user pressed Disconnect, the local network permission was denied, the phone is on another Wi-Fi, or the very first Connect finds no camera Wi-Fi (shown as an error instead).
- SessionState (new) replaces ConnectionState for the UI: Disconnected, SearchingWifi, PermissionRequired, Connecting, Connected, Reconnecting(attempt, delay, reason), CameraOff, Error(reason). ConnectionState stays as the low-level state inside CameraApi (the 0.0.2 tests still use it). This differs from the brief only in the name.
- WifiBinder now implements CameraNetwork (new interface, so the session is testable without Android) and reports when the bound network is lost. New CameraException.WifiNotFound tells "no Wi-Fi" from "other Wi-Fi".
- StatusParser, CameraStatus, SettingsSnapshot: tolerant parsing of battery/SD/settings replies; Wi-Fi password and serial number are never stored.
- UI: StatusScreen (first real screen, localized EN/ES/UK in res/values, values-es, values-uk) with connection card, battery, SD, camera mode, read-only settings list, Connect/Disconnect/Refresh. The probe screen moved to ProbeScreen.kt and is reachable from the menu. ProbeController is now a thin layer over the session. SessionProvider creates the one session of the app.
- Tests: BackoffTest, StatusParserTest, CameraSessionTest (status, secrets, events, refresh, polling, background pause, drop and reconnect, Wi-Fi lost -> CameraOff -> back, errors that stop retrying, disconnect). FakeCameraNetwork added; FakeCameraServer answers msg_id 5 by type.
- Not verified on a phone: everything above. Not compiled or run in the environment where it was written (no Kotlin compiler there). Run `./gradlew testDebugUnitTest` first.
- FILES.md: res/values* is no longer ignored by the checker (strings.xml files are ours); colors.xml, themes.xml and values-night stay ignored.

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
