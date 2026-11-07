"""Wrapped sessions end to end: a fake claude-mobile-wrap speaking the real unix-socket
protocol, Claude Code hooks driving the status machine and notifications, and what the
phone types ending up as input bytes for the terminal."""

import asyncio
import json
import os
import signal
import subprocess
import sys
import time

import pytest

from claude_bridge import server
from helpers import PERMISSION, PERMISSION_BOXED, PROMPT_BOX, QUESTION, TRUST, Conv, FakeWrapper, screen_bytes, until

SID = "dddddddd-0000-4000-8000-000000000001"
NO_SUCH_PID = 2**22 + 1


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


def kinds(bridge):
    return [n["kind"] for n in bridge.notifications]


# ---- the wrapper protocol ----------------------------------------------------------------
async def test_wrapper_registers_and_is_listed(api, bridge, wrappers, sandbox):
    await wrappers("w1")
    (s,) = (await (await api.get("/api/sessions")).json())["sessions"]
    assert s["id"] == "w1" and s["project"] == "proj" and s["title"] == "proj" and s["cwd"] == str(sandbox.home / "proj")
    assert s["status"] == "starting" and s["connected"] is True and s["session_id"] is None
    assert (s["context"], s["context_max"], s["model"], s["questions"], s["repo"]) == (0, 200_000, None, None, None)
    state = json.loads((sandbox.run / "state.json").read_text())
    assert state["w1"]["status"] == "starting"


async def test_output_feeds_the_virtual_screen(api, wrappers):
    w = await wrappers("w1")
    await show(w, TRUST)
    d = await (await api.get("/api/sessions/w1/screen")).json()
    assert d["t"] == "screen" and d["sid"] == "w1" and (d["cols"], d["rows"]) == (80, 24)
    assert d["lines"][0] == " Do you trust the files in this folder?"
    assert [o["label"] for o in d["options"]] == ["Yes, proceed", "No, exit"]
    assert d["cursor"] == [len(TRUST[-1]), len(TRUST) - 1]


async def test_resize_and_activity_frames(wrappers):
    w = await wrappers("w1")
    await w.resize(30, 100)
    await until(lambda: (w.session.screen.rows, w.session.screen.cols) == (30, 100), what="resize")
    assert w.session.last_local_input == 0
    await w.activity()
    await until(lambda: w.session.last_local_input > 0, what="activity")


async def test_frames_before_hello_are_ignored(bridge, wrappers):
    w = await FakeWrapper.connect(wrappers.path)
    await w.output(b"before hello")
    await w.hello(id="w2", pid=NO_SUCH_PID, wrapper_pid=os.getpid(), cwd="/x", rows=24, cols=80)
    await until(lambda: "w2" in bridge.sessions)
    assert bridge.sessions["w2"].screen.lines() == []
    await w.close()


async def test_exit_removes_the_session(bridge, wrappers, sandbox):
    w = await wrappers("w1")
    await w.exit(0)
    await w.close()
    await until(lambda: "w1" not in bridge.sessions, what="removal")
    assert json.loads((sandbox.run / "state.json").read_text()) == {}


async def test_lost_connection_keeps_a_live_session_until_it_reconnects(api, bridge, wrappers):
    w = await wrappers("w1")
    await show(w, ["old output"])
    await w.close()
    s = bridge.sessions["w1"]
    await until(lambda: s.writer is None, what="disconnect")
    (d,) = (await (await api.get("/api/sessions")).json())["sessions"]
    assert d["connected"] is False
    r = await api.post("/api/sessions/w1/send", json={"text": "hi"})
    assert r.status == 409 and await r.text() == "session is not connected"

    w2 = await wrappers("w1", rows=40, cols=120)  # the wrapper reconnects (bridge restart, hiccup)
    assert w2.session is s and s.screen.lines() == [] and (s.screen.rows, s.screen.cols) == (40, 120)


async def test_lost_connection_of_a_dead_wrapper_removes_it(bridge, wrappers):
    w = await wrappers("w1", wrapper_pid=NO_SUCH_PID)
    await w.close()
    await until(lambda: "w1" not in bridge.sessions, what="removal")


async def test_garbage_frame_drops_the_connection(bridge, wrappers):
    w = await wrappers("w1")
    await w.frame(b"R", b"{not json")
    await until(lambda: w.session.writer is None, what="disconnect")
    assert "w1" in bridge.sessions


# ---- typing from the phone ---------------------------------------------------------------------
async def test_send_types_the_text_then_enter(api, bridge, wrappers, fast):
    w = await wrappers("w1")
    r = await api.post("/api/sessions/w1/send", json={"text": "hello ✓"})
    assert await r.json() == {"ok": True}
    assert await w.inputs() == ["hello ✓".encode(), b"\r"]
    assert fast.slept and fast.slept[-1] >= 0.25  # paced like a paste, then Enter
    assert w.session.last_remote_input > 0


