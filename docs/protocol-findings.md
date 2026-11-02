# Protocol findings (Xiaomi Yi Action Cam, original, firmware YDXJv22L_1.3.0, hw YDXJ_v25L)

Facts seen on the real camera. Only observations, no plans. Source: phone probe logs of 2026-10-12 (v0.0.1 on Android SDK 37), plus earlier PC probes described in the plan (sections 10 and 11).
The camera Wi-Fi gives the phone 192.168.42.2; the camera is 192.168.42.1, JSON port 7878.

## Session and token
- `{"msg_id":257,"token":0}` returns `{ "rval": 0, "msg_id": 257, "param": 1 }`. `param` is a NUMBER.
- Every new session on the same power cycle got the next number: 1, then 2 on the second Connect. The token is therefore not a constant.
- Reply time of 257: 0.05 s on three runs, 1.2 s once.

## Value types in replies (parse tolerantly)
| Command | `param` is |
|---|---|
| 257 session | number (`1`) |
| 13 battery | string (`"82"`) |
| 3 settings | array of one-key objects |
| 1283 change dir | no `param`; the reply has `pwd` (string, `/` written as `\/`) |
| 259 preview | no `param` |

## Reply formatting differs between commands
- Some replies are spaced: `{ "rval": 0, "msg_id": 3, ... }`. Others are compact: `{"rval":0,"msg_id":13,...}`.
- Events have an odd space: `{ "msg_id": 7, "type": "battery" ,"param":"82"}`.
- `/` is written as `\/` in both directions. The stream has no delimiters; several objects can arrive in one chunk.

## Events (msg_id 7)
- `type: "battery"`, `param: "<percent>"`: sent when the level changes (82, 81, 78 seen), not on a fixed timer.
- `type: "vf_start"` (no `param`): arrives about 1.1 s after the reply to 259 `none_force`.
- The camera may send other types; unknown ones must be logged and ignored, never crash.

## Latency seen from the phone
| msg_id | Reply time |
|---|---|
| 13 battery | 0.1 s |
| 1283 change dir | 0.08 s |
| 3 settings | 0.5 s (55 keys) |
| 1282 list, `-D -S` on DCIM | 0.44 s |
| 259 preview | 1.3 s |

## File listing (1282 with `-D -S`)
- Reply: `{"rval":0,"msg_id":1282,"listing":[{"101MEDIA/":"0 bytes|2025-10-14 17:05:56"}, ...]}`.
- Each element is an object with ONE key (the name; folders end with `/`) and the value `"<size> bytes|<date time>"`. Folders are always `0 bytes`.
- Order is not sorted: 101MEDIA/, Camera/, EOSMISC/, 102MEDIA/.
- Dates are unreliable: the camera clock was 2008-03-23, while some folders carry 2024/2025 dates.
- Folder contents (files) were not seen in the phone probe; see plan section 11.1 for the PC probe results.

## Delete command [VERIFY]
- Not seen on this camera. The 0.4.0 probe screen can send candidate `msg_id 1281` with `param` = absolute path, then `1283` to the parent folder and `1282 -D -S`. Use a sacrificial photo. Record: rval of 1281, the raw reply, and whether the file is gone from the next listing. Do not add 1281 to Cmd.kt until that is confirmed.

## HTTP downloads sleep without the viewfinder (from the PC program, 0.4.1.1) [VERIFY on this camera]
- Found in the Xiaomi_Yi PC program (deltaflyer4747, `CC.pyw` `FileDownloadThread`): before every file download it sends `{"msg_id":259,"param":"none_force","token":...}`, with the comment "fix for web port by luckylz" (dashcamtalk forum thread "yicam can not visit by http webport any more"). The camera's HTTP server (port 80, Cherokee) stops answering while the camera is not in viewfinder mode; msg_id 259 wakes it.
- This matches the phone behaviour: listings (msg_id 1282, JSON port 7878) and battery work any time, but an HTTP download from the files screen failed unless a live view had been opened earlier in the session. Once started, the camera stays in viewfinder mode (no stop command is known, see 0.1.0), which is why downloads then kept working.
- 0.4.1.1 sends msg_id 259 `none_force` before every download and waits 0.8 s. This does not start the phone player. [VERIFY: a fresh app start, files screen, download without ever opening the live view must succeed; note the wake-up answer and the HTTP answer code in the log.]

