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
  opens by itself. A Read of an image (a screenshot Claude looks at) shows the picture in its
  row, served by the bridge only for files the transcript says Claude read; tap for full screen,
  pinch to zoom. Long-press a message to copy it; code blocks have a Copy button. The composer
  has a mic (Android speech-to-text). The **+** button sends a photo (shrunk to 2000 px JPEG) to
  `~/.cache/claude-mobile/uploads/` (kept 14 days) and puts its path in the message, where
  Claude reads it. The chat header shows how full the context is (models seen past 200k
  tokens count as 1M, remembered in `big_context_models`).
* **Search a chat** (⋮ → Search): matches messages, tool names, commands and output, case
  insensitive. "3 / 12" with ↑ older / ↓ newer jumps between hits, starting at the newest;
  the current one is outlined in the accent colour (a tool row opens when the hit is in its
  command or output), the others are tinted faintly. Back closes the search.
* **Rename and end sessions** from the chat's ⋮ menu or by long-pressing a session card.
  Rename types `/rename <name>` into the session (one line, at most 80 characters), so
  Claude Code stores it as the conversation's title (a `custom-title` transcript entry) and
  it shows up on the phone and in `claude --resume`. This works while Claude is working
  (`/rename` runs mid-turn rather than queueing) but not while a permission prompt or
  question is open, since the typing would land in it. End interrupts a busy session (Esc),
  clears the input line and types `/exit`; the conversation stays in history and can be
  resumed. Force quit sends SIGTERM to the session's `claude` (SIGKILL 10 s later if it's
  wedged), checking first that the pid is still the wrapper's child.
* **Appearance:** Settings → System / Light / Dark.
* **GitHub** (branch icon on the home screen, or in a chat whose folder is a clone) runs
  through the laptop's signed-in `gh`, so the phone holds no GitHub token. Repositories
  with open PR/issue counts and CI state; per repo: pull requests (checks, per-file diffs,
  conversation; comment, review, merge with squash/merge/rebase, close, mark ready),
  issues (comment, close/reopen, create), Actions runs (failed-step log, re-run failed
  jobs) and the clone on the laptop (status, recent commits, pull, push, open a PR, clone
  if missing). "Work on it" / "Ask Claude to review" / "Ask Claude to fix it" start a
  session in the clone with an editable first message.
* **Updates without USB.** The bridge offers the newest APK Gradle built in this repo
  (`android/app/build/outputs/apk/{release,debug}`, from `output-metadata.json`, whichever
  has the higher versionCode; `apk_dir` in the config overrides the folder). Build the fast
  one with `./gradlew assembleRelease` (R8, signed with the debug key so it updates in place
  over debug builds; not debuggable, so `run-as`/jdb need a debug build). When its versionCode is higher, the app shows "Update ready";
  Settings → App downloads it over Tailscale, checks the sha256 and hands it to Android's
  installer. Android asks once to allow installs from Claude Mobile.
* **Questions** (AskUserQuestion) show as a form in the chat: every question, its options
  (one, or any with multiple choice), and a field for your own answer; Submit enters it all
  on the laptop's question screen key by key (an option's number; for typed text "Type
  something.", or with multiple choice: tick, arrow onto it, type, Tab, Enter; then the review
  screen's Submit), checking after each step that the screen moved on. Claude Code writes a
  question to the transcript only once it's answered, so the questions come from a
  PreToolUse hook (matcher AskUserQuestion, set up by install.sh; PostToolUse clears it).
  The ⋮ menu also has **Switch mode** (Shift+Tab, then shows the mode the status area reports)
  and **Compact** (`/compact`).