async def test_multiline_text_is_a_bracketed_paste(api, wrappers):
    w = await wrappers("w1")
    await api.post("/api/sessions/w1/send", json={"text": "line 1\r\nline 2\n\n"})
    assert await w.inputs() == [b"\x1b[200~line 1\nline 2\x1b[201~", b"\r"]


async def test_bracketed_paste_mode_wraps_anything_longer_than_a_key(api, wrappers):
    w = await wrappers("w1")
    await w.output(b"\x1b[?2004h")
    await until(lambda: w.session.screen.bracketed_paste, what="paste mode")
    await api.post("/api/sessions/w1/send", json={"text": "/compact"})
    assert await w.inputs() == [b"\x1b[200~/compact\x1b[201~", b"\r"]
    await api.post("/api/sessions/w1/send", json={"text": "y"})
    assert await w.inputs() == [b"y", b"\r"]


async def test_nothing_to_send(api, wrappers):
    w = await wrappers("w1")
    for text in ("", "\n\n", None):
        r = await api.post("/api/sessions/w1/send", json={"text": text} if text is not None else {})
        assert r.status == 200
    assert await w.inputs() == []


async def test_keys(api, wrappers):
    w = await wrappers("w1")
    r = await api.post("/api/sessions/w1/keys", json={"keys": ["esc", "enter", "up", "shift-tab", "ctrl-c", "x", "tab"]})
    assert r.status == 200
    assert await w.inputs() == [b"\x1b", b"\r", b"\x1b[A", b"\x1b[Z", b"\x03", b"x", b"\t"]
    await w.output(b"\x1b[?1h")  # application cursor keys
    await until(lambda: w.session.screen.app_cursor, what="cursor mode")
    await api.post("/api/sessions/w1/keys", json={"keys": ["down", "left"]})
    assert await w.inputs() == [b"\x1bOB", b"\x1bOD"]


async def test_unknown_key(api, wrappers):
    w = await wrappers("w1")
    r = await api.post("/api/sessions/w1/keys", json={"keys": ["esc", "f13", "enter"]})
    assert r.status == 400 and await r.text() == "unknown key 'f13'"
    assert await w.inputs() == [b"\x1b"]  # keys before the bad one were already typed


# ---- menus --------------------------------------------------------------------------------------
async def test_select_presses_the_options_number(api, wrappers):
    w = await wrappers("w1")
    await show(w, PERMISSION)
    d = await (await api.post("/api/sessions/w1/select", json={"index": 2})).json()
    assert d["picked"]["label"] == "No, and tell Claude what to do differently (esc)"
    assert await w.inputs() == [b"3"]


async def test_select_without_numbers_uses_arrows(api, wrappers):
    w = await wrappers("w1")
    await show(w, ["Select model", "", "  Default", "› Opus", "  Sonnet"])
    await api.post("/api/sessions/w1/select", json={"index": 0})
    assert await w.inputs() == [b"\x1b[A", b"\r"]
    await api.post("/api/sessions/w1/select", json={"index": 2})
    assert await w.inputs() == [b"\x1b[B", b"\r"]
    await api.post("/api/sessions/w1/select", json={"index": 1})
    assert await w.inputs() == [b"\r"]


async def test_select_errors(api, wrappers):
    w = await wrappers("w1")
    r = await api.post("/api/sessions/w1/select", json={"index": 0})
    assert r.status == 409  # no menu
    await show(w, TRUST)
    for body in ({"index": 2}, {"index": -1}, {}):
        r = await api.post("/api/sessions/w1/select", json=body)
        assert r.status == 400 and await r.text() == "no such option"
    assert await w.inputs() == []


@pytest.mark.parametrize("lines", [PERMISSION, PERMISSION_BOXED])
async def test_answer_by_meaning(api, wrappers, lines):
    w = await wrappers("w1")
    await show(w, lines)
    for choice, key in (("yes", b"1"), ("always", b"2"), ("no", b"3")):
        r = await api.post("/api/sessions/w1/answer", json={"choice": choice})
        assert r.status == 200
        assert await w.inputs() == [key]
    r = await api.post("/api/sessions/w1/answer", json={})
    assert (await r.json())["picked"]["key"] == "1"
    await w.inputs()


async def test_answer_refuses_what_isnt_offered(api, wrappers):
    w = await wrappers("w1")
    r = await api.post("/api/sessions/w1/answer", json={"choice": "yes"})
    assert r.status == 409 and await r.text() == "no dialog visible in this session"
    await show(w, TRUST)
    r = await api.post("/api/sessions/w1/answer", json={"choice": "always"})
    assert r.status == 409 and await r.text() == "option 'always' not offered"
    assert await w.inputs() == []


