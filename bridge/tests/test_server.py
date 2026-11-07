"""The phone-facing API: auth, listings, spawn/upload/settings validation, app updates,
and the small helpers in server.py."""

import asyncio
import hashlib
import json
import os
import stat
from types import SimpleNamespace

import pytest

from claude_bridge import launcher, server
from claude_bridge.server import clean_name, parent_pid, plain, tool_preview
from conftest import TOKEN
from helpers import Conv, touch_old, until

UUID = "cccccccc-0000-4000-8000-000000000001"


# ---- auth ----------------------------------------------------------------------------
@pytest.mark.parametrize("headers,query", [
    ({}, ""),
    ({"Authorization": "Bearer wrong"}, ""),
    ({}, "?token=nope"),
    ({}, "?token="),
    ({"Authorization": f"Bearer {TOKEN}x"}, ""),
    ({"Authorization": f"Bearer {TOKEN[:-1]}"}, ""),
    ({"Authorization": f"Basic {TOKEN}"}, ""),
    ({"Authorization": "Bearer wrong"}, f"?token={TOKEN}"),  # a header, once sent, is what counts
])
async def test_requests_without_the_right_token_are_refused(aiohttp_client, bridge, headers, query):
    client = await aiohttp_client(server.make_app(bridge))
    for path in ("/api/health", "/api/sessions", "/api/gh/repos", "/api/settings", "/api/app/apk"):
        r = await client.get(path + query, headers=headers)
        assert r.status == 401, path
        assert await r.text() == "bad token"
    r = await client.post("/api/spawn" + query, json={"cwd": "/"}, headers=headers)
    assert r.status == 401


async def test_token_in_the_header(aiohttp_client, bridge):
    client = await aiohttp_client(server.make_app(bridge))
    for kw in ({"headers": {"Authorization": f"Bearer {TOKEN}"}}, {"headers": {"Authorization": TOKEN}}):
        r = await client.get("/api/health", **kw)
        assert r.status == 200, kw
        d = await r.json()
        assert d["ok"] is True and d["sessions"] == 0


async def test_websocket_needs_the_token_too(aiohttp_client, bridge):
    client = await aiohttp_client(server.make_app(bridge))
    r = await client.get("/api/ws")
    assert r.status == 401


# ---- listings -----------------------------------------------------------------------------
async def test_sessions_empty(api):
    d = await (await api.get("/api/sessions")).json()
    assert d == {"t": "sessions", "sessions": [], "awake": False, "keep_awake": "working"}


async def test_unknown_session_is_404(api):
    for method, path in (("GET", "items"), ("GET", "screen"), ("POST", "send"), ("POST", "keys"),
                         ("POST", "select"), ("POST", "answer"), ("POST", "choose"), ("POST", "end"),
                         ("POST", "rename"), ("POST", "upload")):
        r = await api.request(method, f"/api/sessions/nope/{path}", json={})
        assert r.status == 404, path


async def test_projects_endpoint(api, bridge, sandbox, wrappers):
    proj = sandbox.home / "proj"
    proj.mkdir(exist_ok=True)
    d = sandbox.projects / "-proj"
    d.mkdir()
    c = Conv(d / f"{UUID}.jsonl", cwd=str(proj), session_id=UUID)
    c.write(c.prompt("hello"))
    (sandbox.sessions / "1.json").write_text(json.dumps({"pid": os.getpid(), "sessionId": UUID}))
    (p,) = (await (await api.get("/api/projects")).json())["projects"]
    assert p["cwd"] == str(proj) and p["path"] == "~/proj"
    (s,) = p["sessions"]
    assert s["session_id"] == UUID and s["title"] == "hello" and s["running"] is True and s["live"] is None

    w = await wrappers("w1")
    w.session.claude_session_id = UUID
    (p,) = (await (await api.get("/api/projects")).json())["projects"]
    assert p["sessions"][0]["live"] == "w1"


