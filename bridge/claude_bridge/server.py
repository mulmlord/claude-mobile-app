"""claude-mobile bridge daemon.

  wrappers.sock  <- claude-mobile-wrap (one connection per interactive claude):
                    terminal output for the virtual screen, input injection
  hooks.sock     <- claude-mobile-hook (HTTP POST from Claude Code hooks):
                    session id, transcript path, status changes
  127.0.0.1:8787 <- the Android app (REST + one WebSocket), token protected;
                    exposed remotely through `tailscale serve`
"""

from __future__ import annotations

import asyncio
import collections
import glob
import hashlib
import hmac
import json
import logging
import os
import re
import secrets
import socket
import struct
import time

from aiohttp import WSMsgType, web

from . import github, launcher
from .screen import Screen
from .transcript import Transcript

log = logging.getLogger("claude-bridge")

RUNTIME = os.path.join(os.environ.get("XDG_RUNTIME_DIR") or f"/run/user/{os.getuid()}", "claude-mobile")
CONFIG_DIR = os.path.join(os.environ.get("XDG_CONFIG_HOME") or os.path.expanduser("~/.config"), "claude-mobile")
CLAUDE_DIR = os.path.expanduser("~/.claude")
STATE_FILE = os.path.join(RUNTIME, "state.json")
# Where Gradle puts the debug APK of the app in this repo (config "apk_dir" overrides).
APK_DIR = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                        "..", "..", "android", "app", "build", "outputs", "apk", "debug"))
UPLOADS = os.path.join(os.environ.get("XDG_CACHE_HOME") or os.path.expanduser("~/.cache"), "claude-mobile", "uploads")

KEYS = {
    "enter": b"\r", "esc": b"\x1b", "tab": b"\t", "shift-tab": b"\x1b[Z", "backspace": b"\x7f",
    "space": b" ", "ctrl-c": b"\x03", "ctrl-o": b"\x0f", "ctrl-r": b"\x12", "ctrl-t": b"\x14",
    "ctrl-u": b"\x15", "ctrl-l": b"\x0c",
}
ARROWS = {"up": "A", "down": "B", "right": "C", "left": "D"}


def load_config() -> dict:
    os.makedirs(CONFIG_DIR, exist_ok=True)
    path = os.path.join(CONFIG_DIR, "config.json")
    cfg = {"host": "127.0.0.1", "port": 8787, "token": "", "quiet_after_local_input": 45, "keep_awake": "working"}
    if os.path.exists(path):
        with open(path) as f:
            cfg.update(json.load(f))
    if not cfg["token"]:
        cfg["token"] = secrets.token_urlsafe(24)
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w") as f:
            json.dump(cfg, f, indent=2)
    return cfg


def save_config_key(key: str, value):
    """Persist one setting changed from the phone, keeping everything else in config.json."""
    path = os.path.join(CONFIG_DIR, "config.json")
    try:
        with open(path) as f:
            cfg = json.load(f)
    except (OSError, ValueError):
        cfg = {}
    cfg[key] = value
    tmp = path + ".tmp"
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        json.dump(cfg, f, indent=2)
    os.replace(tmp, path)


def pid_alive(pid: int | None) -> bool:
    if not pid:
        return False
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        return True


def plain(md: str, n: int = 280) -> str:
    """Markdown -> short plain text for notification bodies."""
    s = re.sub(r"```.*?```", "[code]", md, flags=re.S)
    s = re.sub(r"`([^`]*)`", r"\1", s)
    s = re.sub(r"\*\*([^*]+)\*\*|__([^_]+)__", lambda m: m.group(1) or m.group(2), s)
    s = re.sub(r"^\s{0,3}#+\s*", "", s, flags=re.M)
    s = re.sub(r"\[([^\]]+)\]\([^)]+\)", r"\1", s)
    s = re.sub(r"\s+", " ", s).strip()
    return s if len(s) <= n else s[: n - 1] + "…"


# Models seen using more than 200k tokens of context have the 1M window. Learned
# from real sessions and kept in config.json, since transcripts don't say it.
BIG_CONTEXT_MODELS: set[str] = set()