def ask(conv, *questions):
    tool = conv.tool_use("AskUserQuestion", {"questions": list(questions)})
    conv.write(conv.assistant(tool))
    return tool["id"]


def question(text="Which database should we use?", labels=("PostgreSQL", "SQLite"), multi=False):
    return {"question": text, "header": "DB", "multiSelect": multi,
            "options": [{"label": lab, "description": ""} for lab in labels]}


async def test_choose_by_label(api, bridge, wrappers, conv):
    w = await wrappers("w1")
    qid = ask(conv, question())
    bridge.attach_transcript(w.session, conv.path, SID)
    await show(w, QUESTION)
    r = await api.post("/api/sessions/w1/choose", json={"label": "sqlite", "qid": qid})
    assert (await r.json())["picked"]["label"] == "SQLite"
    assert await w.inputs() == [b"2"]
    r = await api.post("/api/sessions/w1/choose", json={"label": "PostgreSQL"})  # no qid: no check
    assert r.status == 200 and await w.inputs() == [b"1"]


async def test_choose_refusals(api, bridge, wrappers, conv):
    w = await wrappers("w1")
    qid = ask(conv, question())
    r = await api.post("/api/sessions/w1/choose", json={"label": "SQLite", "qid": qid})
    assert r.status == 409 and await r.text() == "That question was already answered"  # no transcript yet
    bridge.attach_transcript(w.session, conv.path, SID)
    r = await api.post("/api/sessions/w1/choose", json={"label": "SQLite", "qid": qid})
    assert r.status == 409 and await r.text() == "No question is showing in this session"
    await show(w, QUESTION)
    r = await api.post("/api/sessions/w1/choose", json={"label": "   "})
    assert r.status == 400 and await r.text() == "label missing"
    r = await api.post("/api/sessions/w1/choose", json={"label": "MySQL", "qid": qid})
    assert r.status == 409 and "isn't an option on screen" in await r.text()
    r = await api.post("/api/sessions/w1/choose", json={"label": "SQLite", "qid": "toolu_old"})
    assert r.status == 409 and await r.text() == "That question was already answered"
    conv.write(conv.user([conv.result(qid, "answered at the laptop")]))
    await bridge.poll_transcript(w.session)
    r = await api.post("/api/sessions/w1/choose", json={"label": "SQLite", "qid": qid})
    assert r.status == 409
    assert await w.inputs() == []


# ---- ending and renaming ------------------------------------------------------------------------
async def test_end_an_idle_session(api, wrappers):
    w = await wrappers("w1")
    w.session.status = "idle"
    assert await (await api.post("/api/sessions/w1/end", json={})).json() == {"ok": True}
    assert await w.inputs() == [b"\x15", b"/exit", b"\r"]


async def test_end_a_working_session_interrupts_first(api, wrappers):
    w = await wrappers("w1")
    w.session.status = "working"
    await api.post("/api/sessions/w1/end")  # no body at all is fine
    assert await w.inputs() == [b"\x1b", b"\x15", b"/exit", b"\r"]


async def test_end_dismisses_an_open_dialog(api, wrappers, fast):
    w = await wrappers("w1")
    w.session.status = "permission"
    await show(w, PERMISSION)
    # the terminal reacts to Esc while the bridge waits a second
    fast.on_sleep.append(lambda delay: delay == 1.0 and w.session.screen.feed(screen_bytes(PROMPT_BOX)))
    assert (await api.post("/api/sessions/w1/end", json={})).status == 200
    assert await w.inputs() == [b"\x1b", b"\x15", b"/exit", b"\r"]


async def test_end_refuses_when_the_menu_stays(api, wrappers):
    w = await wrappers("w1")
    await show(w, PERMISSION)
    r = await api.post("/api/sessions/w1/end", json={})
    assert r.status == 409 and "A menu is still open" in await r.text()
    assert await w.inputs() == [b"\x1b"]


async def test_end_a_disconnected_session(api, bridge, wrappers):
    w = await wrappers("w1")
    await w.close()
    await until(lambda: bridge.sessions["w1"].writer is None)
    r = await api.post("/api/sessions/w1/end", json={"force": "yes"})  # only true means force
    assert r.status == 409 and "isn't connected" in await r.text()


@pytest.fixture
def child():
    procs = []

    def start(ignore_term=False):
        code = ("import signal, sys, time\n"
                + ("signal.signal(signal.SIGTERM, signal.SIG_IGN)\n" if ignore_term else "")
                + "print('ready', flush=True)\ntime.sleep(30)\n")
        p = subprocess.Popen([sys.executable, "-I", "-c", code], stdout=subprocess.PIPE)
        assert p.stdout.readline() == b"ready\n"
        procs.append(p)
        return p
    yield start
    for p in procs:
        if p.poll() is None:
            p.kill()
            p.wait()


