# Kino

Watch video files stored on your Windows PC on your TV, over your own Wi-Fi. No cloud, no
account, no uploading.

Two halves:

| | Name | What it is |
|---|---|---|
| PC | **Kino Server** | Electron tray app. You pick folders to share; it runs an HTTP server on your LAN. |
| TV | **Kino** | Native Android TV app (Kotlin + Compose for TV + Media3/ExoPlayer). Finds the PC, browses the folders, plays the files. |

Files stream **byte-for-byte, as-is** — there is no transcoding. ExoPlayer decodes the file
directly, which means playback quality is perfect and the server uses almost no CPU, but a
file will only play if your TV has a decoder for it. See
[When a file won't play](#when-a-file-wont-play).

---

## Repo layout

```
tv-server/
├── server/                     Kino Server (Electron + Node)
│   ├── src/main/               Electron main process: window, tray, IPC, preload
│   ├── src/server/             Transport core — usable without Electron
│   │   ├── httpServer.js       Express app
│   │   ├── stream.js           HTTP Range handler (the performance-critical file)
│   │   ├── library.js          Folder scan, opaque id ↔ path map, subtitle pairing
│   │   ├── subtitles.js        SRT → WebVTT conversion
│   │   ├── discovery.js        UDP broadcast responder
│   │   ├── netInfo.js          LAN IPv4 detection
│   │   ├── config.js           Persisted settings
│   │   └── standalone.js       Headless entry point (no GUI)
│   ├── src/renderer/           Tray app UI
│   └── src/web/                Browser-based library page
└── tv/                         Kino (Android TV)
    └── app/src/main/java/com/vpsin/kino/
        ├── data/               HTTP client, UDP discovery, DataStore prefs
        ├── ui/connect/         Server discovery + manual IP entry
        ├── ui/browse/          Folder and file grid
        ├── ui/player/          ExoPlayer + D-pad control overlay
        └── ui/common/          Shared interaction helpers
```

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| Node.js | 20+ (developed on 24.12) | For Kino Server |
| Android Studio | any recent | For Kino |
| JDK | **21** (Android Studio's bundled JBR) | Not the system JDK — see below |
| Android SDK | platform 36, build-tools 36.1.0 | Install via SDK Manager |

**The JDK matters.** This machine's system JDK is 23, which AGP does not support. Gradle is
pinned to Android Studio's bundled JBR 21 in `tv/gradle.properties`:

```properties
org.gradle.java.home=C:\\Program Files\\Android\\Android Studio\\jbr
```

If you move Android Studio or build on another machine, update that line.

Commands below assume Git Bash. `adb` lives at
`%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`; adding it to `PATH` makes life easier.

---

## Running Kino Server

```bash
cd server
npm install
npm start          # launches the Electron tray app
```

The window shows your LAN address (e.g. `http://192.168.1.38:8080`), a QR code, and the
list of shared folders. Closing the window hides it to the tray — use the tray's **Quit**
to actually stop the server.

**Allow it through Windows Firewall on Private networks** the first time you're asked. This
is the single most common reason the TV can't see the PC. To check afterwards:

```bash
netsh advfirewall firewall show rule name=all verbose | grep -i -A6 electron | grep -i action
```

Also confirm your PC and TV are on the **same SSID** — the 2.4 GHz and 5 GHz bands of the
same router are sometimes isolated from each other.

### Headless mode (no GUI)

Useful for testing the server on its own, or running it as a background service. Shared
folders live in config, not on the command line, so **the server can be started from any
directory regardless of where the media lives**:

```bash
cd server
npm run serve -- add "C:\Users\me\Videos"      # register a folder
npm run serve -- list                          # show configured folders
npm run serve -- remove "C:\Users\me\Videos"   # unregister
npm run serve                                  # serve what's configured
```

The Electron app and headless mode share one config file:

```
%APPDATA%\kino-server\config.json
```

It holds `port`, `name`, `folders`, and `startOnLogin`. Delete it to reset to defaults.

### Verifying the server by itself

```bash
curl http://localhost:8080/api/info
curl "http://localhost:8080/api/browse"

# Byte-range support — the make-or-break behaviour for seeking:
curl -sD - -o /dev/null -r 0-1023 http://localhost:8080/api/files/<id>/stream
#   expect 206, Content-Range: bytes 0-1023/<size>, Content-Length: 1024
curl -sD - -o /dev/null -r 999999999999- http://localhost:8080/api/files/<id>/stream
#   expect 416
```

From your phone's browser on the same Wi-Fi, `http://<pc-ip>:8080` should load the library
page. If that works, the firewall is configured correctly and the TV will work too.

---

## Building and installing Kino (TV app)

```bash
cd tv
./gradlew.bat assembleDebug
```

Output: `tv/app/build/outputs/apk/debug/app-debug.apk`

### Install to the Android TV emulator

```bash
adb devices                                  # find the emulator serial
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
```

Two emulator limitations to know about, so you don't chase phantom bugs:

- **Discovery cannot work.** The emulator is NAT'd and never sees a LAN UDP broadcast. Use
  *Enter address manually* → `10.0.2.2`, port `8080` (`10.0.2.2` is how the emulator reaches
  the host, not your LAN IP).
- **Its HEVC decoder is 8-bit only.** Any 10-bit / Main 10 file (most x265 releases) fails
  with a decode error on the emulator but plays fine on real hardware.

### Install to a real TV over Wi-Fi

On the TV:

1. Settings → System → About → click **Android TV OS build** seven times.
2. Settings → System → **Developer options** → enable **Wireless debugging**.
3. Settings → Network & Internet → your Wi-Fi → note the **IP address**.

Then, for a **newer TV with Wireless debugging** (Android 11+), pair once:

```bash
# On the TV, open "Pair device with pairing code" and leave it on screen.
adb mdns services | grep pairing              # finds IP:port of the pairing service
adb pair <tv-ip>:<pairing-port> <6-digit-code>

# Then connect to the (different) connect port:
adb mdns services | grep connect
adb connect <tv-ip>:<connect-port>
adb -s <tv-ip>:<connect-port> install -r app/build/outputs/apk/debug/app-debug.apk
```

The pairing service only advertises over mDNS while that dialog is open, and the code is
short-lived — reopen it for a fresh one if pairing fails.

For an **older Android TV** with a plain *USB/ADB debugging* toggle, port 5555 is used
directly and no pairing is needed:

```bash
adb connect <tv-ip>:5555
```

Accept the *Allow debugging from this computer?* prompt on the TV (tick **Always allow**).

**If the connection goes `offline`** — which happens when the TV sleeps — reset it:

```bash
adb disconnect <tv-ip>:<port> && adb connect <tv-ip>:<port>
```

### Reading logs

```bash
adb -s <serial> logcat -c                                    # clear first
adb -s <serial> logcat | grep -i "ExoPlayer\|kino\|AndroidRuntime"
adb -s <serial> logcat -b crash                              # crashes only
```

### Checking what your TV can decode

Worth doing before assuming a playback failure is a bug:

```bash
adb -s <serial> shell dumpsys media.player | grep -A20 'video/hevc'
```

Look for the profile list. `2/... (Main10/...)` means 10-bit HEVC is supported;
if only `1/... (Main/...)` appears, the TV is 8-bit only.

---

## Publishing Kino to Google Play

### 1. Create a release keystore — once, and keep it safe forever

```bash
"C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" \
  -genkeypair -v -keystore kino-release.jks \
  -alias kino -keyalg RSA -keysize 2048 -validity 10000
```

Store `kino-release.jks` **outside the repo** and back it up. If you lose it you cannot
publish updates to the same listing — unless you've enrolled in Play App Signing, which
lets Google re-key you. Enrol; it's the default for new apps.

### 2. Add release signing to Gradle

`tv/app/build.gradle.kts` currently signs release builds with the **debug** keystore so
`assembleRelease` produces something installable during development. Play will reject that.
Replace it:

```kotlin
android {
    signingConfigs {
        create("release") {
            storeFile = file(providers.gradleProperty("KINO_STORE_FILE").get())
            storePassword = providers.gradleProperty("KINO_STORE_PASSWORD").get()
            keyAlias = providers.gradleProperty("KINO_KEY_ALIAS").get()
            keyPassword = providers.gradleProperty("KINO_KEY_PASSWORD").get()
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true          // see the ProGuard note below
        }
    }
}
```

Put the four values in `%USERPROFILE%\.gradle\gradle.properties` — **never** in the repo:

```properties
KINO_STORE_FILE=C:/keys/kino-release.jks
KINO_STORE_PASSWORD=…
KINO_KEY_ALIAS=kino
KINO_KEY_PASSWORD=…
```

> **If you enable `isMinifyEnabled`**, test the release build on a device before uploading.
> `app/proguard-rules.pro` keeps the `com.vpsin.kino.data.**` model classes; if those rules
> ever fall out of sync with the package name, kotlinx.serialization fails at runtime in
> release only, and every server response silently fails to parse.

### 3. Bump the version, then build a bundle

Play requires an **AAB**, not an APK. `versionCode` must increase on every upload.

```kotlin
versionCode = 2
versionName = "1.1"
```

```bash
cd tv
./gradlew.bat bundleRelease
```

Output: `tv/app/build/outputs/bundle/release/app-release.aab`

Sanity-check the exact artifact you're about to upload by installing it locally:

```bash
./gradlew.bat assembleRelease
adb -s <serial> install -r app/build/outputs/apk/release/app-release.apk
```

### 4. Play Console setup for a TV app

The app ID is **`com.vpsin.kino`** and is **permanent** — Google never allows it to change
for an existing listing.

TV-specific requirements the app already satisfies:

- `android.software.leanback` declared as required
- `android.hardware.touchscreen` declared as **not** required — without this the app is
  filtered out of TV devices entirely
- `LEANBACK_LAUNCHER` intent category, or the app installs but never appears on the home screen
- A TV banner drawable

What you still need to supply in the Console:

- Opt in to the **Android TV** form factor and submit for the TV review
- **App title, max 30 characters.** This is your biggest search signal, so don't use the
  bare brand: e.g. `Kino: Stream from Your PC`
- **TV banner** 1280×720 and at least one **16:9 landscape screenshot**
- Privacy policy URL, content rating questionnaire, and the Data Safety form. Kino sends
  nothing off the local network, which makes this form short — declare no data collection.

Do not put *Android TV*, *Google TV*, or *Chromecast* in the app name; Google's brand
guidelines prohibit it and it's a common first-submission rejection.

---

## Building the Windows installer

```bash
cd server
npm run dist
```

Output: `server/dist/Kino Server Setup 1.0.0.exe` (~82 MB — it bundles Electron). It's a
non-one-click installer, so the user can choose the install directory.

### If `npm run dist` fails with symlink errors

```
ERROR: Cannot create symbolic link : A required privilege is not held by the client.
  ...winCodeSign\...\darwin\10.12\lib\libcrypto.dylib
```

This is an electron-builder bug, not a problem with this project. It downloads a
`winCodeSign` bundle containing **macOS** symlinks, and creating symlinks on Windows needs
elevated privileges. Those files are only used for signing macOS builds and are irrelevant
to the NSIS installer.

Fix it by pre-populating the cache with the `darwin` folder excluded — no admin needed:

```bash
CACHE="$LOCALAPPDATA/electron-builder/Cache/winCodeSign"
Z="node_modules/7zip-bin/win/x64/7za.exe"

# Any of the leftover .7z files in that folder will do; they're all the same archive.
"$Z" x "$CACHE/<any-number>.7z" -o"$CACHE/winCodeSign-2.6.0" -xr'!darwin' -y

# Remove the half-extracted numbered temp dirs, or the build reuses them and fails again:
cd "$CACHE" && rm -rf [0-9]*
```

electron-builder then finds `winCodeSign-2.6.0` already present and skips the download and
extraction entirely. Enabling Windows Developer Mode, or running the build as administrator,
also works — both permit symlink creation.

Identity comes from `server/package.json`:

```json
"build": {
  "appId": "com.vpsin.kinoserver",
  "productName": "Kino Server",
  "win": { "target": "nsis", "icon": "assets/icon.png" }
}
```

`assets/icon.png` must stay **at least 256×256** — electron-builder converts it to an `.ico`
and errors out below that size. If the log says `default Electron icon is used`, the `icon`
key isn't being picked up.

The installer is **unsigned**, so Windows SmartScreen will warn users on first run. To
avoid that you need an EV code-signing certificate, then add `win.certificateFile` and
`certificatePassword` (via env vars, not the repo) to the build config.

---

## How it works

### HTTP API

| Route | Purpose |
|---|---|
| `GET /api/info` | Server name, version, share count, LAN addresses. Used to validate a manually entered IP. |
| `GET /api/browse?path=<dirId>` | Children of a directory. Omit `path` for the share roots. |
| `GET /api/files/:id/stream` | The video. Supports `Range`, `HEAD`, `ETag`. |
| `GET /api/files/:id/subtitles/:subId` | Sidecar subtitle. Served raw by default; add `?format=vtt` to convert SRT → WebVTT on the fly. |
| `POST /api/rescan` | Force a re-index. |

**Paths are never exposed.** The API uses opaque hashed ids, and `library.js` re-verifies
that a resolved path still sits under a configured share root before opening it. That's the
path-traversal boundary — keep it intact if you extend the API.

`stream.js` is the file to be careful with. It uses a 4 MB `highWaterMark` instead of Node's
64 KB default, and destroys the read stream on `res` close — ExoPlayer aborts the connection
on *every* seek, so without that, file descriptors leak steadily during normal use. Never
apply compression middleware to this route.

### Discovery

The TV broadcasts `KINO_DISCOVER` to `255.255.255.255:48200`. The server replies with
`{magic: "KINO", name, host, port, protocol}` and also broadcasts unsolicited every 5s, so a
TV that just launched finds the PC without asking.

**These magic strings are a wire protocol — changing one side breaks discovery silently.**
Both live in `server/src/server/discovery.js` and
`tv/app/src/main/java/com/vpsin/kino/data/Discovery.kt`. Manual IP entry exists because some
routers, mesh systems, and guest networks block broadcast entirely.

### Subtitles and resume

Sidecar subtitles are paired by filename: `Movie.mkv` picks up `Movie.srt`, `Movie.en.srt`,
`Movie.ass`, `Movie.vtt` from the same folder, with the language read from the middle segment.
Resume positions are stored on the **TV** in DataStore, keyed by file id — not on the server.

---

## Troubleshooting

| Symptom | Cause |
|---|---|
| TV finds no server | Firewall not allowed on the current network profile; or PC and TV on different SSIDs. Try manual IP to tell the two apart — if manual works, broadcast is being blocked. |
| Works on phone browser, not on TV | Almost always different SSIDs, or router client isolation (common on guest networks and 4G routers). |
| `Cannot read properties of undefined (reading 'requestSingleInstanceLock')` | `ELECTRON_RUN_AS_NODE=1` is set in your shell, so Electron runs as plain Node. Launch with `env -u ELECTRON_RUN_AS_NODE npm start`. VS Code's integrated terminal and extension host set this. |
| `adb: device offline` | The TV slept. `adb disconnect` then `adb connect` again. |
| `./gradlew.bat: No such file or directory` | You're not in `tv/`. |
| Playback stutters on large 4K files | Wi-Fi throughput, not the server. Compare the file's bitrate against actual link speed; the server itself streams far faster than any Wi-Fi link. |

### When a file won't play

There is no transcoding, so an unsupported codec cannot be worked around in software — the
app reports it in plain language rather than showing a black screen.

The usual culprit is **10-bit HEVC** (`x265`, `Main 10`, `hvc1.2`/`hev1.2`). Most real TVs
handle it; the Android TV emulator does not. Use the `dumpsys media.player` command above to
check the profiles your device actually supports before treating it as a bug.

---

## Current limitations

- No transcoding — playback depends entirely on the TV's decoders
- No thumbnails or media metadata (deliberate: avoids an ffmpeg dependency)
- No authentication — anyone on your Wi-Fi can browse the shared folders
- Not intended for internet exposure; do not port-forward it