## Live view on a phone (log of 2026-10-13, Android 17, Media3 1.8.0)
- RTSP over TCP: SETUP is answered "461" (unsupported transport) every time. The camera does not do interleaved TCP. UDP (the player's default) works: video 640x480, first picture 0.46-0.74 s after the preview command.
- The user measured a delay of about 3-4 s (not yet split into camera, Wi-Fi and player parts) and saw stripes of glitches about every 10-15 minutes in poor light only (none on another day): probably UDP packet loss when the encoder makes bigger frames.
- msg_id 259 answered rval -21 while the camera was recording a video (twice).
- Events seen (msg_id 7): start_video_record, vf_stop (recording started, the preview stops), video_record_complete (param = file path), vf_start, switch_to_cap_mode, switch_to_rec_mode.
- The camera Wi-Fi vanished twice while the live view was open: about 17 s after switch_to_rec_mode (the stream had stalled 6 s before), and 10 s after switch_to_cap_mode. The camera clock went on without a reset (20:24:42 at 10:14:19, 20:32:27 at 10:22:04), so the camera did not reboot. User's account: the camera was left alone for about 3 minutes, then recording was started and stopped while the live view was open. In both losses a video_record_complete came 20-40 s earlier. Working theory (not proven): the camera loses its Wi-Fi after a recording ended while an RTSP session from before the recording was still open. The camera clock is not proof of uptime (it seems to run on without a reset), so a camera power-off cannot be ruled out; both of the 3-minute stretches with no button presses (10:14:19-10:17:20 and 10:22:04-10:25:23) passed with the camera still on while the app polled it every 20 s.
- After the camera Wi-Fi vanished the phone fell back to the home Wi-Fi and did not return to the camera Wi-Fi by itself.
- With UDP, when the camera stopped sending the player stayed in BUFFERING and later reached ENDED; the old controller kept the last frame on screen.

## SD card size (phone log of 2026-10-12, Android 17, v0.0.3)
- First connection: msg_id 5 type total gave 17490, type free gave 17414 (kilobytes), the same again 30 s later, while the settings said sd_card_status "insert" and sdcard_need_format "no-need".
- After the camera Wi-Fi was lost and the phone reconnected (camera clock continued without a reset: 07:19 to 07:21), the answers were total 30317536 and free 20959712 (a 32 GB card, 28.9 GiB).
- So the camera can report a tiny "card" of about 17 MB while sd_card_status still says insert. Cause not known (no card, a different card, or a card that was not mounted yet). The app does not show such a size as a card.
- Android 17 phone: the camera network was found, bound and used for TCP without any extra step visible in the log.

## Camera Wi-Fi loss seen from the phone
- The phone reported the loss of the camera network at 21:09:30.876, 0.4 s after the last battery reply; the app showed "camera off" 6.2 s later.

## Camera freeze (incident)
- In the first phone run the camera answered the first Settings request and then stopped answering anything; its own buttons did not react either. The app showed nothing about it (fixed in 0.0.2: timeouts and connection events are logged).
- The next run was normal. Cause unknown. If it happens again, save the log from Connect on.

## Not yet verified
Whether polling (battery every 20 s) keeps the camera awake with auto_power_off = 3 minutes; how fast Android reports the loss of the camera Wi-Fi after the camera is switched off; what a socket error looks like on Android 17 without the local network permission; whether `type: adapter` in the reply of msg_id 13 means the charger is connected (0.0.3 assumes so); whether app_status changes are visible without re-reading msg_id 3 (0.0.3 reads it on connect and on Refresh only).
Token lifetime after idle; preview during recording; zoom command rate; `.THM` format; EXIF thumbnails in camera photos (plan section 11.4).