async def test_force_quit_sends_sigterm(api, bridge, wrappers, child, monkeypatch):
    later = []

    async def kill_later(pid, wrapper_pid):
        later.append((pid, wrapper_pid))
    monkeypatch.setattr(bridge, "kill_later", kill_later)
    p = child()
    w = await wrappers("w1", pid=p.pid, wrapper_pid=os.getpid())  # the claude is our child
    assert (await api.post("/api/sessions/w1/end", json={"force": True})).status == 200
    assert p.wait(timeout=5) == -signal.SIGTERM
    await until(lambda: later == [(p.pid, os.getpid())], what="SIGKILL timer")
    assert await w.inputs() == []


async def test_force_quit_checks_the_pid_is_still_the_wrappers_child(api, wrappers, child):
    p = child()
    await wrappers("w1", pid=p.pid, wrapper_pid=NO_SUCH_PID)
    r = await api.post("/api/sessions/w1/end", json={"force": True})
    assert r.status == 409 and await r.text() == "The session's claude process isn't running any more."
    assert p.poll() is None
    await wrappers("w2", pid=None)
    assert (await api.post("/api/sessions/w2/end", json={"force": True})).status == 409


async def test_kill_later_sigkills_a_wedged_claude(bridge, child):
    stubborn = child(ignore_term=True)
    os.kill(stubborn.pid, signal.SIGTERM)
    await bridge.kill_later(stubborn.pid, NO_SUCH_PID)  # not the wrapper's child: left alone
    assert stubborn.poll() is None
    await bridge.kill_later(stubborn.pid, os.getpid())
    assert stubborn.wait(timeout=5) == -signal.SIGKILL


async def test_rename(api, bridge, wrappers, conv):
    w = await wrappers("w1")
    bridge.attach_transcript(w.session, conv.path, SID)
    d = await (await api.post("/api/sessions/w1/rename", json={"name": "  my\n new\x1b  name "})).json()
    assert d == {"ok": True, "name": "my new name"}
    assert await w.inputs() == [b"/rename my new name", b"\r"]
    # Claude Code then writes the title into the transcript
    conv.write(conv.meta("custom-title", customTitle="my new name", sessionId=SID))
    bridge.dirty = False
    await bridge.poll_transcript(w.session)
    assert bridge.dirty and w.session.title == "my new name"


@pytest.mark.parametrize("name,status,msg", [
    ("", 400, "Give the session a name."),
    ("\x1b\x07 \n", 400, "Give the session a name."),
    ("n" * 81, 400, "Keep the name to 80 characters or fewer."),
])
async def test_rename_bad_names(api, wrappers, name, status, msg):
    w = await wrappers("w1")
    r = await api.post("/api/sessions/w1/rename", json={"name": name})
    assert r.status == status and await r.text() == msg
    assert await w.inputs() == []


async def test_rename_waits_for_open_dialogs(api, bridge, wrappers):
    w = await wrappers("w1")
    assert (await api.post("/api/sessions/w1/rename", json={"name": "n" * 80})).status == 200
    await w.inputs()
    for st in ("permission", "question"):
        w.session.status = st
        r = await api.post("/api/sessions/w1/rename", json={"name": "x"})
        assert r.status == 409 and "waiting for an answer" in await r.text()
    w.session.status = "idle"
    await show(w, TRUST)  # a menu the hooks don't know about
    assert (await api.post("/api/sessions/w1/rename", json={"name": "x"})).status == 409
    assert await w.inputs() == []
    await w.close()
    await until(lambda: bridge.sessions["w1"].writer is None)
    r = await api.post("/api/sessions/w1/rename", json={"name": "x"})
    assert r.status == 409 and "isn't connected" in await r.text()


# ---- hooks and the status machine -------------------------------------------------------------
async def test_hooks_drive_the_status(api, bridge, hooks, wrappers, conv, sandbox):
    w = await wrappers("w1")
    s = w.session

    async def status():
        (d,) = (await (await api.get("/api/sessions")).json())["sessions"]
        return d["status"], d["detail"]
    await hooks.fire("w1", hook_event_name="SessionStart", session_id=SID, transcript_path=conv.path,
                     cwd=str(sandbox.home / "proj" / "sub"))
    assert await status() == ("idle", "")
    assert s.claude_session_id == SID and s.transcript.path == conv.path and s.cwd.endswith("/proj/sub")
    await hooks.fire("w1", hook_event_name="UserPromptSubmit")
    assert await status() == ("working", "")
    await hooks.fire("w1", hook_event_name="Notification", notification_type="idle_prompt",
                     message="Claude is waiting for your input")
    assert await status() == ("working", "")
    await hooks.fire("w1", hook_event_name="Stop")
    assert await status() == ("idle", "")
    await hooks.fire("w1", hook_event_name="SessionEnd", reason="logout")
    assert await status() == ("ended", "logout")
    assert json.loads((sandbox.run / "state.json").read_text())["w1"]["status"] == "ended"


