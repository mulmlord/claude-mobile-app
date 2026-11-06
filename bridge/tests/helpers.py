"""Builders for synthetic transcripts and a fake claude-mobile-wrap, shared by the tests."""

from __future__ import annotations

import asyncio
import json
import os
import struct
import time


# ---- transcripts ------------------------------------------------------------------
class Conv:
    """Writes a transcript the way Claude Code does: one JSON object per line, each
    conversation entry pointing at the previous one through parentUuid."""

    def __init__(self, path, cwd: str = "/home/u/proj", session_id: str = "11111111-2222-4333-8444-555555555555"):
        self.path = str(path)
        self.cwd = cwd
        self.session_id = session_id
        self.last: str | None = None
        self.n = 0
        self.tool_n = 0

    # entries -----------------------------------------------------------------
    def entry(self, type_: str, parent="last", sidechain: bool = False, **fields) -> dict:
        self.n += 1
        uuid = f"00000000-0000-4000-8000-{self.n:012d}"
        e = {
            "parentUuid": self.last if parent == "last" else parent,
            "isSidechain": sidechain, "type": type_, "uuid": uuid,
            "timestamp": f"2026-11-06T10:{self.n // 60:02d}:{self.n % 60:02d}.000Z",
            "userType": "external", "entrypoint": "cli", "cwd": self.cwd,
            "sessionId": self.session_id, "version": "2.1.0", "gitBranch": "main",
        }
        e.update(fields)
        if not sidechain:
            self.last = uuid
        return e

    def prompt(self, text, parent="last", **kw) -> dict:
        """What you type at the prompt."""
        return self.entry("user", parent=parent, message={"role": "user", "content": text},
                          promptSource="typed", origin={"kind": "human"}, **kw)

    def user(self, content, **kw) -> dict:
        """A user entry Claude Code writes itself (tool results, command output...)."""
        return self.entry("user", message={"role": "user", "content": content}, **kw)

    def assistant(self, *blocks, model: str = "claude-opus-5-5", usage: dict | None = None, **kw) -> dict:
        msg = {"model": model, "id": f"msg_{self.n}", "type": "message", "role": "assistant",
               "content": list(blocks), "stop_reason": "end_turn",
               "usage": usage if usage is not None else {"input_tokens": 3, "cache_creation_input_tokens": 0,
                                                        "cache_read_input_tokens": 0, "output_tokens": 5}}
        return self.entry("assistant", message=msg, **kw)

    def system(self, subtype: str, **kw) -> dict:
        return self.entry("system", subtype=subtype, **kw)

    def tool_use(self, name: str, inp: dict, tid: str | None = None) -> dict:
        self.tool_n += 1
        return {"type": "tool_use", "id": tid or f"toolu_{self.tool_n:04d}", "name": name, "input": inp}

    @staticmethod
    def text(t: str) -> dict:
        return {"type": "text", "text": t}

    @staticmethod
    def result(tid: str, content="ok", is_error: bool = False) -> dict:
        return {"type": "tool_result", "tool_use_id": tid, "content": content, "is_error": is_error}

    @staticmethod
    def meta(type_: str, **fields) -> dict:
        """Entries without a uuid (titles, snapshots...)."""
        return {"type": type_, **fields}

    # file ------------------------------------------------------------------------
    def write(self, *entries, partial: str = ""):
        with open(self.path, "a") as f:
            for e in entries:
                f.write(json.dumps(e) + "\n")
            f.write(partial)


def write_jsonl(path, *entries):
    with open(path, "w") as f:
        for e in entries:
            f.write(json.dumps(e) + "\n")


# ---- polling ------------------------------------------------------------------------
async def until(cond, timeout: float = 2.0, what: str = "condition"):
    """Wait for `cond()` to hold (the bridge handles socket frames and hook tasks
    asynchronously)."""
    deadline = time.monotonic() + timeout
    while not cond():
        if time.monotonic() > deadline:
            raise AssertionError(f"timed out waiting for {what}")
        await asyncio.sleep(0.005)


