# Test plan 0.4.1.1
Report: the shared log (probe screen -> Share log) and your answers to the questions marked WRITE DOWN.
1. Fresh start: fully close the app, switch the camera off and on. Connect, open "Files on the camera" WITHOUT opening the live view. Download a photo. It must succeed. In the log find the lines "[DL] ... waking the camera web server (msg_id 259 none_force)", "web server wake-up accepted" and "[HTTP] answer 200" (or 206). WRITE DOWN whether the download worked and what the wake-up answered.
2. If the download in step 1 failed: WRITE DOWN the [DL]/[HTTP] lines from the log (the answer code or the connection error) — this is exactly the information that was missing before 0.4.1.1.
3. Download a short video. The log shows "[DL] ... done, N bytes in M ms". WRITE DOWN N and M (this gives the real HTTP speed).
4. Open the live view, then the files screen, and download another photo. The live view stops, the download works. WRITE DOWN whether anything differed from step 1.
5. Start a video recording on the camera (from the live screen), then try a download while it records. WRITE DOWN what the wake-up answered (rval -21 expected) and whether the download worked or which [DL]/[HTTP] line explains the failure.
6. Listings: open a folder with many files. The log shows "[FILES] <folder>: N files". Turn the camera Wi-Fi off and press Refresh: the log shows a [FILES] [ERR] line and the screen shows the error. WRITE DOWN both.
