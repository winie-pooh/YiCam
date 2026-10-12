# FILES.md — project file map (YiCam)

Read this first. One row per project file: what it is responsible for.

## Rules (mandatory for every change)

1. Every source file (`.kt`, `.xml`, scripts) starts with a short header comment (1-3 lines):
   what the file is responsible for and what must NOT be put in it.
2. When a file is added, renamed, deleted, or its responsibility changes, update in the same change:
   (a) the header comment in the file, (b) the row below, (c) `CHANGELOG.md`.
3. One file = one responsibility. If the description needs more than two sentences, split the file.
4. The description below and the header in the file must say the same thing.

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
Files marked "no header yet" get their header comment the next time they are changed.

| File | Responsible for | Last changed | Header in file |
|---|---|---|---|
| `app/src/main/java/com/example/yicam/MainActivity.kt` | v0.0.1 probe screen: buttons, raw command field, log view, local-network permission request. No network logic. | 0.0.1 | no header yet |
| `app/src/main/java/com/example/yicam/WifiBinder.kt` | Finds the camera Wi-Fi (192.168.42.x) and binds the app process to it. | 0.0.1 | no header yet |
| `app/src/main/java/com/example/yicam/CameraConnection.kt` | TCP connection to camera port 7878: reader thread, serialized writer, %TOKEN% substitution, "/" escaping, secret masking in logs. | 0.0.1 | no header yet |
| `app/src/main/java/com/example/yicam/JsonStreamParser.kt` | Splits a continuous text stream into complete JSON objects. Knows nothing about camera commands. | 0.0.1 | no header yet |
| `app/src/main/res/xml/network_security_config.xml` | Allows cleartext HTTP/RTSP only for 192.168.42.1. | 0.0.1 | no header yet |
| `app/src/main/AndroidManifest.xml` | Permissions (INTERNET, ACCESS_NETWORK_STATE, CHANGE_NETWORK_STATE, ACCESS_LOCAL_NETWORK) and network security config reference. | 0.0.1 | n/a (generated) |
| `docs/FILES.md` | This index. | 0.0.1 | n/a |
| `CHANGELOG.md` | Change log per version. | 0.0.1 | n/a |
