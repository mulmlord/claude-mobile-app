"""Shared fixtures.

Nothing here may reach the owner's real setup: HOME and the XDG dirs point into a
throwaway sandbox *before* the bridge modules are imported (their path constants are
computed at import time), every test then gets its own temp home on top of that, and
real subprocesses (gh, git, terminals, systemd-inhibit, systemctl) are refused.
"""

from __future__ import annotations

import asyncio
import atexit
import os
import shutil
import subprocess
import tempfile
import time
from types import SimpleNamespace

_SANDBOX = tempfile.mkdtemp(prefix="cmb-tests-")
atexit.register(shutil.rmtree, _SANDBOX, ignore_errors=True)
for _var, _sub in (("HOME", "home"), ("XDG_RUNTIME_DIR", "run"),
                   ("XDG_CONFIG_HOME", "config"), ("XDG_CACHE_HOME", "cache")):
    os.makedirs(os.path.join(_SANDBOX, _sub), exist_ok=True)
    os.environ[_var] = os.path.join(_SANDBOX, _sub)

import pytest  # noqa: E402

from claude_bridge import github, launcher, server  # noqa: E402
from helpers import FakeWrapper, until  # noqa: E402

for _path in (launcher.CLAUDE_DIR, server.CLAUDE_DIR, server.CONFIG_DIR, server.RUNTIME, server.UPLOADS):
    if not _path.startswith(_SANDBOX):  # imported before this file ran: refuse to go near the real dirs
        raise RuntimeError(f"claude_bridge was imported before the test sandbox was set up ({_path})")

TOKEN = "test-token-0123456789"
_real_sleep = asyncio.sleep


async def _no_subprocess(*argv, **kw):
    raise AssertionError(f"test tried to start a real process: {argv!r}")


def _no_subprocess_sync(*argv, **kw):
    raise AssertionError(f"test tried to run a real process: {argv!r}")


@pytest.fixture(autouse=True)
def sandbox(tmp_path, monkeypatch):
    """A fresh fake home (with ~/.claude), config, runtime, cache and APK dir per test."""
    root = tmp_path.resolve()
    home = root / "home"
    claude = home / ".claude"
    (claude / "projects").mkdir(parents=True)
    (claude / "sessions").mkdir()
    for d in ("config", "run", "cache", "apk", "tmp"):
        (root / d).mkdir()
    monkeypatch.setenv("HOME", str(home))
    monkeypatch.setattr(launcher, "CLAUDE_DIR", str(claude))
    monkeypatch.setattr(server, "CLAUDE_DIR", str(claude))
    # The test home itself lives under /tmp; a separate fake /tmp keeps the filter testable.
    monkeypatch.setattr(launcher, "TMP_PREFIX", str(root / "tmp") + "/")
    monkeypatch.setattr(server, "CONFIG_DIR", str(root / "config"))
    monkeypatch.setattr(server, "RUNTIME", str(root / "run"))
    monkeypatch.setattr(server, "STATE_FILE", str(root / "run" / "state.json"))
    monkeypatch.setattr(server, "UPLOADS", str(root / "cache" / "uploads"))
    monkeypatch.setattr(server, "APK_DIR", str(root / "apk"))
    monkeypatch.setattr(server, "BIG_CONTEXT_MODELS", set())
    monkeypatch.setattr(github, "_env", None)
    # No real processes: anything that slips past a test's own fakes fails loudly.
    monkeypatch.setattr(asyncio, "create_subprocess_exec", _no_subprocess)
    monkeypatch.setattr(subprocess, "run", _no_subprocess_sync)
    return SimpleNamespace(root=root, home=home, claude=claude, projects=claude / "projects",
                           sessions=claude / "sessions", config=root / "config", run=root / "run",
                           uploads=root / "cache" / "uploads", apk=root / "apk", tmp=root / "tmp")


# ---- a stand-in for systemd-inhibit ---------------------------------------------------
class FakeProc:
    """An asyncio.subprocess.Process that never existed."""
    _next_pid = 900000

    def __init__(self, argv, hang: bool = False):
        FakeProc._next_pid += 1
        self.pid = FakeProc._next_pid
        self.argv = list(argv)
        self.returncode = None
        self.hang = hang

    async def wait(self):
        if self.hang:  # ignores SIGTERM: makes Awake._stop's wait_for time out right away
            raise asyncio.TimeoutError
        if self.returncode is None:
            self.returncode = -15
        return self.returncode


