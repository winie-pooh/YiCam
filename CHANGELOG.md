# CHANGELOG

## 0.1.5 - the live view comes back by itself after the screen was off
- Phone log (M12): rotation no longer restarts the stream; after the screen went off the stream stopped (by design) and had to be started by hand.
- Now: going to the background (screen off) stops the stream but remembers that it was wanted; returning waits up to 10 s for the link to the camera and starts the stream again. A stream stopped by the user (Stop, Back) is not resumed.
- Tests: background_stopsTheStream_andReturningStartsItAgain, stoppedByTheUser_isNotResumedWhenTheAppReturns. Not compiled or run here.

## 0.1.4 - rotation keeps the live view playing
- Phone log: a rotation released the player and started the stream again within 50 ms (picture back after about 0.5 s), although configChanges is in the manifest, so the activity is probably recreated anyway.
- LiveProvider (new) keeps the player and the LiveViewController for the whole process; the activity and the screen no longer stop the live view when they are going away because of a configuration change; the screen starts the stream only if it is not already running. A new surface is attached to the running player. Not compiled or run here.

## 0.1.3 - recording while the live view is open
- User account of the two Wi-Fi losses: the camera had been left alone for about 3 minutes, then a recording was started and stopped while the live view was open. The log agrees: both times the camera Wi-Fi vanished 20-40 s after video_record_complete, with the RTSP session still open on the phone (the preview stops with vf_stop when the recording starts, and the old controller never closed the session). The cause is not proven.
- Now: when the camera sends vf_stop or start_video_record, the player is closed at once (the RTSP session ends cleanly), the screen shows "not available while the camera is recording", and the stream is started again by itself 1.5 s after the camera sends vf_start (waits up to 5 minutes).
- CameraSession.cameraEvents (new): every message the camera sends on its own is available to screens.
- Test: recordingStartsWhilePlaying_closesThePlayer_andResumesAfterTheCameraIsBack. Test plan: new check M11 (English and Ukrainian). Not compiled or run here.

## 0.1.2 - fixes after the first live view test on a phone (Android 17)
- The camera answers "SETUP 461" (unsupported transport) to RTP over TCP, on every attempt; UDP works and the first picture came 0.5-0.7 s after the preview command. The stream now starts with UDP at once (LiveViewController.Config.useTcp = false), so the useless first attempt and the 1.5 s retry pause are gone.
- A frozen picture was left on screen when the camera stopped sending: the player stayed in BUFFERING and later ended, and nothing reacted. Now: the player's "buffering" and "ended" are passed on (StreamPlayer.Listener.onBuffering/onEnded); buffering for 5 s without data counts as a frozen picture and the stream is restarted; a stream that ends is restarted; a lost link to the camera stops the live view at once with "the camera is not connected".
- msg_id 259 answered rval -21 whenever the camera was recording (seen twice). It is now shown as "not available while recording" instead of "the camera did not start the preview".
- CameraSession reads the settings again 0.7 s after any camera event other than battery (start_video_record, vf_stop, video_record_complete, vf_start, switch_to_cap_mode, switch_to_rec_mode), so the camera mode on the status screen and the "recording" check are no longer stale.
- Tests added: streams start without forced TCP; camera busy (-21) is shown as recording; a frozen picture is restarted; short buffering pauses are ignored; an ended stream is restarted; a lost link stops the live view; a camera event refreshes the mode. Not compiled or run here.

## 0.1.1 - the first live view run on a phone failed with "ERROR_CODE_IO_UNSPECIFIED: Source error"
- That message is Media3's generic wrapper; the real reason was hidden. Now the player error shows the whole chain of causes (on screen and as a [LIVE] log line).
- The second start attempt no longer forces RTP over TCP: the player chooses the transport itself (UDP first). The first attempt still uses TCP. Both are logged.
- RtspProbe (new) and an "RTSP trace" button on the probe screen: starts the preview, then writes a full RTSP conversation with the camera (OPTIONS, DESCRIBE with SDP, SETUP with several address guesses, PLAY, and how many media frames arrive in 3 s) to the log. This shows which step the camera refuses and which SETUP address works.
- Test plan: updated "If the picture does not appear"; Ukrainian translation added (docs/test-plan-0.1.0.uk.md).
- Tests: secondAttempt_letsThePlayerChooseTheTransport; FakeStreamPlayer records the transport. Not compiled or run here.

## 0.1.0 - live view (first version, not yet run on a phone)
- LiveViewController (new): checks that a preview is possible (connected, not recording, preview not switched off), sends msg_id 259 `none_force`, starts the player on rtsp://192.168.42.1:554/live, waits for the first picture (12 s), retries once, restarts a stream that breaks while playing, and measures the time from Start to the first picture.
- ExoRtspPlayer (new): Media3 ExoPlayer with RTP over TCP and small buffers (0.1 s to start, 1 s at most); logs player states, video size and errors as [LIVE] lines. StreamPlayer is the interface the controller uses, so the logic is tested with a fake player.
- LiveScreen (new, EN/ES/UK): video kept in the picture's aspect ratio, status text, technical detail of a problem, Start/Stop. A "Live view" button was added to the status screen. The live view stops when the app goes to the background.
- The camera's preview is not stopped when the live view is stopped: no command for that is known yet (to be tried with the probe screen).
- Needs two manual edits: Media3 dependencies in app/build.gradle.kts and configChanges in the manifest (docs/test-plan-0.1.0.md).
- Tests: LiveViewControllerTest (8 cases), FakeStreamPlayer; FakeCameraServer got appStatus and previewRval.
- Open risk: the camera's RTSP reply has a broken Content-Base header; whether Media3 copes is only known after a phone test (docs/test-plan-0.1.0.md has the decision rules: Media3, then libVLC, then an own RTSP client).

## 0.0.3.1 - fixes after the first phone run of 0.0.3 (Android 17)
- SD card: on the first connection the camera itself answered total 17490 KB / free 17414 KB (a 17 MB "card") although sd_card_status was "insert"; on the next connection it answered 30317536 / 20959712. The app showed exactly what the camera said. Now a total below 512 MB (Config.minPlausibleSdKb) is not shown as a card: the status says "Not usable: the camera reports only 17.9 MB" and the SD size is read again every 5 s (12 times, then every 60 s) until it looks real. New field CameraStatus.sdUnusableReportedKb; new string sd_not_usable (EN, ES, UK).
- CameraOff: after the first (short) attempt there was a blind pause of about 25 s in which a returning Wi-Fi was not noticed. Now the pause is 1 s and the next attempt itself waits up to 30 s for the Wi-Fi, so recovery is immediate; an attempt that fails at once still waits 30 s so it cannot spin.
- After a loss, "the phone is on another Wi-Fi" is no longer final (the phone falls back to the home Wi-Fi when the camera Wi-Fi goes away): the session keeps waiting. Before the first successful connection it is still an error. This differs from the 0.0.3 brief.
- WifiBinder logs the "not the camera's Wi-Fi" warning once per address list instead of twice.
- Tests: tinyCardSize_isNotShownAsACard_andIsCheckedAgainUntilItIsReal, afterALoss_anotherWifi_isNotFinal_theSessionKeepsWaiting; FakeCameraServer got tinySd, Fixtures got the real small-card replies.

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
