"""Starting sessions from the phone, and keeping the laptop awake while Claude works.

History  - past Claude Code sessions from ~/.claude/projects, grouped by the folder
           they ran in, so the phone can start a new session there or resume one.
spawn()  - opens a normal terminal window on the laptop running claude-mobile-wrap,
           so the session is reachable from the phone and still there at the desk.
Awake    - holds a logind inhibitor (lid switch + idle) while a session is working
           or waiting for you, so closing the lid doesn't suspend mid-task.
"""

from __future__ import annotations

import asyncio
import glob
import json
import logging
import os
import re
import shutil
import signal
import subprocess
import time

log = logging.getLogger("claude-bridge")

CLAUDE_DIR = os.path.expanduser("~/.claude")
UUID_RE = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
HEAD, TAIL = 128 * 1024, 64 * 1024


def _prompt_text(content) -> str | None:
    if isinstance(content, list):
        if any(isinstance(b, dict) and b.get("type") == "tool_result" for b in content):
            return None
        content = next((b.get("text", "") for b in content if isinstance(b, dict) and b.get("type") == "text"), "")
    if not isinstance(content, str):
        return None
    s = content.strip()
    # Slash commands, caveats and injected reminders aren't what you typed.
    if not s or s.startswith("<") or s.startswith("Caveat:") or s.startswith("[Request interrupted"):
        return None
    s = re.sub(r"\s+", " ", s)
    return s if len(s) <= 90 else s[:89] + "…"


class History:
    def __init__(self):
        self.cache: dict[str, tuple[tuple, dict | None]] = {}

    def _scan(self, path: str) -> dict | None:
        try:
            st = os.stat(path)
        except OSError:
            return None
        key = (st.st_mtime, st.st_size)
        hit = self.cache.get(path)
        if hit and hit[0] == key:
            return hit[1]
        info = {"session_id": os.path.basename(path).removesuffix(".jsonl"), "cwd": None,
                "custom": None, "summary": None, "ai": None, "prompt": None, "updated": st.st_mtime}
        try:
            with open(path, "rb") as f:
                chunks = [f.read(HEAD)]
                if st.st_size > HEAD:
                    f.seek(max(HEAD, st.st_size - TAIL))
                    chunks.append(f.read())
        except OSError:
            return None
        for chunk in chunks:
            for line in chunk.split(b"\n"):
                if not line.strip():
                    continue
                try:
                    e = json.loads(line)
                except ValueError:
                    continue  # partial line at a chunk edge
                t = e.get("type")
                if t == "custom-title" and e.get("customTitle"):
                    info["custom"] = e["customTitle"]
                elif t == "summary" and e.get("summary"):
                    info["summary"] = e["summary"]
                elif t == "ai-title" and e.get("aiTitle"):
                    info["ai"] = e["aiTitle"]
                if e.get("cwd") and not info["cwd"]:
                    info["cwd"] = e["cwd"]
                if t == "user" and not info["prompt"] and not e.get("isSidechain") and not e.get("isMeta"):
                    info["prompt"] = _prompt_text((e.get("message") or {}).get("content"))
        title = info["custom"] or info["summary"] or info["ai"] or info["prompt"]
        out = None
        if info["cwd"] and title:
            out = {"session_id": info["session_id"], "cwd": info["cwd"], "title": title, "updated": info["updated"]}
        self.cache[path] = (key, out)
        return out

    def projects(self, live: dict[str, str], running: set[str], per_project: int = 10) -> list[dict]:
        home = os.path.expanduser("~")
        groups: dict[str, list[dict]] = {}
        for path in glob.glob(os.path.join(CLAUDE_DIR, "projects", "*", "*.jsonl")):
            info = self._scan(path)
            if not info:
                continue
            cwd = info["cwd"]
            if cwd.startswith("/tmp/") or not cwd.startswith(home) or not os.path.isdir(cwd):
                continue
            groups.setdefault(cwd, []).append(info)
        out = []
        for cwd, items in groups.items():
            items.sort(key=lambda i: i["updated"], reverse=True)
            out.append({
                "cwd": cwd,
                "name": os.path.basename(cwd.rstrip("/")) or cwd,
                "path": "~" + cwd[len(home):] if cwd.startswith(home) else cwd,
                "updated": items[0]["updated"],
                "sessions": [dict(i, live=live.get(i["session_id"]), running=i["session_id"] in running)
                             for i in items[:per_project]],
            })
        out.sort(key=lambda p: p["updated"], reverse=True)
        return out[:40]

    def find(self, session_id: str) -> dict | None:
        hits = glob.glob(os.path.join(CLAUDE_DIR, "projects", "*", f"{session_id}.jsonl"))
        return self._scan(hits[0]) if hits else None


def running_session_ids() -> set[str]:
    """Every claude running on the laptop (wrapped or not), from Claude Code's own registry."""
    ids = set()
    for f in glob.glob(os.path.join(CLAUDE_DIR, "sessions", "*.json")):
        try:
            with open(f) as fh:
                d = json.load(fh)
            os.kill(int(d["pid"]), 0)
            if d.get("sessionId"):
                ids.add(d["sessionId"])
        except (OSError, ValueError, KeyError, TypeError):
            continue
    return ids


