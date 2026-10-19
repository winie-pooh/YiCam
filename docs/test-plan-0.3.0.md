# Test plan 0.3.0 (camera settings)

Share the log after the test.

1. Connect. On the status screen press "Camera settings". Check: the list fills in within a few seconds, in groups (Video, Photo, Sound and LED, Power/Wi-Fi/system); names are readable; on/off settings are switches.
2. Change "Beep volume" and one switch. Check: they are marked with a dot, "Apply (2)" is shown. Press Discard: marks disappear.
3. Change the beep volume again and press Apply. Check: "Settings applied"; the camera really changed (the beep). Go back and open the screen again: the new value is there.
4. Change "Video resolution" and Apply. Check on the camera screen or by filming.
5. Change "Video standard" (NTSC/PAL) together with the resolution, Apply. Check: no error, the resolution list changed. Change the standard back afterwards.
6. Press "Set camera clock to phone time". Check: the note says set; ask the camera for the time (status screen after Refresh does not show it; use Probe > Settings and look at camera_clock).
7. Start a recording, open the settings. Check: the screen says settings cannot be changed, Apply is disabled.
8. Report: any setting that the camera refused (the note names it), and any setting name that looks wrong.