def context_window(t) -> int:
    if not t:
        return 200_000
    if t.context_tokens > 200_000 and t.model and t.model not in BIG_CONTEXT_MODELS:
        BIG_CONTEXT_MODELS.add(t.model)
        save_config_key("big_context_models", sorted(BIG_CONTEXT_MODELS))
    return 1_000_000 if t.context_tokens > 200_000 or t.model in BIG_CONTEXT_MODELS else 200_000


class Session:
    def __init__(self, hello: dict):
        self.id: str = hello["id"]
        self.pid: int = hello.get("pid")
        self.wrapper_pid: int = hello.get("wrapper_pid")
        self.cwd: str = hello.get("cwd", "")
        self.argv: list = hello.get("argv", [])
        self.started: float = hello.get("started", time.time())
        self.screen = Screen(hello.get("cols", 120), hello.get("rows", 40))
        self.writer: asyncio.StreamWriter | None = None
        self.claude_session_id: str | None = None
        self.transcript: Transcript | None = None
        self.name: str | None = None
        self.status = "starting"
        self.detail = ""
        self.status_since = time.time()
        self.updated = time.time()
        self.last_local_input = 0.0
        self.last_remote_input = 0.0
        self.exited = False
        self.cc_status: str | None = None

    @property
    def project(self) -> str:
        return os.path.basename(self.cwd.rstrip("/")) or self.cwd

    @property
    def title(self) -> str:
        return (self.transcript.title if self.transcript and self.transcript.title else None) or self.name or self.project

    def set_status(self, status: str, detail: str = ""):
        if status != self.status or detail != self.detail:
            self.status, self.detail = status, detail
            self.status_since = time.time()
            self.updated = time.time()
            return True
        return False

    def last_text(self) -> str:
        if not self.transcript:
            return ""
        for iid in reversed(self.transcript.order[-40:]):
            it = self.transcript.items[iid]
            if it["kind"] in ("assistant", "user"):
                return ("You: " if it["kind"] == "user" else "") + plain(it["text"], 160)
        return ""

    def summary(self) -> dict:
        q = None
        if self.transcript and self.transcript.pending_question:
            iid = self.transcript.tool_items.get(self.transcript.pending_question)
            if iid in self.transcript.items:
                q = self.transcript.items[iid].get("questions")
        return {
            "id": self.id, "title": self.title, "project": self.project, "cwd": self.cwd,
            "status": self.status, "detail": self.detail, "status_since": self.status_since,
            "updated": self.updated, "started": self.started, "last_text": self.last_text(),
            "session_id": self.claude_session_id, "connected": self.writer is not None,
            "questions": q,
            "context": self.transcript.context_tokens if self.transcript else 0,
            "context_max": context_window(self.transcript),
            "model": self.transcript.model if self.transcript else None,
        }


class Client:
    def __init__(self, ws: web.WebSocketResponse):
        self.ws = ws
        self.sub: str | None = None
        self.screen_sub: str | None = None
        self.screen_version = -1

    async def send(self, obj: dict):
        if self.ws.closed:
            return
        try:
            await self.ws.send_str(json.dumps(obj, ensure_ascii=False))
        except (ConnectionError, RuntimeError):
            pass


