# 🦞 Mezchaju

**OpenClaw gateway + DeepSeek Harness + Claw Code, natively on Android — with a dashboard you actually operate.**

Mezchaju is an Android APK that boots a full Linux environment in the app’s private storage (Termux-style), installs the agent stack — OpenClaw, DeepSeek Harness (`dsh`) and Claw Code — and opens a **native dashboard** where every service has a live status card, a real terminal, and one-tap updates. No root required. No forced OpenAI login.

> `mezchaju-v1.6.0.apk` · Android 7.0+ (ARM64) · side-load only · one stable release per build (no release spam)

---

## ✨ What you get

### 🧭 Native dashboard (the app home)
Open the app and land straight on a **100% native dashboard** — real Kotlin views, **no WebView, no HTML behind the home screen** (the WebView is only used when you tap **Open UI** on a service card):

- **Service cards** — OpenClaw Gateway, OpenClaw Control UI, DeepSeek Harness, Claw Code (and the bundled web UI) each show live status, installed version, and a **port** badge for servers or a **CLI** badge for agents.
- **Ports vs CLI agents** — only *servers* bind a port: OpenClaw Gateway `18789`, Control UI `19001`, Web UI `18923` (dashboard API on `18922`). `dsh` and Claw Code are **on-demand CLI commands** (`dsh`, `claw`) — they have no port because they only run when you launch them from a terminal.
- **Real Termux inside** — every card opens a genuine **Termux terminal**: a real POSIX PTY (`libtermux.so` from `termux-app`), real `bash`, real signals, terminal resizing and soft-keyboard input. Not a fake HTML console.
- **Open UI in one tap** — the OpenClaw Control UI opens **already authenticated** (device token pre-wired), directly into its dashboard. No device-token prompt, no OpenAI login.
- **Animated, native feel** — smooth transitions between home, terminal and web views, live status pulses, styled service cards, and the polished boot/loading animation.

### 🛡 Reliability & observability
- **Crash watchdog** — OpenClaw gateway, Control UI, web UI, dashboard and proxy restart automatically if they die (with backoff), and their card shows a `↻ crash restarts` chip.
- **Per-service log viewer** — every card has a **LOGS** button that tails the real log file (`~/.mezchaju/logs/<svc>.log`) with a live filter; services started from the dashboard already wrote these, now boot services do too.
- **Update notifications** — the foreground service pings upstreams and posts a notification the moment a new OpenClaw / dsh / Claw Code release lands.

### ⌨️ Terminals & tools
- **Terminal tabs** — one real Termux PTY per service stays open; tap tabs to switch, `×` to close. Updates and quick commands run inside the service's tab.
- **⌘ Quick commands** — one-tap palette per service (status, tokens, doctor, version, ports…).
- **`mez` CLI** — installed into `$PREFIX/bin` and available in every terminal: `mez status`, `mez log`, `mez update`, `mez providers`, `mez skills`, `mez ports`, `mez workspace`.
- **Notification controls** — Start/Stop Gateway and open a terminal straight from the notification shade.

### 💾 Files & skills
- **Workspace file browser** — native file list (`~/workspace`): navigate folders, **TERMINAL HERE** to open a shell in that folder, share or delete files.
- **Backup & restore** — **BACKUP** zips workspace + providers + config for sharing; **RESTORE** picks a backup file and applies it (including on a fresh boot before the harness install).
- **🧩 Skill packs** — Android Dev, Git Workflow and Web Scraping guides install into `~/workspace/skills` from the dashboard; agents working in the shared workspace pick them up.
- **⚡ Provider health** — one tap tests OpenCodeZen / OpenRouter / Xkiro keys and reports latency + model counts.

### 🏠 Home-screen widget
- A native widget shows **live gateway / Control UI / web UI status** (● online, ○ offline).
- **GATEWAY** button toggles the OpenClaw gateway on/off with one tap; **OPEN** jumps into the app.
- Stays in sync with the dashboard — and refreshes itself even when the app is in the background.

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

1. Download **`mezchaju-v1.6.0.apk`** from the [Releases](https://github.com/canelaslorenzo0-png/Mezchaju/releases) page.
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

CI (`.github/workflows/build-apk.yml`) does all of this on `workflow_dispatch` and publishes **one stable release tag per version** — no automatic per-commit release spam.

## 🎛 Custom builds (same release, no extra tags)

All variants attach to the **same** release of the current version as separate APK assets — one release, never a spam of tags:

| Variant | Gradle flags | What changes |
|---|---|---|
| `stable` | — | Normal APK; creates/refreshes the release artifact |
| `lite` | `-Plite=true` | No bundled web UI assets — gateway + CLI agents + native dashboard only, smaller and faster builds |
| `arm64-only` | `-PonlyArm64=true` | Packages only `arm64-v8a` native libs (drops x86/x86_64/armeabi-v7a) |
| `debug` | `assembleDebug` | Debuggable APK; **long-press the 🦞 title** on the dashboard to wipe all data (prefix, workspace, state) |
| `unsigned` | `-Punsigned=true` | Unsigned release APK for self-signing |
| `branded` | `-PbrandName=… -PbrandAccent=…` | Custom app label + accent color (hex without `#`) |

How to build one: **Actions → Build APK → Run workflow** → pick the variant (and brand inputs). Artifacts are also uploaded to the run, and attached to the current release when it exists.

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

- The **native dashboard** is Kotlin views in `NativeDashboard.kt`; it talks to a **zero-dependency Node server** (`dashboard-server.js`, pure `http` + RFC 6455 WebSocket) bundled at `android/app/src/main/assets/dashboard/` for `/health` status, `/api` control actions and update checks.
- **Real Termux terminals** live in `NativeTerminal.kt`: `com.termux.termux-app:terminal-emulator` + `terminal-view` spawn `bash` on a real PTY via `JNI.createSubprocess(...)` inside the app’s Termux prefix (`libtermux.so` packaged for all ABIs). The `dashboard-server.js` WebSocket terminal remains as a fallback for the web UI.
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
│  ├─ MainActivity.kt       # boot flow → native dashboard (WebView only for Open UI)
│  ├─ NativeDashboard.kt    # 100% native Kotlin dashboard (no HTML)
│  ├─ NativeTerminal.kt     # real Termux PTY terminal (bash in shared workspace)
│  ├─ MezchajuWidget.kt     # home-screen status widget + gateway toggle
│  ├─ CodexServerManager.kt # process lifecycle for every service
│  ├─ BootstrapInstaller.kt # prefix extraction / first-run installs
│  └─ CodexForegroundService.kt
docs/index.html             # public landing page (GitHub Pages)
```

## ✅ Status

- **v1.6.0**: crash watchdog, per-service live logs, terminal tabs, quick commands, notification controls, `mez` CLI, backup/restore, workspace file browser, skill packs, provider health checks, update notifications.
- v1.5.0: 100% native dashboard (no WebView home), real Termux PTY terminals per service, home-screen widget, custom build variants.
- Dashboard with per-service start/stop/restart, real terminals, shared workspace and auto-update banners.
- OpenClaw gateway config rewritten for the current `openclaw` schema (`auth.mode: "none"`), fixing Control UI startup/login on fresh installs.
- Single stable release per build — no release spam.

## ⚠️ Disclaimer

This is an experimental side-loader. First boot installs OpenClaw, DeepSeek Harness and Claw Code from the network. The harnesses run with broad local permissions inside the app sandbox — install at your own discretion. Target SDK 28 is an intentional workaround for Android’s W^X enforcement (same approach as Termux F-Droid).
