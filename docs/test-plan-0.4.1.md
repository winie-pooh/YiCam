# Test plan 0.4.1
Report: a screenshot of a download in progress and your answers to the questions marked WRITE DOWN.
1. Connect. Files on the camera. Open a folder. Press Download on a photo. The live view is stopped if it was running; a note on the files screen says a download stops the live view. The photo is saved in Pictures/YiCam. WRITE DOWN the name and size and compare with the PC program (size must match).
2. Download a short video into Movies/YiCam. Size matches the PC copy. WRITE DOWN the name and size.
3. Start a download of a video larger than 500 MB (or the largest you have). Progress shows bytes, percent, speed and time left. Only one Download is enabled. Cancel: the file is not finished. Resume: it continues (the percent does not jump back to 0). When it finishes, compare size with the PC copy. WRITE DOWN whether resume continued and whether the sizes match.
4. During a download turn the camera Wi-Fi off. The screen says the link was lost and you can resume. Turn Wi-Fi on, Connect, Resume. WRITE DOWN whether it continued.
5. Screen off for 30 s during a download, then back: WRITE DOWN whether the download continued or failed (0.4.1 has no foreground service yet; a fail-and-resume is acceptable). Rotate the phone: no crash, progress still shown.
6. Save to: choose a folder with the system picker, download a photo there. Ask every time: the picker appears for the next download. Back to Phone (Movies/YiCam, Pictures/YiCam).
7. Download a file that is already on the phone (same name and size): the app offers to keep the copy or download again.
