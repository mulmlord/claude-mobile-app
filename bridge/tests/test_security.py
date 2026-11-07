"""Security fixes: approvals tied to the prompt on screen, header-only tokens, and the
small hardening fixes the test suite and review turned up."""

import asyncio
import time

import pytest

from claude_bridge import server
from claude_bridge.github import checks_state
from claude_bridge.screen import Screen
from claude_bridge.transcript import Transcript
from conftest import TOKEN
from helpers import PERMISSION, PERMISSION_BOXED, Conv, screen_bytes, until

SID = "eeeeeeee-0000-4000-8000-000000000002"


@pytest.fixture
def conv(sandbox):
    d = sandbox.projects / "-proj"
    d.mkdir()
    c = Conv(d / f"{SID}.jsonl", cwd=str(sandbox.home / "proj"), session_id=SID)
    c.write(c.prompt("start"))
    return c


async def show(w, lines):
    await w.output(screen_bytes(lines))
    want = next(ln for ln in lines if ln.strip()).strip()[:10]
    await until(lambda: any(want in ln for ln in w.session.screen.lines()), what="screen to update")


def screen_of(lines) -> Screen:
    sc = Screen(80, 24)
    sc.feed(screen_bytes(lines))
    return sc


# ---- reading the prompt off the screen ------------------------------------------------------
def test_dialog_text_of_a_boxed_prompt():
    assert screen_of(PERMISSION_BOXED).dialog_text() == "Bash command\n\nrm -rf build\nClean the build folder\n\nDo you want to proceed?"


def test_dialog_text_keeps_the_diff_preview_and_stops_at_claudes_output():
    text = screen_of(PERMISSION).dialog_text()
    assert text.splitlines() == ["Edit file", "1 -old line", "1 +new line", "Do you want to make this edit to app.kt?"]
    assert "I'll clean" not in text


def test_dialog_text_without_a_menu_is_empty():
    assert screen_of(["just some output", "> "]).dialog_text() == ""


def test_screen_message_carries_the_prompt():
    class S:  # what screen_msg needs from a session
        id, screen = "w1", screen_of(PERMISSION_BOXED)
    msg = server.Bridge.screen_msg(None, S)
    assert msg["prompt"].startswith("Bash command") and len(msg["options"]) == 3


# ---- approvals are tied to the prompt the phone showed ----------------------------------------
async def test_answer_with_the_prompts_fingerprint(api, wrappers):
    w = await wrappers("w1")
    await show(w, PERMISSION_BOXED)
    dh = server.dialog_hash(w.session.screen.dialog_text())
    r = await api.post("/api/sessions/w1/answer", json={"choice": "yes", "dh": dh})
    assert r.status == 200 and await w.inputs() == [b"1"]


async def test_answer_refused_when_another_prompt_is_up(api, wrappers):
    w = await wrappers("w1")
    await show(w, PERMISSION_BOXED)
    old = server.dialog_hash(w.session.screen.dialog_text())
    await show(w, PERMISSION)  # the laptop now asks about something else
    r = await api.post("/api/sessions/w1/answer", json={"choice": "yes", "dh": old})
    assert r.status == 409 and "changed" in await r.text()
    assert await w.inputs() == []


async def test_parallel_calls_need_the_fingerprint(api, bridge, hooks, wrappers, conv):
    w = await wrappers("w1")
    await hooks.fire("w1", hook_event_name="UserPromptSubmit", session_id=SID, transcript_path=conv.path)
    a = conv.tool_use("Bash", {"command": "make deploy", "description": "Deploy"})
    b = conv.tool_use("Bash", {"command": "ls", "description": "List"})
    conv.write(conv.assistant(a, b))
    await bridge.poll_transcript(w.session)
    await show(w, PERMISSION_BOXED)
    # an older app (no fingerprint) can't approve while two calls wait
    r = await api.post("/api/sessions/w1/answer", json={"choice": "yes"})
    assert r.status == 409 and "Several tool calls" in await r.text()
    assert await w.inputs() == []
    # with the fingerprint of what's on screen it works
    dh = server.dialog_hash(w.session.screen.dialog_text())
    r = await api.post("/api/sessions/w1/answer", json={"choice": "no", "dh": dh})
    assert r.status == 200 and await w.inputs() == [b"3"]