# ---- spawning sessions --------------------------------------------------------------------
@pytest.fixture
def spawner(monkeypatch, bridge):
    """launcher.spawn replaced: records calls; `then(token)` can act as the new terminal."""
    st = SimpleNamespace(calls=[], then=None, error=None, returncode=None)

    async def fake_spawn(cfg, cwd, token, resume, prompt=None):
        st.calls.append(SimpleNamespace(cwd=cwd, token=token, resume=resume, prompt=prompt))
        if st.error:
            raise RuntimeError(st.error)
        if st.then:
            asyncio.get_running_loop().create_task(st.then(token))
        return SimpleNamespace(returncode=st.returncode)
    monkeypatch.setattr(launcher, "spawn", fake_spawn)
    return st


@pytest.fixture
def folder(sandbox):
    p = sandbox.home / "Projects" / "app"
    p.mkdir(parents=True)
    return p


async def test_spawn_new_session(api, bridge, spawner, folder, wrappers, fast):
    fast.wait_cap = 5

    async def terminal(token):
        await wrappers("w9", spawn=token, cwd=str(folder))
    spawner.then = terminal
    r = await api.post("/api/spawn", json={"cwd": "~/Projects/app", "prompt": "  -fix it  "})
    assert await r.json() == {"ok": True, "sid": "w9"}
    (c,) = spawner.calls
    assert c.cwd == str(folder) and c.resume is None and c.prompt == "-fix it"
    assert c.token not in bridge.spawns


async def test_spawn_in_home_itself(api, spawner, sandbox):
    r = await api.post("/api/spawn", json={"cwd": str(sandbox.home)})
    assert r.status == 200 and spawner.calls[0].cwd == str(sandbox.home)


async def test_spawn_slow_terminal(api, bridge, spawner, folder):
    r = await api.post("/api/spawn", json={"cwd": str(folder)})
    assert await r.json() == {"ok": True, "sid": None}
    assert spawner.calls[0].token in bridge.spawns  # still waiting for its hello, for a while


async def test_spawn_terminal_that_failed(api, bridge, spawner, folder):
    spawner.returncode = 1
    r = await api.post("/api/spawn", json={"cwd": str(folder)})
    assert r.status == 503 and await r.text() == "The terminal on the laptop didn't start"
    assert bridge.spawns == {}


async def test_spawn_without_a_desktop(api, bridge, spawner, folder):
    spawner.error = "No desktop session on the laptop to open a terminal in"
    r = await api.post("/api/spawn", json={"cwd": str(folder)})
    assert r.status == 503 and await r.text() == spawner.error
    assert bridge.spawns == {}


@pytest.mark.parametrize("make_cwd", [
    lambda s: "/usr",                                   # outside home
    lambda s: str(s.home / "missing"),                  # not there
    lambda s: str(s.home / ".bashrc"),                  # not a folder
    lambda s: str(s.home.parent / (s.home.name + "X")),  # /home/userX is not in /home/user
    lambda s: str(s.home / "link-out"),                 # symlink inside home pointing outside
    lambda s: str(s.home / "proj" / ".." / ".." / "elsewhere"),
])
async def test_spawn_refuses_folders_outside_home(api, spawner, sandbox, make_cwd):
    (sandbox.home / ".bashrc").write_text("")
    (sandbox.home.parent / (sandbox.home.name + "X")).mkdir()
    (sandbox.root / "elsewhere").mkdir()
    (sandbox.home / "proj").mkdir()
    (sandbox.home / "link-out").symlink_to(sandbox.root / "elsewhere")
    r = await api.post("/api/spawn", json={"cwd": make_cwd(sandbox)})
    assert r.status == 400 and (await r.text()).startswith("not a folder in your home directory")
    assert spawner.calls == []