async def test_hook_finds_the_transcript_by_session_id(api, bridge, hooks, wrappers, conv):
    w = await wrappers("w1")
    await hooks.fire("w1", hook_event_name="SessionStart", session_id=SID)
    assert w.session.transcript.path == conv.path
    d = await (await api.get("/api/sessions/w1/items")).json()
    assert d["sid"] == "w1" and [i["text"] for i in d["items"]] == ["start"]
    conv.write(conv.assistant(conv.text("reply")))
    await bridge.poll_transcript(w.session)
    d = await (await api.get("/api/sessions/w1/items?limit=1")).json()
    assert [i["text"] for i in d["items"]] == ["reply"]


async def test_hooks_before_the_wrapper_says_hello(bridge, hooks, wrappers, conv):
    for _ in range(4):
        await hooks.fire("w5", hook_event_name="Stop")
    await hooks.fire("w5", hook_event_name="SessionStart", session_id=SID, transcript_path=conv.path)
    await hooks.fire("w5", hook_event_name="UserPromptSubmit")
    assert len(bridge.early_hooks["w5"]) == 5
    w = await wrappers("w5")
    assert w.session.status == "working" and w.session.transcript.path == conv.path
    assert "w5" not in bridge.early_hooks


async def test_hooks_without_an_id_or_json_are_ignored(bridge, hooks):
    r = await hooks.post("/hook", json={"hook_event_name": "Stop"})
    assert await r.json() == {} and dict(bridge.early_hooks) == {}
    r = await hooks.post("/hook", data=b"{nope", headers={"X-Claude-Mobile-Id": "w1",
                                                           "Content-Type": "application/json"})
    assert r.status == 200 and await r.json() == {} and dict(bridge.early_hooks) == {}


async def test_permission_prompt_notifies_with_the_pending_edit(bridge, hooks, wrappers, conv, sandbox):
    w = await wrappers("w1")
    await hooks.fire("w1", hook_event_name="UserPromptSubmit", session_id=SID, transcript_path=conv.path)
    edit = conv.tool_use("Edit", {"file_path": str(sandbox.home / "proj" / "app.kt"),
                                  "old_string": "val x = 1", "new_string": "val x = 2"})
    conv.write(conv.assistant(edit))
    msg = "Claude needs your permission to use Edit"
    await hooks.fire("w1", hook_event_name="Notification", notification_type="permission_prompt", message=msg)
    assert (w.session.status, w.session.detail) == ("permission", msg)
    await until(lambda: kinds(bridge) == ["permission"], what="permission notification")
    (n,) = bridge.notifications
    assert n["sid"] == "w1" and n["title"] == "proj" and n["t"] == "notify"
    assert n["body"] == msg + "\nEdit: - val x = 1\n+ val x = 2"
    assert n["tool"] == {"name": "Edit", "summary": "app.kt", "diff": ["-val x = 1", "+val x = 2"], "add": 1, "del": 1}

    # allowed at the laptop: the result lands and the session is working again
    conv.write(conv.user([conv.result(edit["id"], "updated")]))
    w.session.status_since -= 2
    await bridge.poll_transcript(w.session)
    assert w.session.status == "working"


async def test_permission_prompt_for_a_command(bridge, hooks, wrappers, conv):
    w = await wrappers("w1")
    await hooks.fire("w1", hook_event_name="UserPromptSubmit", session_id=SID, transcript_path=conv.path)
    conv.write(conv.assistant(conv.tool_use("Bash", {"command": "rm -rf build", "description": "Clean"})))
    # older Claude Code: no notification_type, the message says it
    await hooks.fire("w1", hook_event_name="Notification", message="Claude needs your permission to use Bash")
    await until(lambda: kinds(bridge) == ["permission"])
    assert bridge.notifications[0]["tool"] == {"name": "Bash", "summary": "Clean", "detail": "rm -rf build"}
    assert w.session.status == "permission"


async def test_permission_without_a_pending_tool(bridge, hooks, wrappers):
    await wrappers("w1")
    await hooks.fire("w1", hook_event_name="Notification", notification_type="permission_prompt", message="Allow?")
    await until(lambda: kinds(bridge) == ["permission"])
    assert "tool" not in bridge.notifications[0] and bridge.notifications[0]["body"] == "Allow?"


