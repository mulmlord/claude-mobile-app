#!/bin/bash
# Makes the phone reachable over adb through Tailscale, so builds can be installed and
# screens checked without the cable.
#   ./phone-adb.sh   (phone plugged in once: switches adb to TCP, then connects over Tailscale)
# TCP mode lasts until the phone restarts; after a reboot, plug in and run this again.
# Only computers the phone has already authorized can connect.
set -euo pipefail
PORT=5555
IP=$(tailscale status --json | python3 -c '
import json, sys
d = json.load(sys.stdin)
print(next((p["TailscaleIPs"][0] for p in d.get("Peer", {}).values() if p.get("OS") == "android" and p.get("Online")), ""))')
[ -n "$IP" ] || { echo "No Android phone online on Tailscale (is Tailscale on, on the phone?)"; exit 1; }
USB=$(adb devices | awk 'NR > 1 && $2 == "device" && $1 !~ /:/ {print $1; exit}')
if [ -n "$USB" ]; then
    echo "==> Switching adb on $USB to TCP port $PORT"
    adb -s "$USB" tcpip "$PORT" >/dev/null
    sleep 3
fi
adb connect "$IP:$PORT"
echo "Phone: adb -s $IP:$PORT …  (export ANDROID_SERIAL=$IP:$PORT to make it the default)"
