# MAHANAIM CAM — Architecture

> Version: 0.1.0 (First Milestone — Foundation)  
> Last updated: 2026-11-03

---

## 1. System Overview

MAHANAIM CAM enables an Android smartphone to function as a wireless or USB-tethered camera for a Windows PC, feeding video into OBS Studio and, via OBS Virtual Camera, into Google Meet and other conferencing tools. All signaling and transport occur on the **local network** — no cloud services are required.

```
┌─────────────────────────────────────────────────────────────────────┐
│                        LOCAL NETWORK / USB                          │
│                                                                     │
│   ┌───────────────────┐         ┌────────────────────────────────┐  │
│   │  Android Phone    │         │  Windows PC                    │  │
│   │                   │ WebRTC  │                                │  │
│   │  Camera2Capturer ─┼────────►│  OBS Browser Source (port 8766)│  │
│   │  WebRTC SDK       │  video  │  ← WebRTC peer                 │  │
│   │  WebSocket client │         │                                │  │
│   │         │         │         │  Electron App (windows-studio) │  │
│   │         │         │ WS/8765 │  ├─ WebSocket Signaling Server │  │
│   │         └─────────┼────────►│  ├─ OBS Source HTTP Server     │  │
│   │                   │         │  └─ React UI (status/control)  │  │
│   └───────────────────┘         └─────────┬──────────────────────┘  │
│                                           │                         │
└───────────────────────────────────────────┼─────────────────────────┘
                                            │ OBS Studio
                                            │  ├─ Browser Source (localhost:8766)
                                            │  └─ Virtual Camera ON
                                            │
                                            ▼
                                     Google Meet / Zoom / Teams
```

---

## 2. Components

### 2.1 Windows Studio (`apps/windows-studio`)

| Sub-component | Technology | Responsibility |
|---|---|---|
| Electron Main Process | Electron 30, Node.js | Native OS integration, server lifecycle |
| React Renderer | React 18, Vite 5, TypeScript 5 | Desktop UI |
| WebSocket Signaling Server | `ws` 8.x | Local SDP/ICE signaling on port 8765 |
| OBS Browser Source Server | Node.js `http` | Serves `obs-source.html` on port 8766 |
| WebRTC Peer (future) | `wrtc` or Electron WebRTC | Receives video from Android |

**Ports used:**