* **Menus** such as permission prompts, the folder-trust dialog and AskUserQuestion are
  read off the session's virtual screen and offered as buttons. The `>_` view shows the
  live terminal, in the laptop's colours (the bridge
  sends style runs from pyte; the app maps the 16 ANSI colours to a warm palette), wrapped to
  the phone's width, with prompt-box side walls removed, rules drawn as hairlines and long
  runs of padding shrunk; the status lines under the prompt box keep one row each (one font
  size, shrunk to fit), and symbols the phone's fonts lack get stand-ins (⏵ → ▸). Claude Code
  only redraws what changes, so when a wrapper connects to a bridge that wasn't there from
  the start, the bridge has it repaint (the session's terminal a row shorter for half a
  second, then back: a resize); `↻ Redraw` in the view does the same on demand. Opening a
  chat sends its newest 60 items first, the rest right after.
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
  push, no accounts). Deep sleep doesn't stall it: after a drop the app retries at 1, 2, 4
  and 8 s holding a short wakelock, later retries and a 15-minute check (which also pings the
  bridge, in case the socket died while the phone slept) use allow-while-idle alarms, and the
  bridge gives a sleeping phone a minute to answer its pings. After ~3 minutes of failures
  with the screen off it retries every 5 minutes; opening the app tries at once. When the
  phone's Tailscale is the problem, the app and the status notification say so and offer
  **Open Tailscale**. Settings → **Connection log** lists when the phone connected, dropped
  or couldn't connect and why (kept on the phone, survives the app being killed; Copy puts it
  on the clipboard). Permission notifications have Allow/Deny/**Tell Claude…** (deny and type
  what to do instead) and say what's waiting
  ("Edit · app/src/x.kt (+3 −1)"); expanded, they show a compact red/green diff of the
  edit or the Bash command, so you can decide from the shade. Questions (AskUserQuestion
  with a single single-choice question) get up to three of their options as buttons; the
  bridge picks the matching option on screen by its label and refuses if that question
  was already answered. Finished ones have an inline Reply. They're muted while you're
  typing into that session at the laptop (`quiet_after_local_input` in
  `~/.config/claude-mobile/config.json`, default 45 s).
* **Offline**: the app keeps the last session list and each chat's last 200 items on the
  phone (`filesDir/chats`, written a few seconds after changes, dropped after 14 days). On start
  and while the laptop is asleep, off or unreachable it shows them, read-only and marked
  offline, until the bridge sends the live state.
* **Home-screen widget** ("Claude sessions", 4×2, resizable): sessions that need you first,
  then the most recent, each opening its chat; **+** opens New session. The background
  service redraws it as sessions change (at most once a second; the widget never polls or
  connects by itself). When the laptop is away it keeps the last list, marked
  "Offline since …". Light or dark follows the system.

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
# Tailscale admin console → DNS → HTTPS Certificates → Enable (once per tailnet)
./pair-phone.sh                         # now pairs with https://<laptop>.<tailnet>.ts.net
```

On the phone install Tailscale from the Play Store and log in with the same account.
In Claude Mobile → Settings, allow the background connection (battery optimization),
otherwise Android may delay notifications. Give Tailscale "No restrictions" for battery
(and Autostart on Xiaomi) too, and leave its "Block connections without VPN" off: with it
on, every Tailscale hiccup cuts the phone off completely until Tailscale reconnects.

The laptop has to stay awake (not suspended) for any of this to work.

## Pieces

| Path | What |
|---|---|
| `bin/claude-mobile-wrap` | Stdlib-only PTY wrapper; connects to the bridge best-effort |
| `bin/claude-mobile-hook` | Hook → bridge forwarder (curl over a unix socket, always exits 0) |
| `bridge/` | Python daemon (aiohttp + pyte): sessions, transcripts, screen, API |
| `android/` | Kotlin/Compose app (`./gradlew assembleRelease`; `assembleDebug` for debugging) |
| `uninstall.sh` | Reverts everything `install.sh` did |

Logs: `journalctl --user -u claude-mobile-bridge -f`

### Security notes

* The bridge token is the key to the laptop: anyone with it and network reach can type into
  sessions. It's only accepted in the `Authorization` header (never in URLs).
* The app refuses to talk to a tailnet address (`*.ts.net`, `100.64.0.0/10`) while the phone has
  no VPN up, so a paused or killed Tailscale can't make it send the token to whatever the local
  Wi-Fi's DNS answers. (Don't use Tailscale's "Block connections without VPN" for this: it cuts
  the phone off completely whenever Tailscale hiccups.)
* The app only talks HTTPS (`res/xml/network_security_config.xml`): the bridge is served by
  `tailscale serve --bg --https=443 http://127.0.0.1:8787` with the tailnet's certificate (Tailscale
  admin console → DNS → HTTPS Certificates), address `https://<laptop>.<tailnet>.ts.net`. The one
  exception is `pair-phone.sh --usb` (`http://127.0.0.1` through `adb reverse`, never off the
  cable). Pairings with an older `http://…:8787` address move themselves to `https://` once
  the same host answers there with the token.
* Allow/Deny from a notification is tied to the prompt it showed: the bridge reads the prompt's
  text off the session's screen, the notification shows it (when more than one tool call is
  waiting, instead of a guessed tool preview) and sends its fingerprint back; if a different
  prompt is up by then, the answer is refused.

### API (token in `Authorization: Bearer …`)

`GET /api/sessions`, `GET /api/sessions/{id}/items`, `GET /api/sessions/{id}/screen`,
`POST /api/sessions/{id}/send {"text"}`, `POST …/keys {"keys":["esc","enter","up",…]}`,
`POST …/select {"index"}`, `POST …/answer {"choice":"yes|always|no", "dh"?, "message"?}` (with `no`: typed in as the next turn once the prompt closes),
`POST …/choose {"label", "qid"?}` (dialog option by label; 409 if none matches or question `qid` is gone),
`POST …/answers {"qid", "answers": [{"options": [labels], "text"}]}` (a whole AskUserQuestion), `GET …/items/{iid}/image` (an image Claude read), `POST …/redraw` (have Claude Code repaint its screen), `POST …/end {"force"?: bool}`, `POST …/rename {"name"}` (409 when the session isn't connected
or a dialog is open),
WebSocket `/api/ws` (`notify` events: `id, sid, kind, title, body`, plus `tool` {`name, summary,
detail` or `diff, add, del`} on permission and `qid, options` on questions),
`GET /api/projects`, `POST /api/spawn {"cwd", "resume"?}`, `GET|POST /api/settings {"keep_awake"}`,
`POST /api/sessions/{id}/upload` (image body), `GET /api/app`, `GET /api/app/apk`,
`/api/gh/repos`, `/api/gh/{owner}/{name}/{prs,issues,runs,local}[/…]` (see `bridge/claude_bridge/github.py`).

Config (`~/.config/claude-mobile/config.json`): `keep_awake` (`working`|`always`|`off`),
`keep_awake_waiting_minutes` (120), `keep_awake_grace_seconds` (180), and optionally
`terminal`, a command prefix such as `["foot", "-e"]` if the default launcher doesn't suit.

## Tests

```bash
cd bridge && uv run pytest                      # the bridge's suite: offline, a few seconds
cd android && ./gradlew testDebugUnitTest       # the app's: terminal view clean-up, widget (Robolectric)
```

The service runs from its own environment (`~/.local/share/claude-mobile/venv`, made by
`install.sh` without the test tools); `bridge/.venv` is the development one these use.

`bridge/tests/` runs against temp dirs only: HOME and the XDG dirs point into a sandbox,
`gh`, `git`, terminals and `systemd-inhibit` are faked, and the wrapper protocol is driven
by a fake wrapper over a real unix socket. It never touches the running bridge,
`~/.claude` or `~/.config`.
