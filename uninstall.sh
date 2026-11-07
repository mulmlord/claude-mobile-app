#!/bin/bash
# Removes everything install.sh added (keeps ~/.config/claude-mobile with the token).
set -euo pipefail
BIN=$HOME/.local/bin
SETTINGS=$HOME/.claude/settings.json

systemctl --user disable --now claude-mobile-bridge.service 2>/dev/null || true
rm -f "$HOME/.config/systemd/user/claude-mobile-bridge.service"
systemctl --user daemon-reload
rm -rf "$HOME/.local/share/claude-mobile/venv"

python3 - "$SETTINGS" "$BIN/claude-mobile-hook" <<'EOF'
import json, sys
path, hook = sys.argv[1], sys.argv[2]
with open(path) as f:
    s = json.load(f)
hooks = s.get("hooks", {})
for event in list(hooks):
    groups = []
    for g in hooks[event]:
        g["hooks"] = [h for h in g.get("hooks", []) if h.get("command") != hook]
        if g["hooks"]:
            groups.append(g)
    if groups:
        hooks[event] = groups
    else:
        del hooks[event]
with open(path, "w") as f:
    json.dump(s, f, indent=2)
    f.write("\n")
EOF

sed -i '/# >>> claude-mobile >>>/,/# <<< claude-mobile <<</d' "$HOME/.bashrc"

SHIM=$HOME/.local/share/claude-mobile/shim
rm -f "$HOME/.config/uwsm/env.d/90-claude-mobile"
rm -rf "$SHIM"
CUR=$(systemctl --user show-environment | sed -n 's/^PATH=//p')
NEW=$(printf '%s' ":$CUR:" | sed "s#:$SHIM:#:#g; s#^:##; s#:\$##")
[ "$NEW" != "$CUR" ] && systemctl --user set-environment "PATH=$NEW"
HYPR=$HOME/.config/hypr/hyprland.lua
if [ -f "$HYPR" ] && grep -q '>>> claude-mobile >>>' "$HYPR"; then
    sed -i '/^-- >>> claude-mobile >>>/,/^-- <<< claude-mobile <<</d' "$HYPR"
    hyprctl reload >/dev/null 2>&1 || true
fi
rm -f "$BIN/claude-mobile-wrap" "$BIN/claude-mobile-hook"
echo "Uninstalled. Open a new terminal to drop the claude() function."