async def test_ask_user_question_notifies_with_its_options(bridge, hooks, wrappers, conv):
    w = await wrappers("w1")
    await hooks.fire("w1", hook_event_name="UserPromptSubmit", session_id=SID, transcript_path=conv.path)
    qid = ask(conv, question())
    await bridge.poll_transcript(w.session)  # the ticker sees the question first
    assert (w.session.status, w.session.detail) == ("question", "Which database should we use?")
    (n,) = bridge.notifications
    assert n["kind"] == "question" and n["qid"] == qid and n["options"] == ["PostgreSQL", "SQLite"]
    assert n["body"] == "Which database should we use?\nPostgreSQL · SQLite"
    assert w.session.summary()["questions"][0]["question"] == "Which database should we use?"
    # Claude Code also fires its permission_prompt hook for the question's dialog
    await hooks.fire("w1", hook_event_name="Notification", notification_type="permission_prompt",
                     message="Claude needs your permission to use AskUserQuestion")
    for _ in range(20):
        await bridge.poll_transcript(w.session)
    assert w.session.status == "question" and kinds(bridge) == ["question"]


async def test_ask_user_question_when_the_hook_comes_first(bridge, hooks, wrappers, conv, fast):
    w = await wrappers("w1")
    await hooks.fire("w1", hook_event_name="UserPromptSubmit", session_id=SID, transcript_path=conv.path)
    qid = ask(conv, question())
    await hooks.fire("w1", hook_event_name="Notification", notification_type="permission_prompt",
                     message="Claude needs your permission to use AskUserQuestion")
    assert 0.3 in fast.slept  # taken for a tool permission at first: notify_permission ran
    await until(lambda: w.session.status == "question", what="question status")
    await until(lambda: kinds(bridge) == ["question"])
    for _ in range(20):
        await bridge.poll_transcript(w.session)
    assert kinds(bridge) == ["question"] and bridge.notifications[0]["qid"] == qid


@pytest.mark.parametrize("questions", [
    [question(), question("And the cache?", ("Redis", "None"))],
    [question(multi=True)],
])
async def test_complex_questions_get_no_quick_answers(bridge, hooks, wrappers, conv, questions):
    w = await wrappers("w1")
    await hooks.fire("w1", hook_event_name="UserPromptSubmit", session_id=SID, transcript_path=conv.path)
    qid = ask(conv, *questions)
    await bridge.poll_transcript(w.session)
    (n,) = bridge.notifications
    assert n["qid"] == qid and "options" not in n


async def test_answered_question_returns_to_working(bridge, hooks, wrappers, conv):
    w = await wrappers("w1")
    await hooks.fire("w1", hook_event_name="UserPromptSubmit", session_id=SID, transcript_path=conv.path)
    qid = ask(conv, question())
    await bridge.poll_transcript(w.session)
    conv.write(conv.user([conv.result(qid, "User answered: SQLite")]))
    await bridge.poll_transcript(w.session)
    assert w.session.status == "question"  # within a second of the dialog: could be a redraw race
    conv.write(conv.assistant(conv.text("Going with SQLite.")))
    w.session.status_since -= 2
    await bridge.poll_transcript(w.session)
    assert w.session.status == "working"


async def test_elicitation_dialog_is_a_question(bridge, hooks, wrappers):
    w = await wrappers("w1")
    await hooks.fire("w1", hook_event_name="Notification", notification_type="elicitation_dialog",
                     message="The MCP server wants your email")
    assert (w.session.status, w.session.detail) == ("question", "The MCP server wants your email")
    (n,) = bridge.notifications
    assert n["kind"] == "question" and n["body"] == "The MCP server wants your email"


# ---- finished notifications ------------------------------------------------------------------------
async def test_done_notification_with_the_last_reply(bridge, hooks, wrappers, conv):
    w = await wrappers("w1")
    await hooks.fire("w1", hook_event_name="UserPromptSubmit", session_id=SID, transcript_path=conv.path)
    conv.write(conv.assistant(conv.text("**Fixed** it in `parser.py`."), conv.tool_use("Bash", {"command": "x"})),
               conv.assistant(conv.text("All tests pass.")))
    await hooks.fire("w1", hook_event_name="Stop")
    await until(lambda: kinds(bridge) == ["done"], what="done notification")
    n = bridge.notifications[0]
    assert n["title"] == "proj" and n["body"] == "All tests pass."
    await hooks.fire("w1", hook_event_name="UserPromptSubmit")
    await hooks.fire("w1", hook_event_name="Stop")  # nothing new in the transcript: same notification
    for _ in range(20):
        await bridge.poll_transcript(w.session)
    assert kinds(bridge) == ["done"]


async def test_done_without_a_reply_says_finished(bridge, hooks, wrappers, conv):
    await wrappers("w1")
    await hooks.fire("w1", hook_event_name="UserPromptSubmit", session_id=SID, transcript_path=conv.path)
    await hooks.fire("w1", hook_event_name="Stop")
    await until(lambda: kinds(bridge) == ["done"])
    assert bridge.notifications[0]["body"] == "Finished"


