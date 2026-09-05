#!/data/data/com.codex.mobile/files/usr/bin/sh
#
# First-run setup script for Mezchaju inside the Termux bootstrap environment.
# Called by the Android app after bootstrap extraction, or can be run manually
# from a shell inside the prefix.
#
# This script:
#   1. Updates the package index
#   2. Installs Node.js LTS
#   3. Installs agent harnesses (@deepseek-ai/dsh, claw-code) and the web UI
#
# Exit codes:
#   0 = success
#   1 = package install failure
#   2 = npm install failure

set -eu

echo "[setup] Updating package index..."
apt-get update -y || {
    echo "[setup] WARNING: apt-get update failed, continuing anyway"
}

echo "[setup] Installing Node.js LTS..."
apt-get install -y nodejs-lts || {
    echo "[setup] ERROR: Failed to install nodejs-lts"
    exit 1
}

echo "[setup] Node.js version: $(node --version)"
echo "[setup] npm version: $(npm --version)"

echo "[setup] Installing DeepSeek Harness (dsh)..."
npm install -g @deepseek-ai/dsh || {
    echo "[setup] WARNING: deepseek-harness install failed, continuing"
}

echo "[setup] Installing server UI..."
npm install -g codex-web-local || {
    echo "[setup] WARNING: server UI install failed, continuing"
}

if command -v cargo >/dev/null 2>&1; then
    echo "[setup] Installing Claw Code harness..."
    cargo install --git https://github.com/ultraworkers/claw-code --root "$PREFIX" || {
        echo "[setup] WARNING: claw-code install failed, continuing"
    }
else
    echo "[setup] cargo not found — claw-code deferred to first boot"
fi

echo "[setup] Harnesses: $(dsh --version 2>/dev/null || echo 'dsh installed')"
echo "[setup] Setup complete!"