@pytest.fixture
def inhibitor(monkeypatch):
    """Lets launcher.Awake "start" systemd-inhibit: records the processes and the
    signals sent to their process groups, runs nothing."""
    st = SimpleNamespace(procs=[], signals=[], which=True, hang=False)

    async def fake_exec(*argv, **kw):
        assert argv[0] == "/usr/bin/systemd-inhibit", argv
        assert kw.get("start_new_session") is True
        p = FakeProc(argv, hang=st.hang)
        st.procs.append(p)
        return p

    def fake_killpg(pid, sig):
        st.signals.append((pid, sig))
        for p in st.procs:
            if p.pid == pid and not p.hang:
                p.returncode = -sig

    real_which = shutil.which
    monkeypatch.setattr(launcher.shutil, "which",
                        lambda name, *a, **k: ("/usr/bin/systemd-inhibit" if st.which else None)
                        if name == "systemd-inhibit" else real_which(name, *a, **k))
    monkeypatch.setattr(asyncio, "create_subprocess_exec", fake_exec)
    monkeypatch.setattr(launcher.os, "killpg", fake_killpg)
    return st


class FakeClock:
    def __init__(self, now: float = 1_800_000_000.0):
        self.now = now

    def time(self) -> float:
        return self.now

    def monotonic(self) -> float:
        return time.monotonic()

    def advance(self, s: float):
        self.now += s


@pytest.fixture
def clock(monkeypatch):
    c = FakeClock()
    monkeypatch.setattr(launcher, "time", c)
    return c


# ---- the bridge -----------------------------------------------------------------------
class FastAsyncio:
    """The server module's view of asyncio, with its pacing sleeps (typing delays,
    "let the transcript land", the 1 s after Esc) cut down to a yield. Tests can hook
    into a sleep to change the world in between, like a real terminal would."""

    def __init__(self):
        self.on_sleep = []
        self.slept = []
        self.wait_cap = 0.05  # api_spawn waits 15 s for the new wrapper to say hello

    def __getattr__(self, name):
        return getattr(asyncio, name)

    async def sleep(self, delay, result=None):
        self.slept.append(delay)
        for hook in list(self.on_sleep):
            hook(delay)
        await _real_sleep(0)
        return result

    async def wait_for(self, aw, timeout):
        return await asyncio.wait_for(aw, min(timeout, self.wait_cap))


@pytest.fixture
def fast(monkeypatch):
    f = FastAsyncio()
    monkeypatch.setattr(server, "asyncio", f)
    return f


@pytest.fixture
def cfg():
    return {"host": "127.0.0.1", "port": 0, "token": TOKEN, "quiet_after_local_input": 45, "keep_awake": "working"}


@pytest.fixture
async def bridge(cfg, fast):
    return server.Bridge(cfg)


@pytest.fixture
async def api(aiohttp_client, bridge):
    """The phone-facing API, signed in."""
    return await aiohttp_client(server.make_app(bridge), headers={"Authorization": f"Bearer {TOKEN}"})


@pytest.fixture
async def hooks(aiohttp_client, bridge):
    """The hook endpoint (a unix socket in production; plain HTTP is the same app)."""
    client = await aiohttp_client(server.make_hook_app(bridge))

    async def post(sid: str, **data):
        r = await client.post("/hook", json=data, headers={"X-Claude-Mobile-Id": sid})
        assert r.status == 200
        return await r.json()

    client.fire = post
    return client


@pytest.fixture
async def wrappers(bridge, sandbox):
    """connect(sid, **hello) -> FakeWrapper registered with the bridge over the real
    unix-socket protocol."""
    d = tempfile.mkdtemp(prefix="cmb-")  # short: unix socket paths are limited to ~108 bytes
    path = os.path.join(d, "wrappers.sock")
    srv = await asyncio.start_unix_server(bridge.handle_wrapper, path=path)
    made: list[FakeWrapper] = []
    proj = sandbox.home / "proj"
    proj.mkdir(exist_ok=True)

    async def connect(sid: str = "w1", wait: bool = True, **hello) -> FakeWrapper:
        w = await FakeWrapper.connect(path)
        made.append(w)
        info = {"id": sid, "pid": 2**22 + 7, "wrapper_pid": os.getpid(), "cwd": str(proj), "argv": [],
                "started": time.time(), "rows": 24, "cols": 80, "tty": "/dev/pts/9", "spawn": ""}
        info.update(hello)
        await w.hello(**info)
        if wait:
            await until(lambda: sid in bridge.sessions and bridge.sessions[sid].writer is not None,
                        what=f"wrapper {sid} to register")
            w.session = bridge.sessions[sid]
        return w

    connect.path = path
    yield connect
    for w in made:
        await w.close()
    srv.close()
    if hasattr(srv, "close_clients"):
        srv.close_clients()
    await srv.wait_closed()
    shutil.rmtree(d, ignore_errors=True)
