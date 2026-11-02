# YiCam roadmap 0.4.0 -> 1.0.0 (extended, for AI executors)

Language: English only (code, docs, test plans). The user is answered in Ukrainian in chat.
Status of this plan: DRAFT for the user to correct. Nothing here is implemented.

## 0. Starting point (version 0.3.6)
Done and tested on a phone (Android 17) and on the JVM (~173 unit tests, FakeCameraServer):
link and session (CameraSession, reconnect, CameraOff), status (battery, SD, mode), live view over UDP (also during a recording), controls (photo, recording, time-lapse stop, modes, zoom), settings screen (read, change, translated values, unlock), clock set at every connect, EN/ES/UK resources, probe screen (dev tool).

Project rules that stay in force:
- Kotlin + Jetpack Compose, minSdk 26, tested on Android 16/17. One activity. No new libraries unless the roadmap says so.
- Every source file starts with a header comment: "Responsible for: ... Not responsible for: ...". `docs/FILES.md` (index), `CHANGELOG.md` and the header are updated in the same change. `python tools/check_files_index.py` must pass.
- All UI text only in `res/values/strings.xml` (English), `values-es`, `values-uk`; a string added to one file is added to all three.
- Logic is separated from Android so it can be unit-tested on the JVM with fakes (pattern: interface + fake, FakeCameraServer, real camera replies as fixtures).
- Never invent protocol facts: unknown = `[VERIFY]`, check on the real camera, write the result into `docs/protocol-findings.md`.
- Report format for every version: what changed (short list), unit test count (from `app/build/test-results/testDebugUnitTest/*.xml`), what was verified on a phone / only by tests / not at all.
- The process is bound to the camera Wi-Fi (`bindProcessToNetwork`), so plain `HttpURLConnection` / sockets reach 192.168.42.1. Cleartext is allowed only for 192.168.42.1 (network security config). On Android 17 the local-network permission is already handled.

