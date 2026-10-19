# Kino

Watch video files stored on your PC on your TV, over your own Wi-Fi.  
No cloud, no account, no uploading — files never leave your network.

| | Component | What it is |
|---|---|---|
| PC | **Kino Server** | Electron tray app (Windows). Pick folders to share; it runs an HTTP server on your LAN. |
| TV | **Kino** | Native Android TV app (Kotlin + Compose for TV + ExoPlayer). Finds the server, browses folders, plays files. |

Files stream **byte-for-byte, as-is** — no transcoding. ExoPlayer decodes the file
directly, so playback quality is perfect and the server uses almost no CPU. A file will only
play if your TV has a hardware decoder for it (see [Limitations](#limitations)).

---

## Quick start

### 1. Run the server on your PC

```bash
cd server
npm install
npm start        # launches the Electron tray app
```

The window shows your LAN address, a QR code, and shared folders.  
**Allow it through Windows Firewall** (Private network) when prompted — this is the most common reason the TV can't find the server.

### 2. Build and install the TV app

```bash
cd tv
./gradlew assembleDebug      # or gradlew.bat on Windows
```

Install via ADB:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Open **Kino** on your TV. It discovers the server automatically over UDP broadcast. If discovery doesn't work (some routers block broadcast), use *Enter address manually* with the IP shown in the server window.

---

## Repo layout

```
├── server/                     Kino Server (Electron + Node)
│   ├── src/main/               Electron main process
│   ├── src/server/             Transport core (usable without Electron)
│   │   ├── httpServer.js       Express app
│   │   ├── stream.js           HTTP Range handler
│   │   ├── library.js          Folder scan, id mapping, subtitle pairing
│   │   ├── subtitles.js        SRT → WebVTT conversion
│   │   ├── discovery.js        UDP broadcast responder
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
| Node.js | 20+ | For Kino Server |
| Android Studio | Any recent | For building the TV app |
| JDK | 21 | Android Studio's bundled JBR recommended |
| Android SDK | Platform 36, build-tools 36.1.0 | Install via SDK Manager |

> **Note:** `tv/gradle.properties` pins `org.gradle.java.home` to a local JDK path. Update it to match your machine.

---

## Headless mode (no GUI)

Run the server without Electron — useful for background/service use:

```bash
cd server
npm run serve -- add "/path/to/videos"   # register a folder
npm run serve -- list                    # show configured folders
npm run serve -- remove "/path/to/videos"
npm run serve                            # start serving
```

Config is stored in `%APPDATA%\kino-server\config.json` (Windows). Delete it to reset to defaults.

---

## HTTP API

| Route | Purpose |
|---|---|
| `GET /api/info` | Server name, version, share count, LAN addresses |
| `GET /api/browse?path=<dirId>` | Children of a directory (omit `path` for roots) |
| `GET /api/files/:id/stream` | Video stream with `Range`, `HEAD`, `ETag` support |
| `GET /api/files/:id/subtitles/:subId` | Sidecar subtitle (`?format=vtt` converts SRT → WebVTT) |
| `POST /api/rescan` | Force a library re-index |

Paths are never exposed — the API uses opaque hashed IDs, and resolved paths are verified against share roots before access.

---

## How discovery works

The TV broadcasts `KINO_DISCOVER` on UDP port 48200. The server replies with its name, host, port, and protocol, and also broadcasts unsolicited every 5 seconds so newly launched apps find it immediately.

Manual IP entry exists as a fallback for networks that block broadcast (mesh systems, guest networks, client isolation).

> **Wire protocol:** The magic strings in `server/src/server/discovery.js` and `tv/.../data/Discovery.kt` must match — changing one side breaks discovery silently.

---

## Subtitles and resume

- **Subtitles:** Sidecar files are paired by filename — `Movie.mkv` picks up `Movie.srt`, `Movie.en.srt`, `Movie.vtt` from the same folder. Language is read from the middle segment.
- **Resume:** Playback positions are stored on the TV (DataStore), keyed by file ID.

---

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| TV finds no server | Firewall not allowed; or PC and TV on different SSIDs/VLANs |
| Works on phone browser, not on TV | Different SSIDs, or router client isolation |
| Playback stutters on large 4K files | Wi-Fi throughput — compare file bitrate against actual link speed |
| Video won't play | TV lacks a decoder for the codec (usually 10-bit HEVC). No transcoding fallback exists. |

**Emulator note:** The Android TV emulator is NAT'd, so UDP discovery won't work — use manual entry with `10.0.2.2:8080`. Its HEVC decoder is 8-bit only.

---

## Limitations

- **No transcoding** — playback depends entirely on the TV's hardware decoders
- **No thumbnails or metadata** — avoids an ffmpeg dependency
- **No authentication** — anyone on your LAN can browse shared folders
- **LAN only** — not intended for internet exposure; do not port-forward it

---

## Building the Windows installer

```bash
cd server
npm run dist
```

Produces `server/dist/Kino Server Setup 1.0.0.exe`. The installer is unsigned, so SmartScreen will warn on first run.

---

## License

MIT