async def test_parallel_calls_notify_with_the_screen_not_a_guess(bridge, hooks, wrappers, conv):
    w = await wrappers("w1")
    await hooks.fire("w1", hook_event_name="UserPromptSubmit", session_id=SID, transcript_path=conv.path)
    a = conv.tool_use("Bash", {"command": "rm -rf build", "description": "Clean the build folder"})
    b = conv.tool_use("Bash", {"command": "ls", "description": "List"})
    conv.write(conv.assistant(a, b))
    await show(w, PERMISSION_BOXED)
    await hooks.fire("w1", hook_event_name="Notification", notification_type="permission_prompt",
                     message="Claude needs your permission to use Bash")
    await until(lambda: [n["kind"] for n in bridge.notifications] == ["permission"], what="permission notification")
    (n,) = bridge.notifications
    assert "tool" not in n  # the newest pending call ("ls") isn't necessarily the one asked about
    assert n["waiting"] == 2
    assert n["dialog"].startswith("Bash command") and "rm -rf build" in n["dialog"]
    assert n["dh"] == server.dialog_hash(w.session.screen.dialog_text())
    assert "rm -rf build" in n["body"]


async def test_single_call_keeps_its_preview_and_gets_a_fingerprint(bridge, hooks, wrappers, conv):
    w = await wrappers("w1")
    await hooks.fire("w1", hook_event_name="UserPromptSubmit", session_id=SID, transcript_path=conv.path)
    conv.write(conv.assistant(conv.tool_use("Bash", {"command": "rm -rf build", "description": "Clean the build folder"})))
    await show(w, PERMISSION_BOXED)
    await hooks.fire("w1", hook_event_name="Notification", notification_type="permission_prompt",
                     message="Claude needs your permission to use Bash")
    await until(lambda: len(bridge.notifications) == 1, what="permission notification")
    n = bridge.notifications[0]
    assert n["tool"]["detail"] == "rm -rf build" and "waiting" not in n and n["dh"]


# ---- token only in the header ------------------------------------------------------------
async def test_token_in_the_query_is_refused(aiohttp_client, bridge):
    client = await aiohttp_client(server.make_app(bridge))
    r = await client.get("/api/health", params={"token": TOKEN})
    assert r.status == 401


# ---- small hardening fixes ------------------------------------------------------------------
async def test_lone_carriage_return_becomes_a_newline(api, wrappers):
    w = await wrappers("w1")
    r = await api.post("/api/sessions/w1/send", json={"text": "line one\rline two"})
    assert r.status == 200
    sent = b"".join(await w.inputs())
    assert b"line one\nline two" in sent and sent.endswith(b"\r") and sent.count(b"\r") == 1


async def test_items_limit_must_be_a_number(api, wrappers):
    await wrappers("w1")
    r = await api.get("/api/sessions/w1/items?limit=abc")
    assert r.status == 400


def test_transcript_skips_lines_that_arent_objects(tmp_path):
    p = tmp_path / "t.jsonl"
    c = Conv(p)
    c.write(c.prompt("hello"))
    with open(p, "a") as f:
        f.write("[1, 2]\n\"text\"\n42\n")
    c.write(c.prompt("again"))
    t = Transcript(str(p))
    items, _ = t.poll()
    assert [i["text"] for i in items if i["kind"] == "user"] == ["hello", "again"]


def test_cancelled_checks_dont_count_as_passing():
    assert checks_state([{"__typename": "CheckRun", "status": "COMPLETED", "conclusion": "CANCELLED"}]) == "FAILURE"


