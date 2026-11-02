# Test plan 0.4.0
Report: a screenshot of the folder list and of one file list, and your answers to the questions marked WRITE DOWN.
1. Connect. On the status screen press "Files on the camera". Folders appear in under 2 s. EOSMISC is not in the list, nor any name that starts with a dot. WRITE DOWN the folder names and the order.
2. Open each visible folder. A folder with 100+ files appears in under 3 s. Newest YDXJ number is first. Dates are shown as text. A .THM is not a row of its own (it belongs to its video). WRITE DOWN a few names and sizes and compare them with the PC program.
3. Refresh while in a folder: the list is read again. Back returns to the folders. Leave the files screen and open it again: a new read (the cache of the last visit is gone).
4. Start a recording on the camera, then open Files: a note says the camera is recording; the list still appears or a clear error is shown. WRITE DOWN which.
5. Battery on the status screen still updates after you have listed a large folder (the heartbeat was not stuck).
6. Probe (developer tool): take one sacrificial photo. Type its absolute path (example `/tmp/fuse_d/DCIM/101MEDIA/YDXJ0001.jpg`), press Delete?, confirm Send 1281. WRITE DOWN the rval and the raw reply from the log, and whether the file is gone from a new listing. Do not use a video you want to keep.