class Bridge:
    def __init__(self, cfg: dict):
        self.cfg = cfg
        self.sessions: dict[str, Session] = {}
        self.clients: set[Client] = set()
        self.notifications: collections.deque = collections.deque(maxlen=100)
        self.next_notify_id = int(time.time() * 1000)
        self.dirty = False
        self.saved_state: dict = {}
        self.early_hooks: dict[str, list] = collections.defaultdict(list)
        self.notified: dict[tuple, float] = {}
        self.history = launcher.History()
        self.github = github.GitHub(self.history)
        self.awake = launcher.Awake(cfg)
        self.spawns: dict[str, asyncio.Future] = {}
        self.app_cache: tuple | None = None  # (metadata mtime, info, apk path)
        try:
            with open(STATE_FILE) as f:
                self.saved_state = json.load(f)
        except (OSError, ValueError):
            pass

    # ---- persistence -----------------------------------------------------
    def save_state(self):
        state = {sid: {"session_id": s.claude_session_id,
                       "transcript": s.transcript.path if s.transcript else None,
                       "status": s.status, "last_local_input": s.last_local_input,
                       "last_remote_input": s.last_remote_input}
                 for sid, s in self.sessions.items()}
        tmp = STATE_FILE + ".tmp"
        with open(tmp, "w") as f:
            json.dump(state, f)
        os.replace(tmp, STATE_FILE)

    # ---- transcript mapping ----------------------------------------------
    def attach_transcript(self, s: Session, path: str | None, session_id: str | None):
        if not path and session_id:
            hits = glob.glob(os.path.join(CLAUDE_DIR, "projects", "*", f"{session_id}.jsonl"))
            path = hits[0] if hits else None
        if not path or (s.transcript and s.transcript.path == path):
            if session_id:
                s.claude_session_id = session_id
            return False
        s.claude_session_id = session_id or os.path.basename(path).removesuffix(".jsonl")
        s.transcript = Transcript(path, s.cwd)
        s.transcript.poll()
        self.dirty = True
        self.save_state()
        asyncio.get_running_loop().create_task(self.push_items(s, reset=True))
        return True

    def read_cc_session_file(self, s: Session):
        """Claude Code's own registry: ~/.claude/sessions/<pid>.json."""
        try:
            with open(os.path.join(CLAUDE_DIR, "sessions", f"{s.pid}.json")) as f:
                d = json.load(f)
        except (OSError, ValueError):
            return
        if d.get("sessionId") and d["sessionId"] != s.claude_session_id:
            self.attach_transcript(s, None, d["sessionId"])
        if d.get("name") and d["name"] != s.name:
            s.name = d["name"]
            self.dirty = True
        st = d.get("status")
        if st and st != s.cc_status:
            s.cc_status = st
            # hooks are authoritative for permission/question; this fills gaps
            if st == "busy" and s.status in ("starting", "idle"):
                self.dirty |= s.set_status("working")
            elif st == "idle" and s.status in ("starting", "working"):
                self.dirty |= s.set_status("idle")

    # ---- wrappers ----------------------------------------------------------
    async def handle_wrapper(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter):
        s: Session | None = None
        try:
            while True:
                hdr = await reader.readexactly(5)
                kind = hdr[:1]
                (n,) = struct.unpack(">I", hdr[1:])
                payload = await reader.readexactly(n) if n else b""
                if kind == b"H":
                    s = self.register(json.loads(payload), writer)
                elif s is None:
                    continue
                elif kind == b"O":
                    s.screen.feed(payload)
                elif kind == b"R":
                    d = json.loads(payload)
                    s.screen.resize(d["rows"], d["cols"])
                elif kind == b"A":
                    s.last_local_input = time.time()
                elif kind == b"X":
                    s.exited = True
        except (asyncio.IncompleteReadError, ConnectionError, ValueError):
            pass
        finally:
            writer.close()
            if s and s.writer is writer:
                s.writer = None
                if s.exited or not pid_alive(s.wrapper_pid):
                    self.remove(s)
                else:
                    self.dirty = True

    def register(self, hello: dict, writer) -> Session:
        sid = hello["id"]
        s = self.sessions.get(sid)
        if s is None:
            s = Session(hello)
            self.sessions[sid] = s
            saved = self.saved_state.get(sid) or {}
            s.last_local_input = saved.get("last_local_input", 0.0)
            s.last_remote_input = saved.get("last_remote_input", 0.0)
            if saved.get("transcript"):
                self.attach_transcript(s, saved["transcript"], saved.get("session_id"))
                if saved.get("status") in ("working", "permission", "question", "idle"):
                    s.set_status(saved["status"])
            log.info("session %s registered (%s, pid %s)", sid, s.cwd, s.pid)
        else:
            s.screen.reset()
            s.screen.resize(hello.get("rows", 40), hello.get("cols", 120))
        s.writer = writer
        fut = self.spawns.pop(hello.get("spawn") or "", None)
        if fut and not fut.done():
            fut.set_result(sid)
        self.read_cc_session_file(s)
        for data in self.early_hooks.pop(sid, []):
            self.apply_hook(s, data)
        self.dirty = True
        self.save_state()
        return s

    def remove(self, s: Session):
        log.info("session %s ended", s.id)
        self.sessions.pop(s.id, None)
        self.dirty = True
        self.save_state()

    async def inject(self, s: Session, data: bytes):
        if not s.writer:
            raise web.HTTPConflict(text="session is not connected")
        s.writer.write(b"I" + struct.pack(">I", len(data)) + data)
        await s.writer.drain()

    async def send_text(self, s: Session, text: str):
        text = text.replace("\r\n", "\n").rstrip("\n")
        if not text:
            return
        if "\n" in text or s.screen.bracketed_paste and len(text) > 1:
            await self.inject(s, b"\x1b[200~" + text.encode() + b"\x1b[201~")
        else:
            await self.inject(s, text.encode())
        await asyncio.sleep(0.25 + min(len(text) / 40000, 0.75))
        await self.inject(s, b"\r")
        s.last_remote_input = time.time()

    def key_bytes(self, s: Session, key: str) -> bytes:
        if key in ARROWS:
            return (b"\x1bO" if s.screen.app_cursor else b"\x1b[") + ARROWS[key].encode()
        if key in KEYS:
            return KEYS[key]
        if len(key) == 1:
            return key.encode()
        raise web.HTTPBadRequest(text=f"unknown key {key!r}")

    # ---- hooks ---------------------------------------------------------------
    async def handle_hook(self, request: web.Request):
        sid = request.headers.get("X-Claude-Mobile-Id", "")
        try:
            data = await request.json()
        except ValueError:
            return web.json_response({})
        s = self.sessions.get(sid)
        if s is None:
            if sid:
                self.early_hooks[sid].append(data)
                del self.early_hooks[sid][:-5]
            return web.json_response({})
        self.apply_hook(s, data)
        return web.json_response({})

    def apply_hook(self, s: Session, data: dict):
        event = data.get("hook_event_name")
        log.info("hook %s %s %s", s.id, event, data.get("notification_type") or "")
        self.attach_transcript(s, data.get("transcript_path"), data.get("session_id"))
        if data.get("cwd"):
            s.cwd = data["cwd"]
        changed = False
        if event == "SessionStart":
            changed = s.set_status("idle")
        elif event == "UserPromptSubmit":
            changed = s.set_status("working")
        elif event == "Stop":
            changed = s.set_status("idle")
            asyncio.get_running_loop().create_task(self.notify_done(s))
        elif event == "Notification":
            msg = data.get("message") or ""
            ntype = data.get("notification_type") or ""
            if ntype == "permission_prompt" or (not ntype and "permission" in msg.lower()):
                changed = s.set_status("permission", msg)
                asyncio.get_running_loop().create_task(self.notify_permission(s, msg))
            elif ntype == "elicitation_dialog":
                changed = s.set_status("question", msg)
                self.notify(s, "question", f"{s.title}", msg or "Claude has a question")
        elif event == "SessionEnd":
            changed = s.set_status("ended", data.get("reason", ""))
        if changed:
            self.dirty = True
            self.save_state()

    # ---- notifications -------------------------------------------------------
    def quiet(self, s: Session) -> bool:
        """You're at the laptop typing into this session: don't buzz the phone."""
        recent = time.time() - s.last_local_input < self.cfg["quiet_after_local_input"]
        return recent and s.last_local_input >= s.last_remote_input

    def notify(self, s: Session, kind: str, title: str, body: str, dedupe: str | None = None):
        if self.quiet(s):
            log.info("notify suppressed (local activity) %s %s", s.id, kind)
            return
        key = (s.id, kind, dedupe or body)
        if time.time() - self.notified.get(key, 0) < 20:
            return
        self.notified[key] = time.time()
        self.next_notify_id += 1
        n = {"t": "notify", "id": self.next_notify_id, "sid": s.id, "kind": kind,
             "title": title, "body": body, "ts": time.time()}
        self.notifications.append(n)
        log.info("notify %s %s: %s", s.id, kind, body[:80])
        for c in list(self.clients):
            asyncio.get_running_loop().create_task(c.send(n))

    async def notify_done(self, s: Session):
        await asyncio.sleep(0.6)  # let the final transcript lines land
        await self.poll_transcript(s)
        if s.status != "idle":
            return
        body = ""
        if s.transcript:
            for iid in reversed(s.transcript.order[-30:]):
                it = s.transcript.items[iid]
                if it["kind"] == "assistant":
                    body = plain(it["text"])
                    break
                if it["kind"] == "user":
                    break
        self.notify(s, "done", s.title, body or "Finished", dedupe=f"done:{s.transcript.seq if s.transcript else 0}")

    async def notify_permission(self, s: Session, msg: str):
        await asyncio.sleep(0.3)
        await self.poll_transcript(s)
        if s.status != "permission":
            return
        detail = ""
        if s.transcript:
            for iid in reversed(s.transcript.order[-10:]):
                it = s.transcript.items[iid]
                if it["kind"] == "tool" and it.get("status") == "running":
                    detail = f"{it['name']}: {it.get('detail') or it.get('summary')}"
                    break
        body = msg + (f"\n{detail[:300]}" if detail else "")
        self.notify(s, "permission", s.title, body, dedupe=f"perm:{detail or msg}")

    # ---- periodic work -------------------------------------------------------
    async def poll_transcript(self, s: Session):
        t = s.transcript
        if not t:
            return
        items, reset = t.poll()
        if not items and not reset:
            return
        s.updated = time.time()
        self.dirty = True
        if s.status in ("permission", "question") and not t.pending_question:
            if any(i["kind"] != "user" for i in items) and time.time() - s.status_since > 1.0:
                s.set_status("working")
        if t.pending_question and s.status != "question":
            iid = t.tool_items.get(t.pending_question)
            qs = t.items.get(iid, {}).get("questions") or []
            q = qs[0]["question"] if qs else "Claude has a question"
            s.set_status("question", q)
            opts = " · ".join(o["label"] for o in (qs[0]["options"] if qs else []))
            self.notify(s, "question", s.title, q + (f"\n{opts}" if opts else ""), dedupe=f"q:{t.pending_question}")
        await self.push_items(s, items=items, reset=reset)

    async def push_items(self, s: Session, items: list | None = None, reset: bool = False):
        if not s.transcript:
            return
        for c in list(self.clients):
            if c.sub == s.id:
                if reset:
                    await c.send({"t": "items", "sid": s.id, "reset": True, "items": s.transcript.snapshot()})
                elif items:
                    await c.send({"t": "items", "sid": s.id, "reset": False, "items": items})

    async def ticker(self):
        n = 0
        while True:
            await asyncio.sleep(0.4)
            n += 1
            for s in list(self.sessions.values()):
                try:
                    await self.poll_transcript(s)
                    if n % 3 == 0:
                        self.read_cc_session_file(s)
                    if n % 25 == 0 and s.writer is None and not pid_alive(s.wrapper_pid):
                        self.remove(s)
                except Exception:
                    log.exception("tick failed for %s", s.id)
            if n % 150 == 1:
                await asyncio.to_thread(self.github.local)  # repo of each session, for the chat header
            if n % 25 == 0 and self.clients:
                # A new build appeared (e.g. Claude just rebuilt the app): tell the phones.
                before = self.app_cache[1] if self.app_cache else None
                b = await asyncio.to_thread(self.app_build)
                if b and b[0] != before:
                    for c in list(self.clients):
                        await c.send({"t": "app", "app": b[0]})
            if n % 5 == 0:
                try:
                    self.dirty |= await self.awake.update(list(self.sessions.values()))
                except Exception:
                    log.exception("keep-awake update failed")
            if self.dirty:
                self.dirty = False
                await self.broadcast_sessions()
            for c in list(self.clients):
                if c.screen_sub:
                    s = self.sessions.get(c.screen_sub)
                    if s and s.screen.version != c.screen_version:
                        c.screen_version = s.screen.version
                        await c.send(self.screen_msg(s))

    def sessions_msg(self) -> dict:
        ss = sorted(self.sessions.values(), key=lambda s: s.started)
        sessions = []
        for s in ss:
            d = s.summary()
            d["repo"] = self.github.repo_for_cwd(s.cwd)
            sessions.append(d)
        return {"t": "sessions", "sessions": sessions,
                "awake": self.awake.active, "keep_awake": self.awake.mode}

    async def broadcast_sessions(self):
        msg = self.sessions_msg()
        for c in list(self.clients):
            await c.send(msg)

    def screen_msg(self, s: Session) -> dict:
        x, y = s.screen.cursor()
        return {"t": "screen", "sid": s.id, "lines": s.screen.lines(), "cursor": [x, y],
                "cols": s.screen.cols, "rows": s.screen.rows,
                "options": s.screen.dialog_options()}

    # ---- HTTP / WebSocket API ----------------------------------------------
    def check_auth(self, request: web.Request):
        tok = request.headers.get("Authorization", "").removeprefix("Bearer ").strip() or request.query.get("token", "")
        if not hmac.compare_digest(tok.encode(), self.cfg["token"].encode()):
            raise web.HTTPUnauthorized(text="bad token")

    def get_session(self, request: web.Request) -> Session:
        s = self.sessions.get(request.match_info["sid"])
        if not s:
            raise web.HTTPNotFound(text="no such session")
        return s

    @web.middleware
    async def auth_mw(self, request, handler):
        self.check_auth(request)
        return await handler(request)

    async def api_health(self, request):
        return web.json_response({"ok": True, "host": socket.gethostname(), "sessions": len(self.sessions)})

    async def api_sessions(self, request):
        return web.json_response(self.sessions_msg())

    async def api_items(self, request):
        s = self.get_session(request)
        items = s.transcript.snapshot(int(request.query.get("limit", 400))) if s.transcript else []
        return web.json_response({"sid": s.id, "items": items})

    async def api_send(self, request):
        s = self.get_session(request)
        body = await request.json()
        await self.send_text(s, str(body.get("text", "")))
        return web.json_response({"ok": True})

    async def api_keys(self, request):
        s = self.get_session(request)
        body = await request.json()
        for k in body.get("keys", []):
            await self.inject(s, self.key_bytes(s, str(k)))
            await asyncio.sleep(0.06)
        s.last_remote_input = time.time()
        return web.json_response({"ok": True})

    async def select_option(self, s: Session, index: int):
        opts = s.screen.dialog_options()
        if not opts:
            raise web.HTTPConflict(text="no menu visible in this session")
        if not 0 <= index < len(opts):
            raise web.HTTPBadRequest(text="no such option")
        opt = opts[index]
        if opt["key"]:
            await self.inject(s, opt["key"].encode())
        else:
            cur = next((o["index"] for o in opts if o["selected"]), 0)
            key = "down" if index > cur else "up"
            for _ in range(abs(index - cur)):
                await self.inject(s, self.key_bytes(s, key))
                await asyncio.sleep(0.06)
            await self.inject(s, b"\r")
        s.last_remote_input = time.time()
        return opt

    async def api_select(self, request):
        s = self.get_session(request)
        index = int((await request.json()).get("index", -1))
        opt = await self.select_option(s, index)
        return web.json_response({"ok": True, "picked": opt})

    async def api_answer(self, request):
        """Answer a permission dialog by meaning: choice = yes | always | no."""
        s = self.get_session(request)
        choice = (await request.json()).get("choice", "yes")
        opts = s.screen.dialog_options()
        if not opts:
            raise web.HTTPConflict(text="no dialog visible in this session")

        def kind(label):
            low = label.lower()
            if low.startswith("yes"):
                return "always" if ("don't ask" in low or "always" in low or "allow all" in low) else "yes"
            if low.startswith("no"):
                return "no"
            return None
        pick = next((o["index"] for o in opts if kind(o["label"]) == choice), None)
        if pick is None:
            raise web.HTTPConflict(text=f"option {choice!r} not offered")
        opt = await self.select_option(s, pick)
        return web.json_response({"ok": True, "picked": opt})

    async def api_screen(self, request):
        return web.json_response(self.screen_msg(self.get_session(request)))

    # ---- app updates -----------------------------------------------------------
    def app_build(self) -> tuple[dict, str] | None:
        """Newest built APK, from Gradle's output-metadata.json, with a sha256 to verify the download."""
        d = self.cfg.get("apk_dir") or APK_DIR
        meta = os.path.join(d, "output-metadata.json")
        try:
            mtime = os.stat(meta).st_mtime
        except OSError:
            return None
        if self.app_cache and self.app_cache[0] == mtime:
            return self.app_cache[1], self.app_cache[2]
        try:
            with open(meta) as f:
                el = json.load(f)["elements"][0]
            apk = os.path.join(d, el["outputFile"])
            h = hashlib.sha256()
            with open(apk, "rb") as f:
                for chunk in iter(lambda: f.read(1 << 20), b""):
                    h.update(chunk)
            info = {"versionCode": int(el["versionCode"]), "versionName": el.get("versionName", ""),
                    "size": os.path.getsize(apk), "sha256": h.hexdigest()}
        except (OSError, ValueError, KeyError, IndexError):
            return None
        self.app_cache = (mtime, info, apk)
        return info, apk

    async def api_app(self, request):
        b = await asyncio.to_thread(self.app_build)
        if not b:
            raise web.HTTPNotFound(text="no app build on the laptop")
        return web.json_response(b[0])

    async def api_app_apk(self, request):
        b = await asyncio.to_thread(self.app_build)
        if not b:
            raise web.HTTPNotFound(text="no app build on the laptop")
        return web.FileResponse(b[1], headers={"Content-Type": "application/vnd.android.package-archive"})

    # ---- starting sessions from the phone -----------------------------------
    def live_by_session_id(self) -> dict[str, str]:
        return {s.claude_session_id: s.id for s in self.sessions.values() if s.claude_session_id and s.writer}

    async def api_projects(self, request):
        live = self.live_by_session_id()
        running = await asyncio.to_thread(launcher.running_session_ids)
        projects = await asyncio.to_thread(self.history.projects, live, running)
        return web.json_response({"projects": projects})

    async def api_spawn(self, request):
        body = await request.json()
        resume = body.get("resume") or None
        prompt = str(body.get("prompt") or "").strip()[:20_000] or None
        cwd = os.path.realpath(os.path.expanduser(str(body.get("cwd") or "")))
        if resume:
            if not launcher.UUID_RE.match(str(resume)):
                raise web.HTTPBadRequest(text="bad session id")
            live = self.live_by_session_id().get(resume)
            if live:
                return web.json_response({"ok": True, "sid": live})
            info = await asyncio.to_thread(self.history.find, resume)
            if not info:
                raise web.HTTPNotFound(text="no such session")
            if resume in await asyncio.to_thread(launcher.running_session_ids):
                raise web.HTTPConflict(text="That session is open in a terminal the phone can't reach. Close it on the laptop first.")
            cwd = info["cwd"]
        if not os.path.isdir(cwd) or not cwd.startswith(os.path.expanduser("~")):
            raise web.HTTPBadRequest(text=f"not a folder in your home directory: {cwd}")
        token = secrets.token_hex(8)
        fut = asyncio.get_running_loop().create_future()
        self.spawns[token] = fut
        try:
            proc = await launcher.spawn(self.cfg, cwd, token, resume, prompt)
        except RuntimeError as e:
            self.spawns.pop(token, None)
            raise web.HTTPServiceUnavailable(text=str(e))
        try:
            sid = await asyncio.wait_for(asyncio.shield(fut), 15)
        except asyncio.TimeoutError:
            sid = None
            if proc.returncode:
                self.spawns.pop(token, None)
                raise web.HTTPServiceUnavailable(text="The terminal on the laptop didn't start")
            # Keep the waiter a little longer in case it's just slow; drop it later.
            asyncio.get_running_loop().call_later(120, self.spawns.pop, token, None)
        return web.json_response({"ok": True, "sid": sid})

    async def api_settings(self, request):
        if request.method == "POST":
            body = await request.json()
            mode = body.get("keep_awake")
            if mode is not None:
                if mode not in launcher.Awake.MODES:
                    raise web.HTTPBadRequest(text="keep_awake must be working, always or off")
                self.cfg["keep_awake"] = mode
                save_config_key("keep_awake", mode)
                await self.awake.update(list(self.sessions.values()))
                self.dirty = True
        return web.json_response({"keep_awake": self.awake.mode, "awake": self.awake.active})

    async def api_upload(self, request):
        """A photo from the phone: saved on the laptop, path returned for the message."""
        s = self.get_session(request)
        ext = {"image/jpeg": "jpg", "image/png": "png", "image/webp": "webp", "image/gif": "gif"}.get(
            request.content_type)
        if not ext:
            raise web.HTTPUnsupportedMediaType(text="send a JPEG, PNG, WebP or GIF")
        data = await request.read()
        if not data:
            raise web.HTTPBadRequest(text="empty upload")
        os.makedirs(UPLOADS, mode=0o700, exist_ok=True)
        cutoff = time.time() - 14 * 86400
        for old in glob.glob(os.path.join(UPLOADS, "*")):
            try:
                if os.path.getmtime(old) < cutoff:
                    os.unlink(old)
            except OSError:
                pass
        name = time.strftime("photo-%Y%m%d-%H%M%S-") + secrets.token_hex(3) + "." + ext
        path = os.path.join(UPLOADS, name)
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "wb") as f:
            f.write(data)
        log.info("upload for %s: %s (%d KB)", s.id, path, len(data) // 1024)
        return web.json_response({"ok": True, "path": path})

    async def api_ws(self, request):
        ws = web.WebSocketResponse(heartbeat=25)
        await ws.prepare(request)
        c = Client(ws)
        self.clients.add(c)
        log.info("client connected (%d)", len(self.clients))
        try:
            b = await asyncio.to_thread(self.app_build)
            await c.send({"t": "hello", "host": socket.gethostname(), "notify_id": self.next_notify_id,
                          "app": b[0] if b else None})
            await c.send(self.sessions_msg())
            async for msg in ws:
                if msg.type != WSMsgType.TEXT:
                    continue
                try:
                    d = json.loads(msg.data)
                except ValueError:
                    continue
                t = d.get("t")
                if t == "hello":
                    last = int(d.get("last_notify") or 0)
                    if last:
                        cutoff = time.time() - 1800
                        for n in list(self.notifications):
                            if n["id"] > last and n["ts"] > cutoff:
                                await c.send(n)
                elif t == "sub":
                    c.sub = d.get("sid")
                    s = self.sessions.get(c.sub or "")
                    if s:
                        await c.send({"t": "items", "sid": s.id, "reset": True,
                                      "items": s.transcript.snapshot() if s.transcript else []})
                elif t == "screen":
                    c.screen_sub = d.get("sid")
                    c.screen_version = -1
                elif t == "ping":
                    await c.send({"t": "pong"})
        finally:
            self.clients.discard(c)
            log.info("client disconnected (%d)", len(self.clients))
        return ws


async def main():
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    cfg = load_config()
    BIG_CONTEXT_MODELS.update(cfg.get("big_context_models") or [])
    os.makedirs(RUNTIME, mode=0o700, exist_ok=True)
    bridge = Bridge(cfg)

    wsock = os.path.join(RUNTIME, "wrappers.sock")
    hsock = os.path.join(RUNTIME, "hooks.sock")
    for p in (wsock, hsock):
        if os.path.exists(p):
            os.unlink(p)
    wrapper_server = await asyncio.start_unix_server(bridge.handle_wrapper, path=wsock)
    os.chmod(wsock, 0o600)

    hook_app = web.Application()
    hook_app.router.add_post("/hook", bridge.handle_hook)
    hook_runner = web.AppRunner(hook_app, access_log=None)
    await hook_runner.setup()
    await web.UnixSite(hook_runner, hsock).start()
    os.chmod(hsock, 0o600)

    app = web.Application(middlewares=[bridge.auth_mw, github.errors_mw], client_max_size=25 * 1024 * 1024)
    app.router.add_get("/api/health", bridge.api_health)
    app.router.add_get("/api/sessions", bridge.api_sessions)
    app.router.add_get("/api/sessions/{sid}/items", bridge.api_items)
    app.router.add_get("/api/sessions/{sid}/screen", bridge.api_screen)
    app.router.add_post("/api/sessions/{sid}/send", bridge.api_send)
    app.router.add_post("/api/sessions/{sid}/keys", bridge.api_keys)
    app.router.add_post("/api/sessions/{sid}/answer", bridge.api_answer)
    app.router.add_post("/api/sessions/{sid}/select", bridge.api_select)
    app.router.add_post("/api/sessions/{sid}/upload", bridge.api_upload)
    app.router.add_get("/api/ws", bridge.api_ws)
    app.router.add_get("/api/projects", bridge.api_projects)
    bridge.github.routes(app)
    app.router.add_get("/api/app", bridge.api_app)
    app.router.add_get("/api/app/apk", bridge.api_app_apk)
    app.router.add_post("/api/spawn", bridge.api_spawn)
    app.router.add_get("/api/settings", bridge.api_settings)
    app.router.add_post("/api/settings", bridge.api_settings)
    runner = web.AppRunner(app, access_log=None)
    await runner.setup()
    await web.TCPSite(runner, cfg["host"], cfg["port"]).start()
    log.info("listening on %s:%s, sockets in %s", cfg["host"], cfg["port"], RUNTIME)

    async with wrapper_server:
        await bridge.ticker()