async def test_spawn_follows_symlinks_that_stay_in_home(api, spawner, sandbox, folder):
    (sandbox.home / "app-link").symlink_to(folder)
    r = await api.post("/api/spawn", json={"cwd": "~/app-link"})
    assert r.status == 200 and spawner.calls[0].cwd == str(folder)


async def test_spawn_needs_a_folder(api, spawner, sandbox, monkeypatch):
    # an empty cwd must not mean "wherever the bridge daemon happens to run"
    monkeypatch.chdir(sandbox.home)
    for body in ({}, {"cwd": ""}, {"cwd": None}):
        r = await api.post("/api/spawn", json=body)
        assert r.status == 400, body
    assert spawner.calls == []


@pytest.mark.parametrize("resume", ["not-a-uuid", "CCCCCCCC-0000-4000-8000-000000000001", f"{UUID}\n", 12])
async def test_spawn_bad_session_id(api, spawner, folder, resume):
    r = await api.post("/api/spawn", json={"cwd": str(folder), "resume": resume})
    assert r.status == 400 and await r.text() == "bad session id"
    assert spawner.calls == []


def past_session(sandbox, cwd, sid=UUID):
    d = sandbox.projects / "-x"
    d.mkdir(exist_ok=True)
    c = Conv(d / f"{sid}.jsonl", cwd=str(cwd), session_id=sid)
    c.write(c.prompt("earlier work"))


async def test_spawn_resume(api, spawner, sandbox, folder):
    past_session(sandbox, folder)
    r = await api.post("/api/spawn", json={"cwd": "/ignored", "resume": UUID})
    assert r.status == 200
    (c,) = spawner.calls
    assert c.resume == UUID and c.cwd == str(folder)  # the folder it ran in, not the one sent


async def test_spawn_resume_unknown_or_busy(api, spawner, sandbox, folder):
    r = await api.post("/api/spawn", json={"resume": UUID})
    assert r.status == 404
    past_session(sandbox, folder)
    (sandbox.sessions / "77.json").write_text(json.dumps({"pid": os.getpid(), "sessionId": UUID}))
    r = await api.post("/api/spawn", json={"resume": UUID})
    assert r.status == 409 and "open in a terminal" in await r.text()
    assert spawner.calls == []


async def test_spawn_resume_outside_home(api, spawner, sandbox):
    out = sandbox.root / "elsewhere"
    out.mkdir()
    past_session(sandbox, out)
    r = await api.post("/api/spawn", json={"resume": UUID})
    assert r.status == 400 and spawner.calls == []


async def test_spawn_resume_of_a_live_session_just_opens_it(api, spawner, sandbox, folder, wrappers):
    w = await wrappers("w3")
    w.session.claude_session_id = UUID
    r = await api.post("/api/spawn", json={"resume": UUID})
    assert await r.json() == {"ok": True, "sid": "w3"} and spawner.calls == []


# ---- uploads --------------------------------------------------------------------------------
async def test_upload_photo(api, wrappers, sandbox):
    await wrappers("w1")
    data = b"\xff\xd8\xff\xe0 jpeg bytes"
    r = await api.post("/api/sessions/w1/upload", data=data, headers={"Content-Type": "image/jpeg"})
    d = await r.json()
    assert d["ok"] is True
    path = d["path"]
    assert os.path.dirname(path) == str(sandbox.uploads) and path.endswith(".jpg")
    with open(path, "rb") as f:
        assert f.read() == data
    assert stat.S_IMODE(os.stat(path).st_mode) == 0o600
    assert stat.S_IMODE(os.stat(sandbox.uploads).st_mode) == 0o700
    r = await api.post("/api/sessions/w1/upload", data=b"GIF89a", headers={"Content-Type": "image/gif"})
    assert (await r.json())["path"].endswith(".gif")


@pytest.mark.parametrize("ctype", ["image/svg+xml", "text/plain", "application/octet-stream", "image/heic"])
async def test_upload_wrong_type(api, wrappers, ctype):
    await wrappers("w1")
    r = await api.post("/api/sessions/w1/upload", data=b"x", headers={"Content-Type": ctype})
    assert r.status == 415