def desktop_env() -> dict:
    """The graphical session's environment (WAYLAND_DISPLAY, PATH with the shim, ...)."""
    env = dict(os.environ)
    try:
        out = subprocess.run(["systemctl", "--user", "show-environment"], capture_output=True, text=True, timeout=5).stdout
    except (OSError, subprocess.SubprocessError):
        return env
    for line in out.splitlines():
        k, sep, v = line.partition("=")
        if sep and not v.startswith("$'"):
            env[k] = v
    return env


def terminal_cmd(cfg: dict, env: dict, cwd: str) -> list[str] | None:
    """Command prefix that opens a terminal window running the rest of argv."""
    if cfg.get("terminal"):
        return list(cfg["terminal"])
    path = env.get("PATH")
    if shutil.which("uwsm-app", path=path) and shutil.which("xdg-terminal-exec", path=path):
        # uwsm-app puts the window in its own scope, so it outlives bridge restarts.
        return ["uwsm-app", "--", "xdg-terminal-exec", "--app-id=claude-mobile", f"--dir={cwd}", "-e"]
    for t in ("foot", "kitty", "alacritty", "ghostty", "wezterm"):
        if shutil.which(t, path=path):
            return ["systemd-run", "--user", "--scope", "--quiet", "--slice=app.slice", t, "-e"]
    return None


def wrapper_path() -> str | None:
    p = os.path.expanduser("~/.local/bin/claude-mobile-wrap")
    return p if os.access(p, os.X_OK) else shutil.which("claude-mobile-wrap")


async def spawn(cfg: dict, cwd: str, token: str, resume: str | None, prompt: str | None = None) -> asyncio.subprocess.Process:
    env = desktop_env()
    if not env.get("WAYLAND_DISPLAY") and not env.get("DISPLAY"):
        raise RuntimeError("No desktop session on the laptop to open a terminal in")
    term = terminal_cmd(cfg, env, cwd)
    wrap = wrapper_path()
    if not term or not wrap:
        raise RuntimeError("No terminal emulator found on the laptop" if not term else "claude-mobile-wrap is not installed")
    argv = term + ["env", f"CLAUDE_MOBILE_SPAWN={token}", wrap] + (["--resume", resume] if resume else [])
    if prompt:
        argv += ["--", prompt]  # first message; "--" keeps a prompt starting with "-" literal
    log.info("spawn in %s: %s", cwd, " ".join(argv[:-1] if prompt else argv) + (" -- <prompt>" if prompt else ""))
    proc = await asyncio.create_subprocess_exec(
        *argv, cwd=cwd, env=env, stdin=asyncio.subprocess.DEVNULL,
        stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.PIPE, start_new_session=True,
    )

    started = time.monotonic()

    async def reap():
        err = await proc.stderr.read()
        code = await proc.wait()
        # Closing the window later is normal; failing right away means it never opened.
        if code and time.monotonic() - started < 15:
            log.warning("terminal exited with %s: %s", code, err.decode(errors="replace").strip()[-300:])
    asyncio.get_running_loop().create_task(reap())
    return proc


class Awake:
    """Keeps logind from suspending on lid close / idle while Claude needs the laptop."""
    MODES = ("working", "always", "off")

    def __init__(self, cfg: dict):
        self.cfg = cfg
        self.proc: asyncio.subprocess.Process | None = None
        self.last_want = 0.0

    @property
    def mode(self) -> str:
        m = self.cfg.get("keep_awake", "working")
        return m if m in self.MODES else "working"

    @property
    def active(self) -> bool:
        return self.proc is not None and self.proc.returncode is None

    def wants(self, sessions) -> bool:
        now = time.time()
        cap = float(self.cfg.get("keep_awake_waiting_minutes", 120)) * 60
        for s in sessions:
            if s.writer is None:
                continue
            if s.status == "working":
                return True
            if s.status in ("permission", "question") and now - s.status_since < cap:
                return True
        return False

    async def update(self, sessions) -> bool:
        """Returns True when the held/released state changed."""
        now = time.time()
        mode = self.mode
        if mode == "always" or (mode == "working" and self.wants(sessions)):
            self.last_want = now
        grace = float(self.cfg.get("keep_awake_grace_seconds", 180))
        hold = mode != "off" and now - self.last_want < grace
        if hold and not self.active:
            await self._start()
            return True
        if not hold and self.active:
            await self._stop()
            return True
        return False

    async def _start(self):
        exe = shutil.which("systemd-inhibit")
        if not exe:
            return
        self.proc = await asyncio.create_subprocess_exec(
            exe, "--what=handle-lid-switch:idle", "--who=Claude Mobile",
            "--why=Claude is working (phone bridge)", "--mode=block", "sleep", "infinity",
            stdin=asyncio.subprocess.DEVNULL, stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.DEVNULL,
            start_new_session=True,  # own process group, so stopping also ends `sleep`
        )
        log.info("keeping the laptop awake (mode %s)", self.mode)

    async def _stop(self):
        p, self.proc = self.proc, None
        if p and p.returncode is None:
            try:
                os.killpg(p.pid, signal.SIGTERM)
                await asyncio.wait_for(p.wait(), 3)
            except ProcessLookupError:
                pass
            except asyncio.TimeoutError:
                os.killpg(p.pid, signal.SIGKILL)
        log.info("no longer keeping the laptop awake")