## 1. Protocol facts for files [PROBED unless marked]
- Folder change: `msg_id 1283` `{"param": "<absolute path>"}` -> `{"pwd": "..."}`. The "current folder" is global state of the camera: always `cd` to an absolute path before listing.
- Listing: `msg_id 1282` with `{"param": "-D -S"}` lists the CURRENT folder: `{"listing":[{"101MEDIA/":"0 bytes|2025-10-14 17:05:56"}, ...]}`. Value = `size|date`; names ending in `/` are folders (size always `0 bytes`). The list is NOT sorted. ~0.6 s for 100+ files; use a 15 s timeout.
- Media root `/tmp/fuse_d/DCIM`. Folders seen: `101MEDIA`, `102MEDIA` (the camera's own), `Camera` (from another device, contains hidden `.AutoPortrait/`), `EOSMISC` (system). Hide every folder starting with `.` and `EOSMISC`.
- File names `YDXJ####.mp4`, `YDXJ####.jpg`. Thumbnails `YDXJ####.THM` (upper case, 40-100 KB, probably JPEG [VERIFY]) exist only for some videos, never for photos.
- HTTP: Cherokee server, `http://192.168.42.1:80/DCIM/<folder>/<file>`. `Range: bytes=a-b` -> `206` + `Content-Range`; `ETag` present; `Date` headers say 1970 (ignore). The HTML directory listing exists; do not parse it (use 1282).
- Sizes reach 1.4 GB: always `Long`.
- The camera clock is unreliable (reset on power-off; the app sets it at connect since 0.3.5, older files may still have wrong dates): sort by the number in `YDXJ####`, show the date only as text.
- Fallback channel `msg_id 1285` on data port 8787: not needed.
- Unknown [VERIFY]: a delete command; whether 1282 and HTTP stay fast during a recording; real HTTP throughput; whether a download hurts the live view or the heartbeat; format of `.THM`; whether JPEGs carry an EXIF thumbnail in the first 64 KB.
- The camera is a weak single server. Limits: ONE big download at a time, at most TWO parallel thumbnail fetches, the JSON heartbeat (battery poll) keeps running.

## 2. Versions

### 0.4.0 Files: browse (read only)
Goal: the user sees the camera's folders and files, newest first.
New (names are suggestions):
- `FileEntry(name, folder, sizeBytes: Long, dateText, kind)` and `FileKind {VIDEO, PHOTO, THUMBNAIL, OTHER}`; `FolderEntry`.
- `ListingParser` (pure): parses the 1282 reply; `"123 bytes"` -> Long (also values above 2^31); keeps date as text; sort key = number in `YDXJ(\d+)` (descending), fallback name; drops hidden folders; groups `.THM` as an attribute of its video (not a row).
- `CameraFiles` (session-bound): `folders()`, `files(folder)`: `1283` absolute path, then `1282 -D -S`; serialized by the existing CameraApi mutex; in-memory cache per screen visit; clear error mapping (not connected, timeout, camera busy).
- `FilesScreen` (Compose, localized): folder list -> file list (name, size via `Formatter.formatFileSize`, date text, kind), refresh button, empty and error states; entry button on the status screen.
- Strings EN/ES/UK.
Delete probe (user decision 3, dev tool only): add a guarded raw "delete" action to the Probe screen (type the file name, confirm) to find the camera's delete command on a sacrificial photo. Candidate `msg_id 1281` with the absolute path is only a guess from memory of the original API [VERIFY]; log the full reply, check with a new listing. Write the result into `docs/protocol-findings.md`.
Tests: `ListingParserTest` with real replies (incl. 1.4 GB and a folder named `.AutoPortrait/`); `CameraFilesTest` against FakeCameraServer extended with 1282/1283 (several folders, 150 files, unsorted); sorting; hiding; error cases.
Acceptance: folders in < 2 s, a 100+ file folder in < 3 s; order newest first; no hidden folders; the heartbeat is not delayed by a long listing; during a recording the list still works or shows a clear note.
Manual: open all folders; compare names and sizes with the PC program; list while recording.

### 0.4.1 Download of one file
Goal: tap a file, it is saved on the phone, with progress, speed, cancel and resume.
- `Downloader` (pure, `HttpURLConnection`, no new library): `Range` resume; accept `206` (check `Content-Range` start and total) and `200` (server ignored Range -> restart from 0); `Long` arithmetic; 64 KB buffer; read timeout 15 s; cancellation = coroutine cancellation; speed = moving average over 3 s; remaining time estimate; returns `Flow<DownloadProgress>`.
- `MediaStoreSink`: videos -> `MediaStore.Video`, `RELATIVE_PATH = Movies/YiCam`; photos -> `MediaStore.Images`, `Pictures/YiCam`; `IS_PENDING=1` while writing, `0` at the end; open mode `w`, or `wa` to resume. Resume = find the pending row by `DISPLAY_NAME` + `RELATIVE_PATH`, continue at its size. A finished item with the same name and size = "already downloaded" (offer to skip). The final size must equal the Content-Length total, otherwise keep the pending row and report an error. Interface `DownloadSink` so tests use an in-memory sink.
- Destination (user decision 2): app setting "Download folder": default MediaStore (`Movies/YiCam`, `Pictures/YiCam`) or a folder picked with `ACTION_OPEN_DOCUMENT_TREE` (`takePersistableUriPermission`, `DocumentFile`). The picked folder is written as `name.part` and renamed when complete; resume = length of the `.part` file. Both are `DownloadSink` implementations. Optional "ask every time".
- Resume uses `If-Range` with the ETag; if the server answers 200 the download restarts from 0.
- `DownloadState {Idle, Running(progress), Cancelled, Done(uri), Failed(reason)}`; free-space check (`StatFs`) before start; clear reasons (no space, link lost, size mismatch, camera refused).
- UI: file row -> Download; progress bar with bytes, percent, speed, time left; Cancel; one active download.
- Tests: local `com.sun.net.httpserver.HttpServer` in JVM tests that supports Range and a variant that ignores it; resume after a cut; cancel; size mismatch; speed calculation; sink fake.
Acceptance: a photo, a short video and a > 500 MB video are saved bit-identical (compare size and a checksum with the PC download); cancel then resume continues instead of restarting; link loss ends in a resumable `Failed`.
Manual: Wi-Fi off/on during a download; screen off for 30 s; rotate.

### 0.4.2 Queue and background download
- Multi-select in the file list; sequential queue; "download all new" is optional (skip items already downloaded).
- `DownloadService` as a foreground service (type `dataSync`; manifest permissions `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`; runtime `POST_NOTIFICATIONS` on Android 13+); notification with progress and Cancel; `WifiLock` while running.
- Link loss: the queue pauses and resumes by itself when the session is `Connected` again (Range). Process death: the pending MediaStore rows allow a manual resume; the queue itself is not persisted (decision to confirm).
- Policy live view vs download (to confirm with the user): default = starting a download stops the live view (shared Wi-Fi, weak camera); the UI says so.
- Tests: queue order, pause on link loss, automatic resume, cancel one / cancel all, skip already downloaded.

### 0.5.0 Gallery and viewer
- Grid of thumbnails with filter chips (all / photos / videos) and folder chips.
- `ThumbnailLoader`: at most 2 concurrent fetches; memory `LruCache` + disk cache in `cacheDir/thumbs` keyed by name+size. Strategy: (1) video with `.THM` -> use it; (2) photo -> `Range` first 64 KB and read the EXIF thumbnail with `ExifInterface` [VERIFY]; if none, fetch the full JPEG only for visible items and downscale (`inSampleSize`) then cache; (3) video without `.THM` -> `MediaMetadataRetriever` over HTTP at 1 s [VERIFY, may be slow]; else a placeholder. Visible items first; cancel when scrolled away.
- Viewer: photo (pinch zoom, pan; full JPEG with progress); video: ExoPlayer progressive HTTP (Range, seek) [VERIFY smoothness of 1080p60 over this Wi-Fi]; fallback "download, then play from MediaStore". Decide between `media3-ui` and small custom Compose controls before adding a dependency.
- "Downloaded" badge: MediaStore query by name+size. Select and download from the gallery (reuse 0.4.x).
- Tests: `ThumbnailLoader` with a fake fetcher (concurrency limit, cache hit, cancel), badge logic, filters.

### 0.5.1 Downloaded library
- A tab/screen listing what is already in `Movies/YiCam` and `Pictures/YiCam` (works without the camera); open with the system viewer (`ACTION_VIEW`), share (`ACTION_SEND`).
- Tests for the MediaStore query logic with a fake.

### 0.6.0 Reliability and performance
- Large folders (hundreds of files): paged rendering, memory limits, no ANR.
- Consistent error banners and retry policy on every screen when the Wi-Fi drops or the camera powers off.
- Measure battery and heat during long downloads / live view; stop non-essential polling when it helps.
- Release build: R8 + resource shrinking (keep rules; strings looked up by name must survive), service and permissions check on Android 16/17.
- Accessibility: content descriptions, large fonts, dark/light.

### 0.7.0 QA hardening
- Full regression matrix (Android 16/17, at least two phone sizes), every manual test plan re-run.
- Localization review of ES and UK by a native speaker; lint clean; crash/log export; 8-hour soak (live view + polling); security review (no secrets in logs or notifications).

### 0.8.0 Delete files on the camera (required before 1.0)
- Uses the command found by the 0.4.0 delete probe. Select files (list or gallery) -> Delete -> confirmation dialog with the count and total size; the `.THM` goes with its video; the file being recorded is never offered; irreversible, so the text says so.
- Optional "delete after download": only after the final size equals the camera's size.
- After deleting, re-list the folder to confirm; show failures per file.
- Tests with FakeCameraServer (delete handler), selection logic, the guard for the file being recorded.

### 1.0.0 Release
- Icon, signing, versionName/versionCode, AAB/APK, README, privacy statement (no data leaves the phone), change notes.

## 3. Decisions of the user (v1)
1. Starting a download stops the live view (the camera would heat up / overload). The UI says so.
2. Default save locations `Movies/YiCam` and `Pictures/YiCam`, PLUS the user can choose the download folder (see 0.4.1 "destination").
3. Deleting files on the camera IS required (so 0.8.0 is not optional; the command must be found early, see 0.4.0 "delete probe").
4. Still open: queue persistence (example in chat), name collisions on the phone, everything in section 5.

## 4. Risks and problems to design for
- Heat and overload of the camera: one download at a time, no live view during a download, no thumbnails fetched while a big download runs (or at lowest priority), heartbeat stays (it also keeps the camera awake: `auto_power_off` was 3 minutes).
- Download while the camera records: may lower recording quality or heat the camera. Proposed rule: warn and require a confirmation, or block (to decide).
- Long transfers: 1.4 GB at 1-3 MB/s = 8-25 min. Needs a foreground service, `WifiLock`, notification, Doze awareness; the user may revoke notification permission (the service must still work).
- Camera auto power-off during a long download if HTTP counts as no activity [VERIFY]; the camera battery may die (warn below 20 %, pause below 10 %).
- Resume correctness: the file may change between sessions; use `If-Range` with the ETag (seen as `"65b7bcde=415ec7"`), compare final size; the camera gives no checksum.
- A file that is being recorded right now (size grows): mark it, forbid download and delete.
- Same file names in different folders (101MEDIA/YDXJ0001 and 102MEDIA/YDXJ0001) and counter resets after a card format.
- Storage: free-space check; scoped storage; with a user-chosen folder (SAF) writing is slower and resume uses a `.part` file.
- Delete safety: irreversible; confirmation; multi-select delete; delete the `.THM` with its video; never the file being recorded; "delete after download" only after the size check.
- Thumbnail cost: 100 photos of 4 MB would flood the camera if the EXIF trick does not work [VERIFY]; fallback = placeholders or load on demand.
- Video playback from the camera may stutter (1080p60 over Wi-Fi): "download first" fallback.
- Camera Wi-Fi allows few clients: the PC program or another phone can block the app.
- Camera dates are local time without a zone and the clock was often wrong: do not use them for sorting.
- Android 16/17: foreground service limits, local-network permission, notification permission.

## 5. Open questions v2 (asked in chat; defaults in brackets)
Navigation, list vs grid, selection, delete UX, app-level settings, theme, keep-screen-on, heat policy, power-off policy, collisions, queue persistence, downloads during recording. Answers are written here when given.