async def test_upload_empty_and_too_big(api, wrappers, sandbox):
    await wrappers("w1")
    r = await api.post("/api/sessions/w1/upload", data=b"", headers={"Content-Type": "image/png"})
    assert r.status == 400 and await r.text() == "empty upload"
    r = await api.post("/api/sessions/w1/upload", data=b"\0" * (25 * 1024 * 1024 + 1),
                       headers={"Content-Type": "image/png"})
    assert r.status == 413
    assert not sandbox.uploads.exists() or os.listdir(sandbox.uploads) == []


async def test_upload_clears_old_photos(api, wrappers, sandbox):
    await wrappers("w1")
    sandbox.uploads.mkdir(parents=True)
    old, recent = sandbox.uploads / "photo-old.jpg", sandbox.uploads / "photo-recent.jpg"
    old.write_bytes(b"x")
    recent.write_bytes(b"x")
    touch_old(str(old), 15 * 86400)
    touch_old(str(recent), 13 * 86400)
    await api.post("/api/sessions/w1/upload", data=b"x", headers={"Content-Type": "image/webp"})
    names = os.listdir(sandbox.uploads)
    assert "photo-old.jpg" not in names and "photo-recent.jpg" in names and len(names) == 2


# ---- settings -----------------------------------------------------------------------------
async def test_settings_get(api):
    assert await (await api.get("/api/settings")).json() == {"keep_awake": "working", "awake": False}


@pytest.mark.parametrize("mode", ["sometimes", "", 1, ["off"], "OFF"])
async def test_settings_rejects_bad_modes(api, bridge, mode):
    r = await api.post("/api/settings", json={"keep_awake": mode})
    assert r.status == 400 and await r.text() == "keep_awake must be working, always or off"
    assert bridge.cfg["keep_awake"] == "working"


async def test_settings_persist_and_apply(api, bridge, sandbox, inhibitor):
    cfg_path = sandbox.config / "config.json"
    cfg_path.write_text(json.dumps({"token": "keep-me", "terminal": ["foot", "-e"], "keep_awake": "working"}))
    d = await (await api.post("/api/settings", json={"keep_awake": "always"})).json()
    assert d == {"keep_awake": "always", "awake": True}
    assert json.loads(cfg_path.read_text()) == {"token": "keep-me", "terminal": ["foot", "-e"], "keep_awake": "always"}
    assert stat.S_IMODE(os.stat(cfg_path).st_mode) == 0o600
    assert len(inhibitor.procs) == 1
    d = await (await api.post("/api/settings", json={"keep_awake": "off"})).json()
    assert d == {"keep_awake": "off", "awake": False}
    assert json.loads(cfg_path.read_text())["keep_awake"] == "off"
    assert bridge.dirty
    # no keep_awake key: just reports
    d = await (await api.post("/api/settings", json={})).json()
    assert d == {"keep_awake": "off", "awake": False}


async def test_malformed_bodies_are_client_errors(api, wrappers):
    await wrappers("w1")
    bad = [("/api/settings", b"{nope"), ("/api/settings", b"[1]"), ("/api/sessions/w1/send", b"{nope"),
           ("/api/sessions/w1/select", b'{"index": "two"}'), ("/api/spawn", b'"x"')]
    for path, body in bad:
        r = await api.post(path, data=body, headers={"Content-Type": "application/json"})
        assert 400 <= r.status < 500, (path, r.status)


# ---- config file ----------------------------------------------------------------------------
def test_save_config_key(sandbox):
    path = sandbox.config / "config.json"
    server.save_config_key("keep_awake", "off")  # no file yet
    assert json.loads(path.read_text()) == {"keep_awake": "off"}
    path.write_text("{broken")
    server.save_config_key("a", [1])
    assert json.loads(path.read_text()) == {"a": [1]}
    server.save_config_key("b", 2)
    assert json.loads(path.read_text()) == {"a": [1], "b": 2}
    assert not (sandbox.config / "config.json.tmp").exists()
    assert stat.S_IMODE(os.stat(path).st_mode) == 0o600