| Port | Protocol | Purpose |
|---|---|---|
| 8765 | WebSocket (ws://) | Signaling server |
| 8766 | HTTP | OBS Browser Source page |
| 8767 (future) | HTTPS | Optional secure signaling |

### 2.2 Android Mobile (`apps/android-mobile`)

| Sub-component | Technology | Responsibility |
|---|---|---|
| Camera Capture | Camera2 API + WebRTC `Camera2Capturer` | Captures 720p/30fps H.264 |
| WebRTC Peer | `org.webrtc:google-webrtc` 1.0.32006 | Sends encoded video |
| WebSocket Client | OkHttp WebSocket | Signaling client |
| UI | Jetpack Compose | Camera preview, controls, status |
| QR Pairing (future) | ZXing / ML Kit | Scan PC's IP:port from QR code |

### 2.3 Shared Protocol (`packages/protocol`)

TypeScript definitions for all WebSocket message types shared between the Windows signaling server and Android client (compiled to JSON for cross-platform use).

---

## 3. Data Flow

### 3.1 Pairing (First Connection)

```
Android ──────────────────────────────── Windows
   │  WS connect to ws://<PC_IP>:8765   │
   │ ─────────────────────────────────► │
   │                                    │
   │  PAIR_REQUEST { deviceName, uuid } │
   │ ─────────────────────────────────► │
   │                                    │  user confirms on desktop
   │  PAIR_ACCEPT { sessionId }         │
   │ ◄───────────────────────────────── │
```

### 3.2 WebRTC Session Establishment

```
Android ──────────── Windows
   │  SESSION_START  │
   │ ───────────────►│
   │                 │  createOffer()
   │  SDP_OFFER      │
   │ ◄───────────────│
   │  setRemoteDesc  │
   │  createAnswer   │
   │  SDP_ANSWER     │
   │ ───────────────►│
   │    ICE_CANDIDATE (trickle, both directions)
   │ ◄──────────────►│
   │                 │
   │  [WebRTC video stream established]
   │ ═══════════════►│ (video → OBS Browser Source)
```

### 3.3 OBS Integration

1. User adds a Browser Source in OBS pointing to `http://localhost:8766`
2. The served page connects to the local WebRTC peer and renders the video stream
3. OBS renders the browser source frame-by-frame
4. User enables OBS Virtual Camera
5. Google Meet / Zoom / Teams see the virtual camera as a standard webcam

---

## 4. WebSocket Protocol

Protocol messages are JSON objects. All messages have a `type` discriminator field.

See `packages/protocol/src/messages.ts` for the full TypeScript type definitions.

### Message Types

| Type | Direction | Description |
|---|---|---|
| `PAIR_REQUEST` | Android → Windows | Request to pair device |
| `PAIR_ACCEPT` | Windows → Android | Pairing accepted, session assigned |
| `PAIR_REJECT` | Windows → Android | Pairing rejected |
| `SESSION_START` | Android → Windows | Begin streaming session |
| `SESSION_END` | Either | Tear down session |
| `SDP_OFFER` | Windows → Android | WebRTC offer SDP |
| `SDP_ANSWER` | Android → Windows | WebRTC answer SDP |
| `ICE_CANDIDATE` | Either | ICE candidate (trickle) |
| `HEARTBEAT` | Either | Keep-alive ping |
| `HEARTBEAT_ACK` | Either | Keep-alive pong |
| `ERROR` | Either | Error notification |

---

## 5. Video Pipeline

```
Camera2 (Android) → VideoSource → VideoTrack → RtpSender
  → [H.264 encoded, 720p, 30fps]
  → SRTP over WebRTC DataChannel / MediaChannel
  → Windows Electron / OBS Browser Source WebRTC peer
  → <video> element in obs-source.html
  → OBS Browser Source capture
  → OBS Scene
  → OBS Virtual Camera (DirectShow/VfW)
  → Google Meet / Zoom
```

**Initial video targets:**
- Resolution: 1280 × 720 (720p)
- Frame rate: 30 FPS
- Codec: H.264 (hardware-accelerated on Android via `Camera2Capturer`)
- Bitrate: adaptive, target 2–4 Mbps on Wi-Fi

---

## 6. Connection Methods

### Wi-Fi (Primary)
- Android and Windows PC on the same LAN
- Android connects to `ws://<PC_LAN_IP>:8765`
- IP discovered manually (typed) or via QR code scan (future)

### USB Tethering
- Android USB tethering creates a virtual network adapter on Windows
- Android gets an IP in `192.168.42.x` or `192.168.0.x` range
- Windows PC becomes reachable from Android via tethering gateway IP
- No ADB, no custom driver required — standard OS feature

---

## 7. Security Considerations

- All communication is **LAN-only** by design
- No authentication tokens are transmitted over the internet
- Signaling server accepts connections only from the local subnet (future: bind to specific interface)
- No TLS required for Milestone 1 (LAN); TLS will be added in Milestone 3
- Session IDs are UUID v4; not a security boundary in Milestone 1
- Pairing confirmation is required from the Windows desktop before any stream begins

---

## 8. Dependency Versions

| Component | Package | Version | Notes |
|---|---|---|---|
| Runtime | Electron | 30.x | Stable LTS channel |
| Bundler | Vite | 5.x | ESM native |
| UI Framework | React | 18.x | |
| Language | TypeScript | 5.x | |
| WebSocket Server | ws | 8.x | Battle-tested, no deps |
| Test Runner | Vitest | 1.x | Vite-native |
| Android Language | Kotlin | 1.9.x | |
| Android Build | AGP | 8.3.x | |
| Android Compose | Compose BOM | 2024.05.00 | |
| Android WebRTC | org.webrtc:google-webrtc | 1.0.32006 | Google's maintained build |
| Android Min SDK | — | API 26 (Android 8.0) | Camera2 full support |
| Android Target SDK | — | API 34 (Android 14) | |

---

## 9. Future Milestones

| Milestone | Description |
|---|---|
| M1 (current) | Project foundation, builds verified |
| M2 | Live WebRTC video stream (Android → Windows) |
| M3 | QR pairing, OBS source page with live video |
| M4 | Camera controls (zoom, focus, flip) |
| M5 | USB tethering UX improvements, auto-discovery |
| M6 | Multiple camera support |
| M7 | Audio support (optional) |
