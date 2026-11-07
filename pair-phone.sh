#!/bin/bash
# Installs the app on the USB-connected phone and pre-fills its settings.
#   ./pair-phone.sh            -> address = https://<this laptop's Tailscale name> (if Tailscale is up), else USB tunnel
#   ./pair-phone.sh --usb      -> address = http://127.0.0.1:8787 through `adb reverse` (works only while plugged in)
set -euo pipefail
cd "$(dirname "$(readlink -f "$0")")"
PORT=8787
APK=android/app/build/outputs/apk/debug/app-debug.apk
TOKEN=$(python3 -c 'import json,os;print(json.load(open(os.path.expanduser("~/.config/claude-mobile/config.json")))["token"])')

adb get-state >/dev/null 2>&1 || { echo "No phone found. Plug it in, enable USB debugging and accept the prompt on the phone."; exit 1; }

[ -f "$APK" ] || (cd android && ./gradlew -q assembleDebug)
echo "==> Installing app"
adb install -r "$APK"

URL=""
if [ "${1:-}" != "--usb" ] && command -v tailscale >/dev/null && tailscale status >/dev/null 2>&1; then
    NAME=$(tailscale status --json | python3 -c 'import json,sys; print(json.load(sys.stdin)["Self"]["DNSName"].rstrip("."))')
    URL="https://$NAME"
    # HTTPS with a Tailscale certificate (enable "HTTPS Certificates" in the admin console's
    # DNS page first); the app refuses unencrypted connections except over the USB tunnel.
    echo "==> Exposing the bridge to your tailnet only (tailscale serve, HTTPS)"
    tailscale serve --bg --https=443 "http://127.0.0.1:$PORT" >/dev/null
fi
if [ -z "$URL" ]; then
    echo "==> Tailscale not available: using a USB tunnel (only works while plugged in)"
    adb reverse tcp:$PORT tcp:$PORT
    URL="http://127.0.0.1:$PORT"
fi

echo "==> Sending pairing link ($URL)"
adb shell am start -a android.intent.action.VIEW -d "'claudemobile://setup?url=$URL&token=$TOKEN'" dev.mulm.claudemobile >/dev/null
echo "Check the phone: confirm the address and tap Save."
