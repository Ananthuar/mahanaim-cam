# MAHANAIM CAM — WebSocket Signaling Protocol Specification

Version: 0.1.0

## Overview

All signaling between the Android mobile app and Windows Studio is performed via a WebSocket connection to `ws://<PC_IP>:8765`. Messages are JSON objects with a `type` discriminator field.

## Connection

1. Android opens WebSocket to `ws://<PC_IP>:8765`
2. Android sends `PAIR_REQUEST`
3. Windows user confirms → Windows sends `PAIR_ACCEPT`
4. Android sends `SESSION_START` when ready to stream
5. Windows sends `SDP_OFFER` → Android replies `SDP_ANSWER`
6. Both exchange `ICE_CANDIDATE` messages (trickle ICE)
7. WebRTC connection established; video flows peer-to-peer

## Pairing Flow

```
Android                            Windows
  |                                   |
  |-- PAIR_REQUEST -----------------> |  (deviceName, uuid)
  |                                   |  [user confirms on desktop]
  |<-- PAIR_ACCEPT ------------------|  (sessionId)
  |  or PAIR_REJECT                   |
```

## Session Flow

```
Android                            Windows
  |                                   |
  |-- SESSION_START ----------------> |
  |<-- SDP_OFFER ---------------------|
  |-- SDP_ANSWER ------------------> |
  |<-- ICE_CANDIDATE(s) -------------|
  |-- ICE_CANDIDATE(s) -----------> |
  |                                   |
  |  [WebRTC connected — video flows] |
  |                                   |
  |-- SESSION_END ------------------> |  (or either side)
```

## Message Reference

See `src/messages.ts` for the complete TypeScript type definitions.

| Type | Direction | Required Fields |
|---|---|---|
| `PAIR_REQUEST` | A→W | `deviceName`, `uuid`, `platformVersion` |
| `PAIR_ACCEPT` | W→A | `sessionId` |
| `PAIR_REJECT` | W→A | `reason` |
| `SESSION_START` | A→W | `sessionId` |
| `SESSION_END` | Either | `sessionId`, `reason` |
| `SDP_OFFER` | W→A | `sessionId`, `sdp` |
| `SDP_ANSWER` | A→W | `sessionId`, `sdp` |
| `ICE_CANDIDATE` | Either | `sessionId`, `candidate`, `sdpMid`, `sdpMLineIndex` |
| `HEARTBEAT` | Either | `timestamp` |
| `HEARTBEAT_ACK` | Either | `timestamp` |
| `ERROR` | Either | `code`, `message` |
