# 🦞 Mezchaju

**OpenClaw gateway + DeepSeek Harness + Claw Code, natively on Android — with a dashboard you actually operate.**

Mezchaju is an Android APK that boots a full Linux environment in the app’s private storage (Termux-style), installs the agent stack — OpenClaw, DeepSeek Harness (`dsh`) and Claw Code — and opens a **native dashboard** where every service has a live status card, a real terminal, and one-tap updates. No root required. No forced OpenAI login.

> `mezchaju-v1.4.0.apk` · Android 7.0+ (ARM64) · side-load only · one stable release per build (no release spam)

---

## ✨ What you get

### 🧭 Native dashboard (the app home)
Open the app and land straight on the dashboard instead of a login wall:

- **Service cards** — OpenClaw Gateway, OpenClaw Control UI, DeepSeek Harness, Claw Code (and the bundled web UI) each show live status, installed version, port and one-tap **Start / Stop / Restart**.
- **Open UI in one tap** — the OpenClaw Control UI opens **already authenticated** (device token pre-wired), directly into its dashboard. No device-token prompt, no OpenAI login.
- **Real terminal per service** — every card opens an interactive on-device terminal. Type commands, run `dsh`, launch `claw`, tail logs — anything `sh` can do.
- **Animated, native feel** — glassmorphism cards, live status pulses, ripple buttons, bottom-sheet terminal, toasts, and a polished boot/loading animation.

### 📁 One shared workspace
- All agents share a single **`~/workspace`** folder (auto-created and `git init`-ed on first boot).
- Legacy `~/codex` folders are migrated automatically so nothing splits across drives.
- Every terminal session opens inside the shared workspace.

### 🚀 Auto-updates from the original repos
Mezchaju watches the upstream GitHub repos and **pops up a banner the moment a new release lands**:

| Service | Upstream | Update command |
|---|---|---|
| OpenClaw gateway + Control UI | [openclaw/openclaw](https://github.com/openclaw/openclaw) | `npm install -g openclaw@latest` |
| DeepSeek Harness | [deepseek-ai/deepseek-harness](https://github.com/deepseek-ai/deepseek-harness) | `npm install -g @deepseek-ai/dsh@latest` |
| Claw Code | [ultraworkers/claw-code](https://github.com/ultraworkers/claw-code) | `cargo install --git https://github.com/ultraworkers/claw-code` |

- The banner shows **installed → latest** for each service.
- **Update now** runs the update **inside that service’s terminal** (you watch the real output), then **restarts the service automatically**.
- Checks run every 30 minutes (and on every dashboard open); GitHub API is primary, with the npm registry as a fallback for npm-installed packages.

### 🔑 Bring your own provider
No forced OpenAI login. On first boot Mezchaju writes `~/.mezchaju/providers.json` for the free-model providers:

| Provider | Base URL | Key env |
|---|---|---|
| OpenCodeZen | `https://api.opencodezen.ai/v1` | `OPENCODEZEN_API_KEY` |
| OpenRouter | `https://openrouter.ai/api/v1` | `OPENROUTER_API_KEY` |
| Xkiro | `https://api.xkiro.com/v1` | `XKIRO_API_KEY` |

Paste a key in the web UI and it is persisted to `~/.mezchaju/provider.env` for every process — never hardcoded in the APK.

### 🛠 What runs on-device
- 🦞 **OpenClaw gateway** — MCP tools, multi-agent routing, local WebSocket gateway (`127.0.0.1:18789`), Control UI on `127.0.0.1:19001`.
- ⚡ **DeepSeek Harness** — `dsh`, the everything-is-a-plugin agent harness (`@deepseek-ai/dsh`).
- 🦀 **Claw Code** — the Rust-powered claw harness built from `ultraworkers/claw-code`.
- 🌐 **Mezchaju Web UI** — the bundled local web server on `127.0.0.1:18923` (start/stop from the dashboard).

## 📱 Install

1. Download **`mezchaju-v1.4.0.apk`** from the [Releases](https://github.com/canelaslorenzo0-png/Mezchaju/releases) page.
2. Allow “install unknown apps” for your browser / file manager.
3. Open the app. First boot:
   - extracts the Linux prefix (no root), installs Node.js, Python, OpenClaw, `dsh` and Claw Code;
   - starts the CONNECT proxy, gateway, Control UI and web UI;
   - opens the **dashboard** when everything is ready.

> ⚠️ First boot downloads ~100–200 MB on a good connection. Give it a few minutes; the loading screen shows live progress.

## 🏗 Build from source

```bash
# prerequisites: JDK 17, Android SDK (compileSdk 35), Node 22
cd android
./scripts/download-bootstrap.sh      # ~30 MB Termux bootstrap
./scripts/build-server-bundle.sh     # bundles the web UI + CLI
./gradlew assembleRelease            # signed APK in app/build/outputs/apk/release/
```

CI (`.github/workflows/build-apk.yml`) does all of this on `workflow_dispatch` and publishes **one stable release tag per build** — no automatic per-commit release spam.

## 🧩 How it’s wired

```
MainActivity ──► CodexServerManager
                  ├─ startProxy()              CONNECT proxy 127.0.0.1:18924 (native DNS/TLS)
                  ├─ startOpenClawGateway()    openclaw gateway run (WS 18789)
                  ├─ startOpenClawControlUiServer()  Control UI static server (19001)
                  ├─ startServer()             bundled web UI (18923)
                  └─ startDashboard()          dashboard-server.js (18922)
                                                 ├─ /health  live status + versions
                                                 ├─ /term    per-service terminal (WS)
                                                 ├─ /api     start|stop|restart|update
                                                 └─ /openui/openclaw  auto-auth redirect
```

- The dashboard is a **zero-dependency Node server** (pure `http` + RFC 6455 WebSocket) bundled in APK assets at `android/app/src/main/assets/dashboard/`.
- Terminals are interactive `sh -i` sessions in the shared workspace streamed over WebSocket to the built-in xterm.js shell.
- OpenClaw’s Control UI uses `auth.mode: "none"` (the current `openclaw` schema) so it opens straight into its dashboard with no device-token flow.

## 🗺 Project layout

```
android/app/src/main/
├─ assets/
│  ├─ dashboard/            # dashboard server + UI + xterm (in-app control panel)
│  ├─ server-bundle/        # bundled web UI assets
│  ├─ proxy.js              # CONNECT proxy used for native DNS/TLS
│  ├─ setup-codex.sh        # first-run Linux bootstrapping
│  └─ bionic-compat.js      # Android platform shim for Node
├─ java/com/codex/mobile/
│  ├─ MainActivity.kt       # boot flow → dashboard
│  ├─ CodexServerManager.kt # process lifecycle for every service
│  ├─ BootstrapInstaller.kt # prefix extraction / first-run installs
│  └─ CodexForegroundService.kt
docs/index.html             # public landing page (GitHub Pages)
```

## ✅ Status

- Dashboard with per-service start/stop/restart, real terminals, shared workspace and auto-update banners — **in v1.4.0**.
- OpenClaw gateway config rewritten for the current `openclaw` schema (`auth.mode: "none"`), fixing Control UI startup/login on fresh installs.
- Single stable release per build — no release spam.

## ⚠️ Disclaimer

This is an experimental side-loader. First boot installs OpenClaw, DeepSeek Harness and Claw Code from the network. The harnesses run with broad local permissions inside the app sandbox — install at your own discretion. Target SDK 28 is an intentional workaround for Android’s W^X enforcement (same approach as Termux F-Droid).
