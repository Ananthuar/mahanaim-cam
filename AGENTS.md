# AGENTS.md — MAHANAIM CAM Project Conventions

This file describes conventions and architecture constraints for developers and AI coding agents working on the MAHANAIM CAM project.

---

## Project Architecture Constraints

### 1. No Cloud Signaling
All WebRTC signaling MUST be done via the local WebSocket server running inside the Electron main process. Do NOT introduce Firebase Realtime Database, Firebase Cloud Messaging, STUN servers beyond public ICE helpers, or any cloud-based signaling relay.

### 2. No Paid Services
Do not add dependencies or integrations that require a paid subscription, API key purchase, or account with a commercial SaaS provider.

### 3. No Watermarks
The video output must never contain any overlaid watermarks, logos, or branding from dependencies. Evaluate all WebRTC libraries for watermark-free license terms.

### 4. No Custom Windows Virtual Camera Driver
For Milestone 1–3, OBS Virtual Camera is the only output mechanism to Google Meet. Do not design or implement a custom Windows DirectShow filter or KSCATEGORY_VIDEO_CAMERA driver.

### 5. No MJPEG Primary Transport
The video transport for camera streaming is WebRTC (SRTP). MJPEG over HTTP must not be used as the primary video stream. It may be used as a debug/fallback only, clearly marked.

### 6. No Placeholder Code Disguised as Working Features
If a function is not yet implemented, it MUST:
- Be marked with a `TODO(milestone-N):` comment
- Log a clear warning: `[STUB] <function name> not yet implemented`
- NOT silently succeed or return fake data

### 7. Port Conventions
| Port | Service | Notes |
|---|---|---|
| 8765 | WebSocket Signaling | ws:// — Electron main process |
| 8766 | OBS Browser Source HTTP | http:// — static page |

Do not change these without updating ARCHITECTURE.md and the Android app defaults.

---

## Code Style

### TypeScript (Windows Studio + Protocol)
- Use **TypeScript strict mode** (`"strict": true`)
- Use **named exports** not default exports for modules (except React components which may use default export)
- Use `interface` for data shapes, `type` for unions/intersections
- Prefix private class members with `_` only when ambiguity with accessors exists
- All async functions must handle errors with try/catch; never swallow errors silently
- Log format: `[ComponentName] message` using `console.log` / `console.error`

### Kotlin (Android)
- Follow [Android Kotlin Style Guide](https://developer.android.com/kotlin/style-guide)
- All Compose screens live in `ui/screens/`
- All Compose theme files live in `ui/theme/`
- Use `ViewModel` + `StateFlow` for UI state (Milestone 2+)
- Camera and WebRTC operations run on dedicated coroutine dispatchers (not `Dispatchers.Main`)
- Log tag format: `"MahanaimCAM/<ClassName>"`

---

## Monorepo Structure Rules

```
apps/windows-studio/   — Electron + React + TypeScript
apps/android-mobile/   — Kotlin + Jetpack Compose
packages/protocol/     — Shared TypeScript type definitions only
docs/                  — Supplementary documentation
```

- `packages/protocol` must have **zero runtime dependencies** — it is a type-only package
- Cross-package imports in Node: use npm workspace references (`"@mahanaim-cam/protocol": "*"`)
- Do NOT import from `apps/` within `packages/` — packages must be standalone

---

## Testing Requirements

### Windows Studio
- All new utility functions in `src/main/` must have a corresponding unit test in `test/`
- React components must have at minimum a render smoke test
- Test runner: **Vitest**
- Run: `npm run test` from `apps/windows-studio/`

### Android Mobile
- All non-UI logic (message parsing, state machines) must have a unit test
- Test runner: **JUnit 4** via Gradle
- Run: `./gradlew test` from `apps/android-mobile/`

---

## Dependency Management

- Pin all major dependency versions (e.g., `"electron": "30.x"` is fine; `"*"` is not)
- Document any new dependency in ARCHITECTURE.md dependency table
- Evaluate WebRTC-related packages carefully:
  - Android: use `org.webrtc:google-webrtc` from Google's Maven
  - Windows: evaluate `wrtc`, Electron's built-in WebRTC, or `node-webrtc` — document the decision
- Do not add packages that have not been updated in > 2 years without documenting why

---

## Git Conventions

- Branch names: `feat/<milestone>/<description>` or `fix/<description>`
- Commit messages: `[Component] Short description` (e.g., `[windows-studio] Add signaling server stub`)
- Do not commit `node_modules/`, `dist/`, `build/`, `.gradle/`, or `local.properties`
- PRs must pass lint and test before merge

---

## AI Agent Rules

When an AI coding agent works on this project:

1. **Read this file first.** Then read ARCHITECTURE.md.
2. **Do not implement all features at once.** Focus on the current milestone.
3. **Do not leave placeholder code without TODO comments.** See constraint #6 above.
4. **Run builds after changes.** Report actual build output, not assumed success.
5. **Do not invent ports, protocols, or message types** not defined in `packages/protocol/`.
6. **Prefer targeted edits** over full file rewrites.
7. **Document SDK/environment requirements** that cannot be satisfied automatically.
