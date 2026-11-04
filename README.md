# MAHANAIM CAM

**Free · Watermark-free · Church-focused smartphone webcam for Windows + OBS**

> Turn any Android phone into a wireless or USB camera for OBS Studio — no subscription, no watermark, no cloud.

---

## What is MAHANAIM CAM?

MAHANAIM CAM is an open-source application that enables a church (or any organization) to use an Android smartphone as a high-quality webcam for a Windows PC running OBS Studio. Through OBS Virtual Camera, the stream can be used in Google Meet, Zoom, Teams, and any other webcam-compatible application.

### Key Features

- **Free and open source** — no subscription, no account required
- **No watermark** — full clean video output
- **Wi-Fi or USB** — works over local Wi-Fi or Android USB tethering
- **WebRTC transport** — low-latency, high-quality H.264 video
- **720p @ 30 FPS** — ideal for worship recordings
- **OBS-native** — integrates as a Browser Source; works with Virtual Camera
- **No cloud dependency** — all signaling is local

---

## Architecture Overview

```
Android Phone ──[WebRTC]──► Windows OBS Browser Source ──► OBS Virtual Camera ──► Google Meet
       │                           │
       └──[WebSocket/LAN]──► Electron Signaling Server
```

See [ARCHITECTURE.md](./ARCHITECTURE.md) for full technical details.

---

## Prerequisites

### Windows (Development)
- Node.js ≥ 20
- npm ≥ 10
- Git

### Android (Development)
- Android Studio Hedgehog or newer
- Android SDK 34
- JDK 17
- Physical Android device (API 26+) for camera testing

### OBS Studio (Usage)
- OBS Studio ≥ 30.x
- Enable **Virtual Camera** in OBS Tools menu

---

## Quick Start (Development)

### 1. Clone and install

```bash
git clone https://github.com/mahanaim/mahanaim-cam.git
cd mahanaim-cam
npm install
```

### 2. Run Windows Studio

```bash
npm run dev
# or
cd apps/windows-studio
npm run dev
```

This launches the Electron desktop application. The signaling WebSocket server starts on `ws://localhost:8765` and the OBS Browser Source is served at `http://localhost:8766`.

### 3. Set up OBS Browser Source

1. Open OBS Studio
2. Add a **Browser Source** to your scene
3. Set URL to `http://localhost:8766`
4. Set width: `1280`, height: `720`

### 4. Build Android App

```bash
cd apps/android-mobile
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Install the APK on your Android phone and connect to pairing.

---

## Project Structure

```
mahanaim-cam/
├── apps/
│   ├── windows-studio/     # Electron + React + TypeScript desktop app
│   └── android-mobile/     # Kotlin + Jetpack Compose Android app
├── packages/
│   └── protocol/           # Shared WebSocket message type definitions
└── docs/                   # Additional documentation
```

---

## OBS Setup Guide

1. Install OBS Studio from https://obsproject.com/
2. Open OBS → Tools → Virtual Camera → **Start Virtual Camera**
3. Add a Scene and add a Browser Source:
   - URL: `http://localhost:8766`
   - Resolution: 1280 × 720
4. In your video call app (Google Meet, Zoom, etc.):
   - Select **OBS Virtual Camera** as the camera input

---

## Roadmap

- [x] M1: Project foundation
- [ ] M2: Live WebRTC video stream
- [ ] M3: QR pairing + OBS source live video
- [ ] M4: Camera controls (zoom, focus, flip)
- [ ] M5: USB tethering auto-discovery
- [ ] M6: Multiple cameras
- [ ] M7: Audio support

---

## Contributing

See [AGENTS.md](./AGENTS.md) for coding conventions and architecture constraints.

---

## License

MIT License — see LICENSE file.

---

*MAHANAIM CAM is developed for the Mahanaim Gospel Team and the wider church community.*