# ---- the wrapper side of the unix-socket protocol ----------------------------------------
class FakeWrapper:
    """claude-mobile-wrap as the bridge sees it: frames of 1 type byte + 4 byte big-endian
    length + payload. wrapper -> bridge: H hello, O output, R resize, A activity, X exit;
    bridge -> wrapper: I input."""

    def __init__(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter):
        self.reader, self.writer = reader, writer
        self.session = None

    @classmethod
    async def connect(cls, path: str) -> "FakeWrapper":
        r, w = await asyncio.open_unix_connection(path)
        return cls(r, w)

    async def frame(self, kind: bytes, payload: bytes = b""):
        self.writer.write(kind + struct.pack(">I", len(payload)) + payload)
        await self.writer.drain()

    async def hello(self, **info):
        await self.frame(b"H", json.dumps(info).encode())

    async def output(self, data: bytes | str):
        await self.frame(b"O", data.encode() if isinstance(data, str) else data)

    async def resize(self, rows: int, cols: int):
        await self.frame(b"R", json.dumps({"rows": rows, "cols": cols}).encode())

    async def activity(self):
        await self.frame(b"A")

    async def exit(self, status: int = 0):
        await self.frame(b"X", json.dumps({"status": status}).encode())

    async def inputs(self, quiet: float = 0.03) -> list[bytes]:
        """Every I frame the bridge has sent, until the line goes quiet."""
        out = []
        while True:
            try:
                hdr = await asyncio.wait_for(self.reader.readexactly(5), quiet)
            except asyncio.TimeoutError:
                return out
            assert hdr[:1] == b"I", hdr
            (n,) = struct.unpack(">I", hdr[1:])
            out.append(await self.reader.readexactly(n))

    async def close(self):
        if not self.writer.is_closing():
            self.writer.close()
        try:
            await self.writer.wait_closed()
        except (ConnectionError, OSError):
            pass


def screen_bytes(lines: list[str], styled: bool = False) -> bytes:
    """A full redraw of the terminal: clear, home, then the lines. `styled` adds the
    colour/bold escape sequences Claude Code's renderer sprinkles around."""
    out = []
    for ln in lines:
        if styled:
            ln = ln.replace("❯", "\x1b[38;5;153m❯\x1b[39m").replace("Yes", "\x1b[1mYes\x1b[22m")
        out.append("\x1b[2K" + ln)
    return ("\x1b[2J\x1b[H" + "\r\n".join(out)).encode()


def touch_old(path: str, age: float):
    t = time.time() - age
    os.utime(path, (t, t))


# ---- Claude Code menus as they appear on the terminal ---------------------------------
PERMISSION_BOXED = [
    "╭──────────────────────────────────────────────────────────╮",
    "│ Bash command                                             │",
    "│                                                          │",
    "│   rm -rf build                                           │",
    "│   Clean the build folder                                 │",
    "│                                                          │",
    "│ Do you want to proceed?                                  │",
    "│ ❯ 1. Yes                                                 │",
    "│   2. Yes, and don't ask again for rm commands in ~/proj  │",
    "│   3. No, and tell Claude what to do differently (esc)    │",
    "╰──────────────────────────────────────────────────────────╯",
]

PERMISSION = [
    "● I'll clean the build first.",
    "",
    " Edit file",
    " ╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌",
    "  1 -old line",
    "  1 +new line",
    " ╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌",
    " Do you want to make this edit to app.kt?",
    " ❯ 1. Yes",
    "   2. Yes, allow all edits during this session (shift+tab)",
    "   3. No, and tell Claude what to do differently (esc)",
]

QUESTION = [
    "← ☐ Database  ✔ Submit →",
    "",
    "Which database should we use?",
    "",
    "❯ 1. PostgreSQL",
    "     Relational and mature, the safe choice for most",
    "     production workloads",
    "  2. SQLite",
    "     Embedded, zero setup",
    "  3. Type something.",
    "",
    "Enter to select · Tab/Arrow keys to navigate · Esc to cancel",
]

TRUST = [
    " Do you trust the files in this folder?",
    "",
    " /home/u/proj",
    "",
    " Claude Code may read, write, or execute files contained in this directory.",
    "",
    " ❯ 1. Yes, proceed",
    "   2. No, exit",
    "",
    " Enter to confirm · Esc to exit",
]

PROMPT_BOX = [
    "● Done. The tests pass.",
    "",
    "────────────────────────────────────────────",
    "❯ also update the README",
    "────────────────────────────────────────────",
    "  ⏵⏵ accept edits on (shift+tab to cycle)",
]
