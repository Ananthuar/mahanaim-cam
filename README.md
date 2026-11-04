# MAHANAIM CAM

**Free · Open-source · Watermark-free smartphone webcam for churches and creators**

Turn your Android phone into a wireless webcam for your desktop. Stream locally using WebRTC, with no cloud dependency, subscription, or watermark.

> **Development status:** Active development. Android-to-desktop live video streaming is working and has been tested over Wi-Fi and mobile hotspot. Live Camera Studio and basic camera controls are implemented. OBS integration and the Windows release are still in progress.

---

## What is MAHANAIM CAM?

MAHANAIM CAM is an open-source smartphone webcam application designed primarily for churches, worship teams, livestreaming setups, and other organizations.

It allows an Android smartphone to act as a camera for a desktop application. The desktop application receives the live video stream over a local network.

The long-term goal is to make the phone camera available in OBS Studio and, through OBS Virtual Camera, applications such as Google Meet, Zoom, and Microsoft Teams.

### Core goals

- **Free and open source** — no subscription or account required.
- **Watermark-free** — clean video output.
- **Local streaming** — no cloud-based video transmission.
- **Wireless connectivity** — connect devices over a local network or mobile hotspot.
- **WebRTC transport** — real-time video communication.
- **Cross-platform desktop architecture** — designed to support Windows and Linux.
- **Church-focused** — built with practical livestreaming needs in mind.

---

## Current Features

| Feature | Status |
|---|---|
| Android application | Implemented |
| Desktop Electron application | Implemented |
| QR-code device pairing | Working |
| WebSocket signaling | Working |
| WebRTC offer/answer negotiation | Working |
| Android camera streaming | Working |
| Live desktop video preview | Working |
| Wi-Fi connectivity | Tested successfully |
| Mobile hotspot connectivity | Tested successfully |
| Automatic Live Camera Studio screen | Implemented |
| Front/rear camera switching | Implemented |
| Automatic portrait/landscape orientation | Implemented |
| 480p / 720p / 1080p quality selection | Implemented |
| Zoom, focus and exposure controls | Planned |
| Flash/torch control | Planned |
| Advanced video quality optimization | In progress |
| OBS live video integration | Planned |
| USB tethering support | Planned |
| Multiple camera support | Planned |

---

## Live Camera Studio

When the Android phone successfully connects to the desktop, MAHANAIM CAM automatically opens a dedicated Live Camera Studio screen.

The screen provides:

- Live camera preview.
- Current camera information.
- Front and rear camera switching.
- Stream quality selection.
- Automatic portrait and landscape orientation.
- Stream status and session information.
- Disconnect control.

Additional hardware controls, including zoom, torch, focus and exposure, are planned for future development.

---

## Architecture Overview

The application uses separate Android and desktop components.

```text
             ANDROID PHONE
          ┌──────────────────┐
          │   Camera /       │
          │   WebRTC Camera  │
          └────────┬─────────┘
                   │
                   │ WebRTC Video
                   │
                   ▼
          ┌──────────────────┐
          │  Desktop App     │
          │  Electron +      │
          │  React           │
          └──────────────────┘
                   ▲
                   │
                   │ WebSocket Signaling
                   │
          ┌────────┴─────────┐
          │ Desktop          │
          │ Signaling Server │
          └──────────────────┘
```

The desktop application handles device pairing and signaling. WebRTC establishes the video connection between the Android phone and desktop.

The architecture is designed to support future OBS integration.

See [ARCHITECTURE.md](./ARCHITECTURE.md) for additional technical details.

---

## Technology Stack

### Android Application

- Kotlin
- Jetpack Compose
- CameraX / Android camera APIs
- WebRTC
- OkHttp WebSocket
- ML Kit Barcode Scanning

### Desktop Application

- Electron
- React
- TypeScript
- Vite
- Node.js
- WebSocket

### Shared Protocol

- TypeScript
- Shared signaling message definitions

---

## Prerequisites

### Desktop Development

- Node.js 20 or newer
- npm 10 or newer
- Git