def test_load_config_makes_a_token_once(sandbox):
    path = sandbox.config / "config.json"
    cfg = server.load_config()
    assert len(cfg["token"]) >= 30 and cfg["port"] == 8787 and cfg["quiet_after_local_input"] == 45
    assert stat.S_IMODE(os.stat(path).st_mode) == 0o600
    assert server.load_config()["token"] == cfg["token"]
    path.write_text(json.dumps({"token": "mine", "port": 9999}))
    cfg = server.load_config()
    assert cfg["token"] == "mine" and cfg["port"] == 9999 and cfg["keep_awake"] == "working"


# ---- app updates ------------------------------------------------------------------------------
def build_apk(d, version=7, data=b"PK\x03\x04 apk", name="app-debug.apk"):
    d.mkdir(parents=True, exist_ok=True)
    (d / name).write_bytes(data)
    meta = {"version": 3, "artifactType": {"type": "APK", "kind": "Directory"}, "applicationId": "x",
            "variantName": "debug", "elements": [{"type": "SINGLE", "filters": [], "attributes": [],
                                                  "versionCode": version, "versionName": f"1.{version}",
                                                  "outputFile": name}], "elementType": "File"}
    (d / "output-metadata.json").write_text(json.dumps(meta))
    return hashlib.sha256(data).hexdigest()


async def test_no_build(api):
    assert (await api.get("/api/app")).status == 404
    assert (await api.get("/api/app/apk")).status == 404


async def test_app_build_and_download(api, sandbox):
    sha = build_apk(sandbox.apk)
    d = await (await api.get("/api/app")).json()
    assert d == {"versionCode": 7, "versionName": "1.7", "size": len(b"PK\x03\x04 apk"), "sha256": sha}
    r = await api.get("/api/app/apk")
    assert r.status == 200 and r.headers["Content-Type"] == "application/vnd.android.package-archive"
    assert await r.read() == b"PK\x03\x04 apk"


def test_app_build_is_cached_by_metadata_mtime(bridge, sandbox):
    sha = build_apk(sandbox.apk)
    assert bridge.app_build()[0]["sha256"] == sha
    (sandbox.apk / "app-debug.apk").write_bytes(b"changed")
    assert bridge.app_build()[0]["sha256"] == sha  # metadata untouched: cached
    meta = sandbox.apk / "output-metadata.json"
    st = os.stat(meta)
    sha2 = build_apk(sandbox.apk, version=8, data=b"new build")
    os.utime(meta, (st.st_atime + 10, st.st_mtime + 10))
    info, path = bridge.app_build()
    assert info["versionCode"] == 8 and info["sha256"] == sha2 and path == str(sandbox.apk / "app-debug.apk")


def test_apk_dir_from_config(bridge, sandbox):
    other = sandbox.root / "elsewhere-apk"
    build_apk(other, version=42)
    bridge.cfg["apk_dir"] = str(other)
    assert bridge.app_build()[0]["versionCode"] == 42


@pytest.mark.parametrize("meta", [
    "{broken", "{}", '{"elements": []}', '{"elements": [{"versionCode": 1}]}',
    '{"elements": [{"versionCode": "x", "outputFile": "app-debug.apk"}]}',
    '{"elements": [{"versionCode": 1, "outputFile": "missing.apk"}]}',
    '{"elements": [null]}', "[]",
])
def test_bad_metadata_means_no_build(bridge, sandbox, meta):
    build_apk(sandbox.apk)
    (sandbox.apk / "output-metadata.json").write_text(meta)
    assert bridge.app_build() is None


