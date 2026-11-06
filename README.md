# Claude Mobile

Chat with the Claude Code sessions running on this laptop from an Android phone,
with push notifications when a session finishes, needs permission, or asks a question.

```
 foot terminal ── claude-mobile-wrap ── claude          (your session, unchanged)
                        │  pty output / injected input
                        ▼
 Claude Code hooks ─► bridge daemon (127.0.0.1:8787) ◄── tailscale serve ◄── Android app
                        │  reads ~/.claude/projects/*/<session>.jsonl
```

* **Automatic.** `install.sh` adds a `claude()` shell function, so every interactive
  `claude` you start runs through a transparent PTY wrapper and shows up on the phone.
  Sessions started from the desktop (Omarchy's agent key, the console, menus) never see
  that function, so `install.sh` also puts a `claude` shim
  (`~/.local/share/claude-mobile/shim`) first on the desktop PATH: the uwsm session via
  `~/.config/uwsm/env.d/90-claude-mobile`, and Hyprland itself (agent windows inherit its
  environment) via a marked block at the end of `~/.config/hypr/hyprland.lua`.
  Print mode (`-p`), subcommands and non-TTY use pass straight through.
* **Chat** shows the transcript (messages, tool calls with results). Sending a message
  types it into the session exactly as if you'd typed it at the laptop. Edit, MultiEdit and
  Write calls show a red/green line diff with +/− counts; one waiting for your permission
  opens by itself. The composer has a mic (Android speech-to-text) and quick actions:
  Continue, Yes, `/compact`, Switch mode (Shift+Tab, opens `>_` to show the mode) and
  `/clear` (asks first).
* **Menus** such as permission prompts, the folder-trust dialog and AskUserQuestion are
  read off the session's virtual screen and offered as buttons. The `>_` view shows the
  live terminal plus Esc/arrows/Enter for anything else.
* **New sessions from the phone.** "New session" lists the folders Claude Code has run in
  (from `~/.claude/projects`), newest first. Start a fresh session there or resume a recent
  one: the bridge opens a normal terminal window on the laptop (via `uwsm-app`, so it
  survives bridge restarts) running the wrapper, and the app jumps into the chat.
  Sessions already running unwrapped in a terminal are shown but can't be resumed twice.
* **Keeps the laptop awake.** While a session is working or waiting for you (up to 2 h),
  plus 3 minutes after, the bridge holds a logind `handle-lid-switch:idle` inhibitor, so
  closing the lid doesn't suspend mid-task. Settings → "Keep the laptop awake" switches
  between *While working*, *Always* (use before leaving home) and *Off*.
* **Notifications** come over a persistent socket held by a foreground service (no Google
  push, no accounts). Permission notifications have Allow/Deny, finished ones have an
  inline Reply. They're muted while you're typing into that session at the laptop
  (`quiet_after_local_input` in `~/.config/claude-mobile/config.json`, default 45 s).

## Setup

```bash
./install.sh            # wrapper, hooks, bashrc function, systemd user service
# open a new terminal; restart already-running sessions with: claude --continue
./pair-phone.sh         # install the APK over USB and pre-fill the app's settings
```

### Away from home (Tailscale)

The bridge only listens on localhost. To reach it from anywhere, put both devices on
Tailscale and expose the port to your tailnet only:

```bash
sudo pacman -S tailscale
sudo systemctl enable --now tailscaled
sudo tailscale up                       # log in
sudo tailscale set --operator=$USER     # lets pair-phone.sh run `tailscale serve`
./pair-phone.sh                         # now pairs with http://<laptop>.<tailnet>.ts.net:8787
```

On the phone install Tailscale from the Play Store and log in with the same account.
In Claude Mobile → Settings, allow the background connection (battery optimization),
otherwise Android may delay notifications.

The laptop has to stay awake (not suspended) for any of this to work.

## Pieces

| Path | What |
|---|---|
| `bin/claude-mobile-wrap` | Stdlib-only PTY wrapper; connects to the bridge best-effort |
| `bin/claude-mobile-hook` | Hook → bridge forwarder (curl over a unix socket, always exits 0) |
| `bridge/` | Python daemon (aiohttp + pyte): sessions, transcripts, screen, API |
| `android/` | Kotlin/Compose app (`./gradlew assembleDebug`) |
| `uninstall.sh` | Reverts everything `install.sh` did |

Logs: `journalctl --user -u claude-mobile-bridge -f`

### API (token in `Authorization: Bearer …`)

`GET /api/sessions`, `GET /api/sessions/{id}/items`, `GET /api/sessions/{id}/screen`,
`POST /api/sessions/{id}/send {"text"}`, `POST …/keys {"keys":["esc","enter","up",…]}`,
`POST …/select {"index"}`, `POST …/answer {"choice":"yes|always|no"}`, WebSocket `/api/ws`,
`GET /api/projects`, `POST /api/spawn {"cwd", "resume"?}`, `GET|POST /api/settings {"keep_awake"}`.

Config (`~/.config/claude-mobile/config.json`): `keep_awake` (`working`|`always`|`off`),
`keep_awake_waiting_minutes` (120), `keep_awake_grace_seconds` (180), and optionally
`terminal`, a command prefix such as `["foot", "-e"]` if the default launcher doesn't suit.