async def test_no_done_notification_if_work_resumed(bridge, hooks, wrappers, conv, fast):
    w = await wrappers("w1")
    await hooks.fire("w1", hook_event_name="UserPromptSubmit", session_id=SID, transcript_path=conv.path)
    fast.on_sleep.append(lambda delay: delay == 0.6 and w.session.set_status("working"))
    await hooks.fire("w1", hook_event_name="Stop")
    await until(lambda: 0.6 in fast.slept)
    for _ in range(20):
        await bridge.poll_transcript(w.session)
    assert kinds(bridge) == []


async def test_quiet_while_you_type_at_the_laptop(api, bridge, hooks, wrappers, conv):
    w = await wrappers("w1")
    await hooks.fire("w1", hook_event_name="UserPromptSubmit", session_id=SID, transcript_path=conv.path)
    await w.activity()
    await until(lambda: w.session.last_local_input > 0)
    conv.write(conv.assistant(conv.text("one")))
    await hooks.fire("w1", hook_event_name="Stop")
    await until(lambda: w.session.status == "idle")
    for _ in range(20):
        await bridge.poll_transcript(w.session)
    assert kinds(bridge) == []
    # typing from the phone afterwards brings the notifications back
    await api.post("/api/sessions/w1/send", json={"text": "more"})
    await w.inputs()
    await hooks.fire("w1", hook_event_name="UserPromptSubmit")
    conv.write(conv.assistant(conv.text("two")))
    await hooks.fire("w1", hook_event_name="Stop")
    await until(lambda: kinds(bridge) == ["done"])
    # ...and so does time
    w.session.last_local_input = w.session.last_remote_input + 1
    assert bridge.quiet(w.session)
    w.session.last_local_input = time.time() - 46
    w.session.last_remote_input = 0
    assert not bridge.quiet(w.session)


async def test_duplicate_notifications_are_dropped_for_20s(bridge, wrappers):
    w = await wrappers("w1")
    bridge.notify(w.session, "permission", "t", "same body")
    bridge.notify(w.session, "permission", "t", "same body")
    bridge.notify(w.session, "permission", "t", "other body")
    bridge.notify(w.session, "question", "t", "same body")
    assert kinds(bridge) == ["permission", "permission", "question"]
    bridge.notified = {k: v - 21 for k, v in bridge.notified.items()}
    bridge.notify(w.session, "permission", "t", "same body")
    assert len(bridge.notifications) == 4
    ids = [n["id"] for n in bridge.notifications]
    assert ids == sorted(set(ids))


async def test_notifications_reach_phones_and_are_replayed(api, bridge, wrappers):
    w = await wrappers("w1")
    ws = await api.ws_connect("/api/ws")
    assert (await ws.receive_json())["t"] == "hello"
    assert (await ws.receive_json())["t"] == "sessions"
    bridge.notify(w.session, "done", "proj", "first", extra={"x": 1})
    n = await ws.receive_json()
    assert (n["t"], n["kind"], n["body"], n["x"], n["sid"]) == ("notify", "done", "first", 1, "w1")
    bridge.notify(w.session, "done", "proj", "second")
    await ws.receive_json()
    await ws.close()

    bridge.notifications[0]["ts"] -= 3600  # too old to replay
    ws = await api.ws_connect("/api/ws")
    await ws.receive_json()
    await ws.receive_json()
    await ws.send_json({"t": "hello", "last_notify": n["id"] - 1})
    assert (await ws.receive_json())["body"] == "second"
    await ws.send_json({"t": "ping"})
    assert (await ws.receive_json())["t"] == "pong"  # nothing else was replayed
    await ws.close()


async def test_subscribed_phones_get_chat_items(api, bridge, wrappers, conv):
    w = await wrappers("w1")
    bridge.attach_transcript(w.session, conv.path, SID)
    ws = await api.ws_connect("/api/ws")
    await ws.receive_json()
    await ws.receive_json()
    await ws.send_json({"t": "sub", "sid": "w1"})
    d = await ws.receive_json()
    assert d["t"] == "items" and d["reset"] is True and [i["text"] for i in d["items"]] == ["start"]
    conv.write(conv.assistant(conv.text("new reply")))
    await bridge.poll_transcript(w.session)
    d = await ws.receive_json()
    assert d["reset"] is False and [i["text"] for i in d["items"]] == ["new reply"]
    await ws.close()


async def test_a_long_chat_arrives_newest_first(api, bridge, wrappers, conv, monkeypatch):
    monkeypatch.setattr(server, "SNAPSHOT_FIRST", 3)
    for n in range(7):
        conv.write(conv.assistant(conv.text(f"reply {n}")))
    w = await wrappers("w1")
    bridge.attach_transcript(w.session, conv.path, SID)
    ws = await api.ws_connect("/api/ws")
    await ws.receive_json()
    await ws.receive_json()
    await ws.send_json({"t": "sub", "sid": "w1"})
    first = await ws.receive_json()
    assert first["reset"] is True and [i["text"] for i in first["items"]] == ["reply 4", "reply 5", "reply 6"]
    rest = await ws.receive_json()
    assert rest["reset"] is False and [i["text"] for i in rest["items"]] == ["start"] + [f"reply {n}" for n in range(4)]
    await ws.close()