The desktop application is currently being developed and tested on Arch Linux. The architecture is intended to support Windows 10/11.

### Android Development

- Android Studio
- Android SDK 34
- JDK 17
- Physical Android device
- Android API 26 or newer

---

## Getting Started

### 1. Clone the repository

```bash
git clone https://github.com/mahanaim/mahanaim-cam.git
cd mahanaim-cam
```

### 2. Install dependencies

```bash
npm install
```

### 3. Start the desktop application

From the project root:

```bash
npm run dev
```

This launches the Electron desktop application.

The current development configuration uses:

| Service | Address |
|---|---|
| Signaling WebSocket | `ws://localhost:8765` |
| OBS source server | `http://localhost:8766` |
| Vite development server | `http://localhost:5173` |

The signaling server also advertises the desktop's local network address for Android pairing.

### 4. Build the desktop application

```bash
npm run build
```

### 5. Build the Android application

```bash
cd apps/android-mobile
./gradlew assembleDebug
```

The debug APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install it using:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 6. Connect Android to Desktop

1. Start MAHANAIM CAM on the desktop.
2. Open MAHANAIM CAM on your Android phone.
3. Scan the QR code displayed by the desktop.
4. Connect to the desktop.
5. Wait for device pairing.
6. The Live Camera Studio screen opens automatically.
7. The desktop receives the Android camera feed.

**Network note:** Both devices must be reachable on the same local network. A Wi-Fi network or a mobile hotspot can be used. Internet access is not required for local video transmission.

---

## Project Structure

```text
mahanaim-cam/
│
├── apps/
│   ├── windows-studio/
│   │   └── Electron + React + TypeScript desktop app
│   │
│   └── android-mobile/
│       └── Kotlin + Jetpack Compose Android app
│
├── packages/
│   └── protocol/
│       └── Shared signaling message definitions
│
├── docs/
│   └── Additional documentation
│
├── ARCHITECTURE.md
├── AGENTS.md
├── README.md
└── package.json
```

---

## Development Milestones

MAHANAIM CAM is being developed incrementally. Each completed milestone will be documented and committed to GitHub.

- [x] **M1 — Project Foundation**
  - Repository and project structure
  - Android and desktop application scaffolding
  - Shared signaling protocol

- [x] **M2 — Device Pairing and Signaling**
  - Desktop WebSocket signaling server
  - Android WebSocket client
  - QR-code pairing
  - Device connection management

- [x] **M3 — Initial WebRTC Video Streaming**
  - WebRTC offer/answer negotiation
  - Android camera capture
  - Live video transmission
  - Desktop video preview

- [ ] **M4 — Video Quality and Performance**
  - Initial video quality controls
  - Improve frame rate and latency
  - Optimize bitrate and resolution
  - Improve video stability

- [ ] **M5 — OBS Integration**
  - Live OBS Browser Source
  - OBS Virtual Camera compatibility
  - Streaming workflow testing

- [ ] **M6 — Camera Controls**
  - [x] Front and rear camera switching
  - [x] Automatic portrait/landscape orientation
  - [x] Initial resolution selection
  - [ ] Zoom and focus controls
  - [ ] Exposure and torch controls
  - [ ] Complete camera settings panel

- [ ] **M7 — USB Connectivity**
  - USB tethering support
  - Automatic desktop discovery
  - Connection reliability improvements

- [ ] **M8 — Multiple Cameras**
  - Multiple Android device connections
  - Camera selection and management

- [ ] **M9 — Audio Support**
  - Optional audio transmission
  - Audio/video synchronization

- [ ] **M10 — Windows Release**
  - Windows packaging
  - Installer creation
  - Windows 10/11 testing

---

## Contributing

Contributions, suggestions, and improvements are welcome.

Please read [AGENTS.md](./AGENTS.md) for project conventions and architecture constraints before making changes.

---

## License

MIT License. See [LICENSE](./LICENSE).

---

**MAHANAIM CAM** is developed for the Mahanaim Gospel Team and the wider church community.

*Your Phone. Your Camera. Your Church.*