#!/bin/bash
# Installs the laptop side of Claude Mobile. Safe to re-run.
#   - claude-mobile-wrap / claude-mobile-hook  -> ~/.local/bin (symlinks)
#   - `claude` shell function in ~/.bashrc     -> every interactive claude is wrapped
#   - hooks in ~/.claude/settings.json          -> status + notifications
#   - systemd user service claude-mobile-bridge -> the bridge daemon
set -euo pipefail
cd "$(dirname "$(readlink -f "$0")")"
ROOT=$PWD
BIN=$HOME/.local/bin
SETTINGS=$HOME/.claude/settings.json
STAMP=$(date +%Y%m%d-%H%M%S)

echo "==> Installing scripts into $BIN"
mkdir -p "$BIN"
chmod +x bin/claude-mobile-wrap bin/claude-mobile-hook
ln -sf "$ROOT/bin/claude-mobile-wrap" "$BIN/claude-mobile-wrap"
ln -sf "$ROOT/bin/claude-mobile-hook" "$BIN/claude-mobile-hook"

# The service gets its own environment without the test tools (pytest);
# bridge/.venv stays the development one that `uv run pytest` uses.
VENV=$HOME/.local/share/claude-mobile/venv
echo "==> Bridge dependencies ($VENV)"
(cd bridge && UV_PROJECT_ENVIRONMENT="$VENV" uv sync --no-dev --quiet)

echo "==> Claude Code hooks ($SETTINGS)"
cp "$SETTINGS" "$SETTINGS.bak-claude-mobile-$STAMP"
python3 - "$SETTINGS" "$BIN/claude-mobile-hook" <<'EOF'
import json, sys
path, hook = sys.argv[1], sys.argv[2]
with open(path) as f:
    s = json.load(f)
hooks = s.setdefault("hooks", {})
for event in ("SessionStart", "UserPromptSubmit", "Stop", "Notification", "SessionEnd"):
    groups = hooks.setdefault(event, [])
    if any(h.get("command") == hook for g in groups for h in g.get("hooks", [])):
        continue
    groups.append({"hooks": [{"type": "command", "command": hook, "timeout": 5}]})
with open(path, "w") as f:
    json.dump(s, f, indent=2)
    f.write("\n")
EOF

echo "==> Shell function in ~/.bashrc"
if ! grep -q '>>> claude-mobile >>>' "$HOME/.bashrc"; then
    cp "$HOME/.bashrc" "$HOME/.bashrc.bak-claude-mobile-$STAMP"
    cat >> "$HOME/.bashrc" <<'EOF'

# >>> claude-mobile >>>
# Every interactive `claude` runs through a transparent wrapper so the phone app can reach it.
# Bypass once with: command claude ...   Disable: CLAUDE_MOBILE_DISABLE=1
claude() { "$HOME/.local/bin/claude-mobile-wrap" "$@"; }
# <<< claude-mobile <<<
EOF
fi

# The bash function only covers claude typed at a bash prompt. Desktop launches
# (Omarchy's agent key, the console, menus) run `claude` straight from the uwsm
# session PATH, so a `claude` shim goes first there. uwsm sources ~/.config/uwsm/env.d
# after Omarchy's own env, so it stays ahead of mise. The wrapper skips itself
# when it looks up the real claude, so the shim can't loop.
echo "==> claude shim on the desktop session PATH"
SHIM=$HOME/.local/share/claude-mobile/shim
mkdir -p "$SHIM" "$HOME/.config/uwsm/env.d"
ln -sf "$ROOT/bin/claude-mobile-wrap" "$SHIM/claude"
cat > "$HOME/.config/uwsm/env.d/90-claude-mobile" <<'EOF'
# claude-mobile: every `claude` started from the desktop (Omarchy agent key, console,
# menus) runs through the phone wrapper. Removed by claude_mobile_app/uninstall.sh.
__cm_shim="$HOME/.local/share/claude-mobile/shim"
PATH=$(printf '%s' ":$PATH:" | sed "s#:$__cm_shim:#:#g; s#^:##; s#:\$##")
export PATH="$__cm_shim${PATH:+:$PATH}"
unset __cm_shim
EOF
# uwsm reads env.d only at login; put the shim on the running session's PATH too.
CUR=$(systemctl --user show-environment | sed -n 's/^PATH=//p')
CUR=$(printf '%s' ":$CUR:" | sed "s#:$SHIM:#:#g; s#^:##; s#:\$##")
systemctl --user set-environment "PATH=$SHIM${CUR:+:$CUR}"
# Omarchy's agent windows run in a scope that inherits Hyprland's own environment
# rather than the systemd one, so Hyprland's PATH needs the shim as well.
HYPR=$HOME/.config/hypr/hyprland.lua
if [ -f "$HYPR" ] && ! grep -q '>>> claude-mobile >>>' "$HYPR"; then
    cp "$HYPR" "$HYPR.bak.$(date +%s)"
    cat >> "$HYPR" <<'EOF'

-- >>> claude-mobile >>>
-- Omarchy's agent windows (agent key, console) inherit Hyprland's PATH, so the
-- claude-mobile shim goes first here too. Removed by claude_mobile_app/uninstall.sh.
do
  local shim = os.getenv("HOME") .. "/.local/share/claude-mobile/shim"
  local rest = {}
  for dir in (os.getenv("PATH") or ""):gmatch("[^:]+") do
    if dir ~= shim then rest[#rest + 1] = dir end
  end
  hl.env("PATH", shim .. (#rest > 0 and ":" .. table.concat(rest, ":") or ""))
end
-- <<< claude-mobile <<<
EOF
    hyprctl reload >/dev/null 2>&1 || true
fi

echo "==> systemd user service"
mkdir -p "$HOME/.config/systemd/user"
cat > "$HOME/.config/systemd/user/claude-mobile-bridge.service" <<EOF
[Unit]
Description=Claude Mobile bridge (phone <-> Claude Code sessions)

[Service]
ExecStart=$VENV/bin/python -m claude_bridge
WorkingDirectory=$ROOT/bridge
Restart=always
RestartSec=2

[Install]
WantedBy=default.target
EOF
systemctl --user daemon-reload
systemctl --user enable --now claude-mobile-bridge.service
systemctl --user restart claude-mobile-bridge.service

TOKEN=$(python3 -c 'import json,os;print(json.load(open(os.path.expanduser("~/.config/claude-mobile/config.json")))["token"])' 2>/dev/null || true)
for _ in 1 2 3 4 5 6 7 8 9 10; do
    [ -n "$TOKEN" ] && break
    sleep 0.5
    TOKEN=$(python3 -c 'import json,os;print(json.load(open(os.path.expanduser("~/.config/claude-mobile/config.json")))["token"])' 2>/dev/null || true)
done

cat <<EOF

Done. Open a NEW terminal (or run: source ~/.bashrc) and start claude as usual.
Sessions that were already running are not wrapped; restart them with: claude --continue

Token: $TOKEN
Pair the phone over USB:  ./pair-phone.sh
EOF