# ---- Claude Code's own session registry, and restarts -------------------------------------------------
async def test_session_file_fills_in_id_name_and_status(bridge, wrappers, conv, sandbox):
    pid = 2**22 + 99
    reg = sandbox.sessions / f"{pid}.json"
    reg.write_text(json.dumps({"pid": pid, "sessionId": SID, "name": "refactor", "status": "busy"}))
    w = await wrappers("w1", pid=pid)
    s = w.session
    assert s.claude_session_id == SID and s.transcript.path == conv.path
    assert s.name == "refactor" and s.title == "refactor" and s.status == "working"
    reg.write_text(json.dumps({"pid": pid, "sessionId": SID, "name": "refactor", "status": "idle"}))
    bridge.read_cc_session_file(s)
    assert s.status == "idle"
    conv.write(conv.meta("ai-title", aiTitle="Refactoring the parser"))
    await bridge.poll_transcript(s)
    assert s.title == "Refactoring the parser"  # the transcript's title wins


async def test_session_file_doesnt_override_hooks(bridge, wrappers, sandbox):
    pid = 2**22 + 98
    (sandbox.sessions / f"{pid}.json").write_text(json.dumps({"pid": pid, "status": "idle"}))
    w = await wrappers("w1", pid=pid, wait=False)
    await until(lambda: "w1" in bridge.sessions)
    s = bridge.sessions["w1"]
    s.set_status("permission")
    s.cc_status = None
    bridge.read_cc_session_file(s)
    assert s.status == "permission"
    await w.close()


async def test_state_survives_a_bridge_restart(bridge, hooks, wrappers, conv, cfg, sandbox):
    w = await wrappers("w1")
    await hooks.fire("w1", hook_event_name="UserPromptSubmit", session_id=SID, transcript_path=conv.path)
    await w.activity()
    await until(lambda: w.session.last_local_input > 0)
    bridge.save_state()
    saved = json.loads((sandbox.run / "state.json").read_text())["w1"]
    assert saved["session_id"] == SID and saved["transcript"] == conv.path and saved["status"] == "working"

    fresh = server.Bridge(cfg)  # restarted: the wrapper reconnects
    s = fresh.register({"id": "w1", "pid": NO_SUCH_PID, "wrapper_pid": os.getpid(), "cwd": "/x"}, writer=object())
    assert s.transcript.path == conv.path and s.claude_session_id == SID
    assert s.status == "working" and s.last_local_input == w.session.last_local_input


async def test_ticker_pushes_screens_and_sessions_and_reaps_dead_ones(api, bridge, wrappers, conv):
    w = await wrappers("w1")
    bridge.attach_transcript(w.session, conv.path, SID)
    ghost = bridge.register({"id": "ghost", "pid": NO_SUCH_PID, "wrapper_pid": NO_SUCH_PID, "cwd": "/x"},
                            writer=object())
    ghost.writer = None  # its wrapper died while the bridge was down
    ws = await api.ws_connect("/api/ws")
    await ws.receive_json()
    await ws.receive_json()
    await ws.send_json({"t": "screen", "sid": "w1"})
    await ws.send_json({"t": "sub", "sid": "w1"})
    assert (await ws.receive_json())["t"] == "items"
    await show(w, TRUST)
    conv.write(conv.assistant(conv.text("polled by the ticker")))

    ticker = asyncio.get_running_loop().create_task(bridge.ticker())
    seen = {}
    try:
        while not ("screen" in seen and "items" in seen and "sessions" in seen
                   and [s["id"] for s in seen["sessions"]["sessions"]] == ["w1"]):
            m = await ws.receive_json(timeout=2)
            seen[m["t"]] = m
    finally:
        ticker.cancel()
    assert [o["label"] for o in seen["screen"]["options"]] == ["Yes, proceed", "No, exit"]
    assert [i["text"] for i in seen["items"]["items"]] == ["polled by the ticker"]
    assert "ghost" not in bridge.sessions
    await ws.close()


async def test_big_context_in_the_summary(bridge, wrappers, conv):
    w = await wrappers("w1")
    usage = {"input_tokens": 1, "cache_read_input_tokens": 250_000, "output_tokens": 10}
    conv.write(conv.assistant(conv.text("big"), usage=usage, model="claude-opus-5-5"))
    bridge.attach_transcript(w.session, conv.path, SID)
    d = w.session.summary()
    assert (d["context"], d["context_max"], d["model"]) == (250_011, 1_000_000, "claude-opus-5-5")
    assert d["last_text"] == "big"
    assert "claude-opus-5-5" in server.BIG_CONTEXT_MODELS