# ---- context window ---------------------------------------------------------------------------
def test_context_window(sandbox):
    t = SimpleNamespace(context_tokens=150_000, model="claude-opus-5-5")
    assert server.context_window(None) == 200_000
    assert server.context_window(t) == 200_000
    t.context_tokens = 250_000
    assert server.context_window(t) == 1_000_000
    assert json.loads((sandbox.config / "config.json").read_text()) == {"big_context_models": ["claude-opus-5-5"]}
    t.context_tokens = 10_000  # after /compact: still the 1M model
    assert server.context_window(t) == 1_000_000
    assert server.context_window(SimpleNamespace(context_tokens=10_000, model="claude-haiku-5")) == 200_000
    assert server.context_window(SimpleNamespace(context_tokens=300_000, model=None)) == 1_000_000


# ---- helpers -----------------------------------------------------------------------------------
def test_plain():
    md = "# Done\n\n**Fixed** the `parser` bug, see [the PR](https://x/1).\n\n```py\ncode\n```\n__ok__"
    assert plain(md) == "Done Fixed the parser bug, see the PR. [code] ok"
    assert plain("x" * 300) == "x" * 279 + "…"


def test_clean_name():
    assert clean_name("  my\n\tsession \x1b[31m name\x07 ") == "my session [31m name"
    assert clean_name(None) == "" and clean_name(42) == "42"


def test_tool_preview():
    assert tool_preview({"name": "Bash", "summary": "List", "detail": "ls -la"}) == {
        "name": "Bash", "summary": "List", "detail": "ls -la"}
    assert len(tool_preview({"name": "Bash", "detail": "x" * 2000})["detail"]) == 1500
    t = tool_preview({"name": "Edit", "summary": "a.kt", "diff": ["-a", "+b", "@"], "add": 1, "del": 1})
    assert t == {"name": "Edit", "summary": "a.kt", "diff": ["-a", "+b"], "add": 1, "del": 1}
    diff = [f"+{i}" for i in range(300)] + ["@50 more lines"]
    t = tool_preview({"name": "Write", "summary": "", "diff": diff, "add": 350, "del": 0})
    assert len(t["diff"]) == server.NOTIFY_DIFF_LINES + 1 and t["diff"][-1] == "@310 more lines"
    assert len(tool_preview({"name": "Edit", "diff": ["+" + "y" * 500]})["diff"][0]) == 200


def test_parent_pid():
    assert parent_pid(os.getpid()) == os.getppid()
    assert parent_pid(2**22 + 1) is None
    assert parent_pid(None) is None


def test_pid_alive():
    assert server.pid_alive(os.getpid()) and not server.pid_alive(None) and not server.pid_alive(2**22 + 1)
    assert server.pid_alive(1)  # someone else's process: EPERM still means alive


async def test_websocket_hello_sessions_and_ping(api, bridge, sandbox):
    build_apk(sandbox.apk, version=3)
    ws = await api.ws_connect("/api/ws")
    hello = await ws.receive_json()
    assert hello["t"] == "hello" and hello["app"]["versionCode"] == 3 and hello["notify_id"] == bridge.next_notify_id
    assert (await ws.receive_json())["t"] == "sessions"
    await ws.send_str("not json")
    await ws.send_json({"t": "ping"})
    assert await ws.receive_json() == {"t": "pong"}
    await ws.close()
    await until(lambda: not bridge.clients, what="client to go")


def test_the_newer_of_release_and_debug_is_offered(bridge, sandbox):
    build_apk(sandbox.apk / "debug", version=20, data=b"debug")
    build_apk(sandbox.apk / "release", version=21, data=b"release")
    info, path = bridge.app_build()
    assert info["versionCode"] == 21 and path.endswith("/release/app-debug.apk")
    build_apk(sandbox.apk / "debug", version=22, data=b"newer debug")
    assert bridge.app_build()[0]["versionCode"] == 22
