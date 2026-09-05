# 🦞 Mezchaju

**OpenClaw gateway + DeepSeek Harness + Claw Code, natively on Android.**

Mezchaju is an Android APK that boots a full Linux environment in your app's private storage (Termux-style), installs the agent stack — OpenClaw, DeepSeek Harness (`dsh`), Claw Code — and serves a local web UI. No root required. No forced OpenAI login.

## Features

- 🦞 **OpenClaw gateway** — MCP tools, multi-agent routing, Control UI, on-device WebSocket gateway
- ⚡ **DeepSeek Harness** — the everything-is-a-plugin agent harness by DeepSeek AI (`@deepseek-ai/dsh`)
- 🦀 **Claw Code** — Rust-powered claw harness from `ultraworkers/claw-code`
- 🔑 **Bring your own provider** — OpenCodeZen, OpenRouter, Xkiro (free models); keys are entered in the web UI, never hardcoded
- 📱 Android 7.0+ (ARM64), no root, no Play dependency

## Provider config

On first boot the app writes `~/.mezchaju/providers.json` with stanzas for:

| Provider | Base URL | Key env |
|---|---|---|
| OpenCodeZen | `https://api.opencodezen.ai/v1` | `OPENCODEZEN_API_KEY` |
| OpenRouter | `https://openrouter.ai/api/v1` | `OPENROUTER_API_KEY` |
| Xkiro | `https://api.xkiro.com/v1` | `XKIRO_API_KEY` |

Paste a provider key in the web UI (Settings → Provider) and the harnesses route through it.

## Build

```bash
# prerequisites: JDK 17, Android SDK (compileSdk 35), Node 22
cd android
./scripts/download-bootstrap.sh          # ~30 MB Termux bootstrap
./scripts/build-server-bundle.sh         # bundles codex-web-local UI + CLI
./gradlew assembleRelease
```

CI (`build-apk.yml`) does all of this and publishes a signed APK release.

## Disclaimer

This is an experimental side-loader. First boot installs OpenClaw, DeepSeek Harness and Claw Code from the network. The harnesses run with broad local permissions inside the app sandbox — install at your own discretion.