# ---- deny and tell Claude what to do instead --------------------------------------------------
async def test_deny_with_a_message_types_it_once_the_prompt_closes(api, wrappers):
    w = await wrappers("w1")
    await show(w, PERMISSION_BOXED)
    call = asyncio.create_task(api.post("/api/sessions/w1/answer", json={"choice": "no", "message": "use make clean"}))
    assert await w.inputs(quiet=0.3) == [b"3"]  # nothing typed while the prompt is up
    await show(w, ["Interrupted by user", "> "])
    r = await call
    assert r.status == 200 and (await r.json())["sent"] is True
    assert await w.inputs(quiet=0.5) == [b"use make clean", b"\r"]


async def test_deny_with_a_message_types_nothing_if_the_prompt_stays(api, wrappers, monkeypatch):
    monkeypatch.setattr(server, "MESSAGE_WAIT", 0.3)
    w = await wrappers("w1")
    await show(w, PERMISSION_BOXED)
    r = await api.post("/api/sessions/w1/answer", json={"choice": "no", "message": "use make clean"})
    assert r.status == 409 and "wasn't sent" in await r.text()
    assert await w.inputs() == [b"3"]


async def test_a_message_only_goes_with_no(api, wrappers):
    w = await wrappers("w1")
    await show(w, PERMISSION_BOXED)
    r = await api.post("/api/sessions/w1/answer", json={"choice": "yes", "message": "and also"})
    assert r.status == 400
    assert await w.inputs() == []


def test_ws_heartbeat_tolerates_a_sleeping_phone():
    assert server.WS_HEARTBEAT >= 90  # the phone gets WS_HEARTBEAT / 2 to answer a ping


# ---- repainting the session's screen ---------------------------------------------------------
import fcntl
import os
import struct
import termios


def winsize(fd):
    return struct.unpack("HHHH", fcntl.ioctl(fd, termios.TIOCGWINSZ, b"\0" * 8))[:2]


@pytest.fixture
def pty(monkeypatch):
    master, slave = os.openpty()
    fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack("HHHH", 40, 120, 0, 0))
    monkeypatch.setattr(server.Bridge, "session_tty", lambda self, s: os.ttyname(slave))
    yield slave
    os.close(master)
    os.close(slave)


async def test_redraw_shrinks_the_terminal_a_row_and_restores_it(api, wrappers, pty, fast):
    during = []
    fast.on_sleep.append(lambda d: during.append(winsize(pty)) if d == server.REDRAW_PAUSE else None)
    w = await wrappers("w1")
    r = await api.post("/api/sessions/w1/redraw")
    assert r.status == 200 and (await r.json())["ok"] is True
    assert during == [(39, 120)] and winsize(pty) == (40, 120)


async def test_redraw_keeps_a_resize_that_happened_meanwhile(api, wrappers, pty, fast):
    def laptop_resizes(d):
        if d == server.REDRAW_PAUSE:
            fcntl.ioctl(pty, termios.TIOCSWINSZ, struct.pack("HHHH", 50, 100, 0, 0))
    fast.on_sleep.append(laptop_resizes)
    w = await wrappers("w1")
    await api.post("/api/sessions/w1/redraw")
    assert winsize(pty) == (50, 100)


async def test_redraw_without_a_terminal_says_so(api, wrappers, monkeypatch):
    monkeypatch.setattr(server.Bridge, "session_tty", lambda self, s: "/nonexistent/tty")
    w = await wrappers("w1")
    r = await api.post("/api/sessions/w1/redraw")
    assert r.status == 200 and (await r.json())["ok"] is False


async def test_a_wrapper_reconnecting_later_gets_a_repaint(bridge, wrappers, pty, monkeypatch):
    calls = []
    async def fake(self, s, delay=0.0):
        calls.append(s.id)
    monkeypatch.setattr(server.Bridge, "redraw", fake)
    w = await wrappers("w1")  # just started: everything it draws reaches the bridge
    await asyncio.sleep(0.05)
    assert calls == []
    w2 = await wrappers("w2", started=time.time() - 60)  # running for a minute already
    await until(lambda: calls == ["w2"], what="a repaint")
