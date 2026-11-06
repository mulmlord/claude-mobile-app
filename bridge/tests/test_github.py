import asyncio
import json
from types import SimpleNamespace

import pytest
from aiohttp import web

from claude_bridge import github, launcher
from claude_bridge.github import GhError, check_items, checks_state, parse_diff


# ---- CI state ----------------------------------------------------------------------
def run_(conclusion=None, status="COMPLETED", **kw):
    return {"__typename": "CheckRun", "name": "build", "status": status, "conclusion": conclusion, **kw}


def ctx(state, **kw):
    return {"__typename": "StatusContext", "context": "ci/legacy", "state": state, **kw}


@pytest.mark.parametrize("rollup,want", [
    (None, None),
    ([], None),
    ([run_("SUCCESS"), run_("SKIPPED"), run_("NEUTRAL")], "SUCCESS"),
    ([run_("SUCCESS"), run_("FAILURE")], "FAILURE"),
    ([run_("SUCCESS"), run_(None, status="IN_PROGRESS")], "PENDING"),
    ([run_(None, status="QUEUED"), run_("TIMED_OUT")], "FAILURE"),  # a failure outranks pending
    ([ctx("SUCCESS"), run_("SUCCESS")], "SUCCESS"),
    ([ctx("PENDING")], "PENDING"),
    ([ctx("ERROR")], "FAILURE"),
    ([{"state": "EXPECTED"}], "PENDING"),          # no __typename, but status-context shaped
    ([run_("ACTION_REQUIRED")], "FAILURE"),
    ([run_("STARTUP_FAILURE")], "FAILURE"),
    ([run_("")], "PENDING"),
])
def test_checks_state(rollup, want):
    assert checks_state(rollup) == want


def test_check_items():
    rollup = [
        run_("SUCCESS", name="test", workflowName="CI", detailsUrl="https://ci/1"),
        run_(None, status="IN_PROGRESS", name="", workflowName="Deploy"),
        run_(None, name=None),
        ctx("FAILURE", targetUrl="https://legacy/2"),
        {"state": "pending"},
    ]
    assert check_items(rollup) == [
        {"name": "test", "state": "SUCCESS", "workflow": "CI", "url": "https://ci/1"},
        {"name": "Deploy", "state": "PENDING", "workflow": "Deploy", "url": ""},
        {"name": "check", "state": "PENDING", "workflow": "", "url": ""},
        {"name": "ci/legacy", "state": "FAILURE", "workflow": "", "url": "https://legacy/2"},
        {"name": "status", "state": "PENDING", "workflow": "", "url": ""},
    ]
    assert check_items(None) == []


# ---- diffs --------------------------------------------------------------------------
DIFF = """\
diff --git a/src/app.py b/src/app.py
index 83db48f..bf269f4 100644
--- a/src/app.py
+++ b/src/app.py
@@ -1,4 +1,4 @@
 import os
-x = 1
+x = 2

@@ -20,3 +20,4 @@ def f():
     return 1
+    # done
\\ No newline at end of file
diff --git a/old name.txt b/new name.txt
similarity index 100%
rename from old name.txt
rename to new name.txt
diff --git a/img.png b/img.png
new file mode 100644
index 0000000..e69de29
Binary files /dev/null and b/img.png differ
diff --git a/run.sh b/run.sh
old mode 100644
new mode 100755
diff --git a/gone.txt b/gone.txt
deleted file mode 100644
index e69de29..0000000
--- a/gone.txt
+++ /dev/null
@@ -1,2 +0,0 @@
-bye
-now
"""


def test_parse_diff_files():
    files = parse_diff(DIFF)
    assert [f["path"] for f in files] == ["src/app.py", "new name.txt", "img.png", "run.sh", "gone.txt"]
    app, renamed, img, mode, gone = files
    assert app == {"path": "src/app.py", "add": 2, "del": 1,
                   "lines": [" import os", "-x = 1", "+x = 2", " ", "@", "     return 1", "+    # done"]}
    assert renamed == {"path": "new name.txt", "lines": [], "add": 0, "del": 0}
    assert img["lines"] == ["@binary file"]
    assert mode["lines"] == []
    assert gone == {"path": "gone.txt", "lines": ["-bye", "-now"], "add": 0, "del": 2}


def test_parse_diff_ignores_text_before_the_first_file():
    assert parse_diff("From abc\nSubject: x\n\n+not a file\n") == []
    assert parse_diff("") == []


def test_removed_lines_that_look_like_headers_are_kept():
    """SQL/Lua/Haskell comments: removing "-- note" gives the diff line "--- note"."""
    diff = ("diff --git a/q.sql b/q.sql\nindex 1..2 100644\n--- a/q.sql\n+++ b/q.sql\n"
            "@@ -1,3 +1,3 @@\n--- old note\n+-- new note\n++++ counter\n SELECT 1;\n")
    (f,) = parse_diff(diff)
    assert f["lines"] == ["--- old note", "+-- new note", "++++ counter", " SELECT 1;"]
    assert (f["add"], f["del"]) == (2, 1)


def test_parse_diff_caps_per_file_and_in_total():
    big = "".join(f"+line {i}\n" for i in range(1000))
    one = f"diff --git a/a b/a\n@@ -0,0 +1,1000 @@\n{big}"
    (f,) = parse_diff(one)
    assert f["add"] == 1000
    assert len(f["lines"]) == github.MAX_FILE_LINES + 1
    assert f["lines"][-1] == f"@{1000 - github.MAX_FILE_LINES} more lines"

    many = "".join(f"diff --git a/f{n} b/f{n}\n@@ -0,0 +1,1000 @@\n{big}" for n in range(10))
    files = parse_diff(many)
    shown = sum(len([ln for ln in f["lines"] if not ln.startswith("@")]) for f in files)
    assert shown == github.MAX_DIFF_LINES
    assert all(f["add"] == 1000 for f in files)
    (last,) = files[-1]["lines"]  # nothing left to show, just the count
    assert last.startswith("@") and last.endswith(" more lines")


def test_parse_diff_clips_long_lines():
    (f,) = parse_diff("diff --git a/a b/a\n@@ -1 +1 @@\n+" + "x" * 1000 + "\n")
    assert len(f["lines"][0]) == 400


# ---- local clones ----------------------------------------------------------------------
def clone(sandbox, rel, remotes: dict[str, str]):
    d = sandbox.home / rel
    (d / ".git").mkdir(parents=True)
    cfg = "[core]\n\tbare = false\n"
    for name, url in remotes.items():
        cfg += f'[remote "{name}"]\n\turl = {url}\n\tfetch = +refs/heads/*:refs/remotes/{name}/*\n'
    (d / ".git" / "config").write_text(cfg)
    return str(d)


def test_local_finds_github_clones(sandbox, monkeypatch):
    a = clone(sandbox, "Projects/app", {"origin": "https://github.com/Me/App.git"})
    b = clone(sandbox, "Projects/lib", {"upstream": "git@github.com:other/lib.git",
                                        "origin": "git@github.com:me/lib"})
    clone(sandbox, "Projects/gitlab", {"origin": "https://gitlab.com/me/x.git"})
    clone(sandbox, "Projects/noorigin", {"upstream": "https://github.com/me/up.git"})
    c = clone(sandbox, "Work", {"origin": "ssh://git@github.com/me/work/"})
    (sandbox.home / "Projects" / "plain").mkdir()
    deep = clone(sandbox, "deep/nested/thing", {"origin": "https://github.com/me/thing"})
    hist = launcher.History()
    monkeypatch.setattr(hist, "projects", lambda live, running: [{"cwd": deep}, {"cwd": "/etc"}])
    g = github.GitHub(hist)
    assert g.local() == {"me/app": a, "me/lib": b, "me/work": c, "me/thing": deep}
    assert g.path_for("ME/App") == a and g.path_for("me/none") is None
    # cached for a minute
    clone(sandbox, "Projects/later", {"origin": "https://github.com/me/later"})
    assert "me/later" not in g.local()
    g.local_cache = (0.0, {})
    assert "me/later" in g.local()


def test_repo_for_cwd():
    g = github.GitHub(launcher.History())
    g.local_cache = (1e18, {"me/app": "/h/Projects/app"})
    assert g.repo_for_cwd("/h/Projects/app") == "me/app"
    assert g.repo_for_cwd("/h/Projects/app/src") == "me/app"
    assert g.repo_for_cwd("/h/Projects/app2") is None
    assert g.repo_for_cwd("") is None


# ---- run() ----------------------------------------------------------------------------
class GhProc:
    def __init__(self, out=b"", err=b"", code=0, hang=False):
        self.out, self.err, self.code, self.hang = out, err, code, hang
        self.returncode = None
        self.input = None
        self.killed = False

    async def communicate(self, data=None):
        self.input = data
        if self.hang:
            await asyncio.sleep(10)
        self.returncode = self.code
        return self.out, self.err

    def kill(self):
        self.killed = True


@pytest.fixture
def fake_gh(tmp_path, monkeypatch):
    bindir = tmp_path / "bin"
    bindir.mkdir()
    for name in ("gh", "git"):
        (bindir / name).write_text("#!/bin/sh\nexit 99\n")
        (bindir / name).chmod(0o755)
    monkeypatch.setattr(launcher, "desktop_env", lambda: {"PATH": str(bindir), "SSH_AUTH_SOCK": "/agent"})
    st = SimpleNamespace(proc=GhProc(b"out\n"), calls=[], bindir=bindir)

    async def fake_exec(*argv, **kw):
        st.calls.append((argv, kw))
        return st.proc
    monkeypatch.setattr(asyncio, "create_subprocess_exec", fake_exec)
    return st


async def test_run_passes_argv_stdin_and_a_quiet_env(fake_gh):
    out = await github.run(["gh", "pr", "comment", "1", "--body-file", "-"], cwd="/x", stdin="héllo")
    assert out == "out\n"
    (argv, kw), = fake_gh.calls
    assert argv == (str(fake_gh.bindir / "gh"), "pr", "comment", "1", "--body-file", "-")
    assert fake_gh.proc.input == "héllo".encode()
    assert kw["cwd"] == "/x" and kw["stdin"] == asyncio.subprocess.PIPE
    env = kw["env"]
    assert env["GH_PROMPT_DISABLED"] == "1" and env["GIT_TERMINAL_PROMPT"] == "0" and env["SSH_AUTH_SOCK"] == "/agent"


async def test_run_without_stdin_uses_devnull(fake_gh):
    await github.run(["git", "status"])
    (_, kw), = fake_gh.calls
    assert kw["stdin"] == asyncio.subprocess.DEVNULL and fake_gh.proc.input is None


async def test_run_errors(fake_gh):
    with pytest.raises(GhError, match="hub is not installed"):
        await github.run(["hub", "x"])
    fake_gh.proc = GhProc(b"", b"x" * 1000 + b"HTTP 404: Not Found", code=1)
    with pytest.raises(GhError) as e:
        await github.run(["gh", "pr", "view"])
    assert str(e.value).endswith("HTTP 404: Not Found") and len(str(e.value)) == 700
    fake_gh.proc = GhProc(code=4)
    with pytest.raises(GhError, match=r"gh failed \(4\)"):
        await github.run(["gh", "x"])
    fake_gh.proc = GhProc(hang=True)
    with pytest.raises(GhError, match="timed out"):
        await github.run(["gh", "x"], timeout=0.01)
    assert fake_gh.proc.killed


async def test_gh_json(fake_gh):
    fake_gh.proc = GhProc(b'{"a": 1}')
    assert await github.gh_json("api", "x") == {"a": 1}
    fake_gh.proc = GhProc(b"  \n")
    assert await github.gh_json("api", "x") is None


# ---- endpoints, with run() faked ---------------------------------------------------------
@pytest.fixture
async def gh(aiohttp_client, monkeypatch, sandbox):
    st = SimpleNamespace(calls=[], replies=[])

    async def fake_run(argv, cwd=None, stdin=None, timeout=40):
        st.calls.append(SimpleNamespace(argv=list(argv), cwd=cwd, stdin=stdin))
        for prefix, out in st.replies:
            if list(argv[:len(prefix)]) == prefix:
                if isinstance(out, Exception):
                    raise out
                return out
        return ""

    def reply(prefix, out):
        st.replies.insert(0, (prefix, out if isinstance(out, (str, Exception)) else json.dumps(out)))

    monkeypatch.setattr(github, "run", fake_run)
    st.g = github.GitHub(launcher.History())
    st.g.local_cache = (1e18, {})
    app = web.Application(middlewares=[github.errors_mw])
    st.g.routes(app)
    st.client = await aiohttp_client(app)
    st.reply = reply
    return st


@pytest.mark.parametrize("path", [
    "/api/gh/bad%20owner/x/prs",
    "/api/gh/o/r;rm/issues",
    "/api/gh/%C3%BCber/x/runs",
    "/api/gh/o/r$(id)/local",
])
async def test_bad_repository_names(gh, path):
    r = await gh.client.get(path)
    assert r.status == 400 and await r.text() == "bad repository name"
    assert gh.calls == []


@pytest.mark.parametrize("path", [
    "/api/gh/o/r/prs/abc",
    "/api/gh/o/r/prs/-1",
    "/api/gh/o/r/prs/1.5",
    "/api/gh/o/r/prs/%C2%B2",          # superscript two: str.isdigit() says yes
    "/api/gh/o/r/issues/%D9%A3",       # Arabic-Indic three
    "/api/gh/o/r/runs/12x/log",
])
async def test_bad_numbers(gh, path):
    r = await gh.client.get(path)
    assert r.status == 400 and await r.text() == "bad number"
    assert gh.calls == []


@pytest.mark.xfail(reason="REPO_RE accepts an owner starting with '-', so `gh repo clone -x/y …` gets an "
                          "option-like positional (GitHub owners can't start with '-'); reported, not fixed",
                   strict=False)
async def test_owner_cannot_look_like_an_option(gh):
    r = await gh.client.post("/api/gh/-u/x/local/clone")
    assert r.status == 400


@pytest.mark.parametrize("path,body,msg", [
    ("/prs/3/merge", {"method": "fast-forward"}, "method must be squash, merge or rebase"),
    ("/prs/3/review", {"event": "dismiss", "body": "x"}, "event must be approve, request_changes or comment"),
    ("/prs/3/review", {}, "event must be approve, request_changes or comment"),
    ("/prs/3/review", {"event": "comment", "body": "  "}, "body is empty"),
    ("/prs/3/state", {"action": "delete"}, "action must be close, reopen or ready"),
    ("/prs/3/comment", {"body": ""}, "body is empty"),
    ("/prs/3/comment", ["not", "a", "dict"], "body is empty"),
    ("/issues/3/state", {"action": "ready"}, "action must be close or reopen"),
    ("/issues/3/comment", {}, "body is empty"),
    ("/issues", {"title": " ", "body": "x"}, "title is empty"),
])
async def test_bad_enums_and_empty_text(gh, path, body, msg):
    r = await gh.client.post("/api/gh/o/r" + path, json=body)
    assert r.status == 400 and await r.text() == msg
    assert gh.calls == []


async def test_bad_json_body_counts_as_empty(gh):
    r = await gh.client.post("/api/gh/o/r/prs/3/comment", data=b"{nope", headers={"Content-Type": "application/json"})
    assert r.status == 400 and await r.text() == "body is empty"


async def test_free_text_goes_through_stdin(gh):
    body = "  --delete-branch\n$(rm -rf ~) `x`  "
    r = await gh.client.post("/api/gh/o/r/prs/7/comment", json={"body": body})
    assert r.status == 200
    (c,) = gh.calls
    assert c.argv == ["gh", "pr", "comment", "7", "-R", "o/r", "--body-file", "-"]
    assert c.stdin == body.strip()

    await gh.client.post("/api/gh/o/r/issues/8/comment", json={"body": "-x"})
    assert gh.calls[-1].argv == ["gh", "issue", "comment", "8", "-R", "o/r", "--body-file", "-"]
    assert gh.calls[-1].stdin == "-x"


async def test_reviews(gh):
    r = await gh.client.post("/api/gh/o/r/prs/7/review", json={"event": "approve"})
    assert r.status == 200
    assert gh.calls[-1].argv == ["gh", "pr", "review", "7", "-R", "o/r", "--approve", "--body-file", "-"]
    assert gh.calls[-1].stdin == ""
    await gh.client.post("/api/gh/o/r/prs/7/review", json={"event": "request_changes", "body": "pls"})
    assert gh.calls[-1].argv[6] == "--request-changes" and gh.calls[-1].stdin == "pls"


async def test_issue_create_title_is_one_flag_value(gh):
    gh.reply(["gh", "issue", "create"], "https://github.com/o/r/issues/42\n")
    gh.g.repos_cache = (1e18, {"stale": True})
    r = await gh.client.post("/api/gh/o/r/issues", json={"title": "--help\nme " + "t" * 300, "body": "line\n--flag"})
    assert await r.json() == {"ok": True, "number": 42, "url": "https://github.com/o/r/issues/42"}
    (c,) = gh.calls
    title = ("--help me " + "t" * 300)[:250]
    assert c.argv == ["gh", "issue", "create", "-R", "o/r", f"--title={title}", "--body-file", "-"]
    assert c.stdin == "line\n--flag"
    assert gh.g.repos_cache is None


async def test_text_is_capped(gh):
    await gh.client.post("/api/gh/o/r/prs/1/comment", json={"body": "b" * 70_000})
    assert len(gh.calls[-1].stdin) == 60_000


async def test_merge_and_state(gh):
    gh.reply(["gh", "pr", "merge"], "Merged\n")
    r = await gh.client.post("/api/gh/o/r/prs/5/merge", json={"method": "rebase", "delete_branch": True})
    assert await r.json() == {"ok": True, "message": "Merged"}
    assert gh.calls[-1].argv == ["gh", "pr", "merge", "5", "-R", "o/r", "--rebase", "--delete-branch"]
    await gh.client.post("/api/gh/o/r/prs/5/merge", json={})
    assert gh.calls[-1].argv == ["gh", "pr", "merge", "5", "-R", "o/r", "--squash"]
    await gh.client.post("/api/gh/o/r/prs/5/state", json={"action": "ready"})
    assert gh.calls[-1].argv == ["gh", "pr", "ready", "5", "-R", "o/r"]
    await gh.client.post("/api/gh/o/r/issues/6/state", json={"action": "reopen"})
    assert gh.calls[-1].argv == ["gh", "issue", "reopen", "6", "-R", "o/r"]


async def test_list_states_fall_back_to_open(gh):
    gh.reply(["gh", "pr", "list"], [{"number": 1, "title": "T", "author": {"login": "al"}, "isDraft": True,
                                     "statusCheckRollup": [run_("FAILURE")], "additions": 3}])
    r = await gh.client.get("/api/gh/o/r/prs?state=everything")
    (pr,) = (await r.json())["prs"]
    assert pr["number"] == 1 and pr["author"] == "al" and pr["draft"] is True and pr["checks"] == "FAILURE"
    assert pr["add"] == 3 and pr["del"] == 0
    assert gh.calls[-1].argv[:7] == ["gh", "pr", "list", "-R", "o/r", "--state", "open"]
    await gh.client.get("/api/gh/o/r/prs?state=merged")
    assert gh.calls[-1].argv[6] == "merged"
    await gh.client.get("/api/gh/o/r/issues?state=merged")
    assert gh.calls[-1].argv[:7] == ["gh", "issue", "list", "-R", "o/r", "--state", "open"]


async def test_pr_view_with_diff_and_conversation(gh):
    gh.reply(["gh", "pr", "view"], {
        "number": 9, "title": "Add x", "body": None, "author": {"login": "me"}, "state": "OPEN",
        "statusCheckRollup": [run_("SUCCESS", name="t")],
        "comments": [{"author": {"login": "a"}, "body": "c1", "createdAt": "2026-01-02"}],
        "reviews": [{"author": {"login": "b"}, "body": "", "state": "APPROVED", "submittedAt": "2026-01-01"},
                    {"author": {"login": "c"}, "body": "", "state": "COMMENTED", "submittedAt": "2026-01-03"}],
    })
    gh.reply(["gh", "pr", "diff"], DIFF)
    d = await (await gh.client.get("/api/gh/o/r/prs/9")).json()
    assert d["number"] == 9 and d["body"] == "" and d["checksState"] == "SUCCESS"
    assert [f["path"] for f in d["files"]][:1] == ["src/app.py"] and d["diffError"] == ""
    assert [(c["kind"], c["author"]) for c in d["conversation"]] == [("review", "b"), ("comment", "a")]


async def test_pr_view_survives_a_failing_diff(gh):
    gh.reply(["gh", "pr", "view"], {"number": 9, "title": "x"})
    gh.reply(["gh", "pr", "diff"], GhError("diff too large"))
    d = await (await gh.client.get("/api/gh/o/r/prs/9")).json()
    assert d["files"] == [] and d["diffError"] == "diff too large"


async def test_gh_failures_become_502(gh):
    gh.reply(["gh", "issue", "view"], GhError("could not resolve to an Issue"))
    r = await gh.client.get("/api/gh/o/r/issues/1")
    assert r.status == 502 and await r.text() == "could not resolve to an Issue"


async def test_run_log(gh):
    gh.reply(["gh", "run", "view"], "\n".join(f"l{i}" for i in range(300)))
    d = await (await gh.client.get("/api/gh/o/r/runs/123/log")).json()
    assert d["truncated"] is True and d["log"].splitlines()[0] == "l50"
    assert gh.calls[-1].argv == ["gh", "run", "view", "123", "-R", "o/r", "--log-failed"]
    gh.reply(["gh", "run", "view"], GhError("no failed jobs"))
    d = await (await gh.client.get("/api/gh/o/r/runs/123/log")).json()
    assert d == {"log": "(no failed-step log: no failed jobs)", "truncated": False}
    await gh.client.post("/api/gh/o/r/runs/123/rerun")
    assert gh.calls[-1].argv == ["gh", "run", "rerun", "123", "-R", "o/r", "--failed"]


async def test_repos_lists_yours_and_your_clones(gh, monkeypatch):
    node = lambda name, pushed, ci=None: {  # noqa: E731
        "nameWithOwner": name, "pushedAt": pushed, "pullRequests": {"totalCount": 2}, "issues": {"totalCount": 1},
        "defaultBranchRef": {"target": {"statusCheckRollup": {"state": ci} if ci else None}}}
    gh.g.local_cache = (1e18, {"me/a": "/h/a", "other/lib": "/h/lib"})

    async def graphql(argv, cwd=None, stdin=None, timeout=40):
        gh.calls.append(SimpleNamespace(argv=argv, cwd=cwd, stdin=stdin))
        q = argv[-1]
        if "viewer" in q:
            return json.dumps({"data": {"viewer": {"login": "me", "repositories": {
                "nodes": [node("me/a", "2026-01-01", "SUCCESS"), None]}}}})
        assert 'repository(owner: "other", name: "lib")' in q
        return json.dumps({"data": {"r0": node("other/lib", "2026-02-01")}})
    monkeypatch.setattr(github, "run", graphql)
    d = await (await gh.client.get("/api/gh/repos")).json()
    assert d["login"] == "me"
    assert [(r["repo"], r["ci"], r["local"], r["prs"]) for r in d["repos"]] == [
        ("other/lib", None, "/h/lib", 2), ("me/a", "SUCCESS", "/h/a", 2)]
    n = len(gh.calls)
    await gh.client.get("/api/gh/repos")
    assert len(gh.calls) == n  # cached
    await gh.client.get("/api/gh/repos?fresh=1")
    assert len(gh.calls) > n


# ---- the clone on the laptop ------------------------------------------------------------
STATUS = """\
# branch.oid 1234567890abcdef1234567890abcdef12345678
# branch.head feature/x
# branch.upstream origin/feature/x
# branch.ab +2 -1
1 .M N... 100644 100644 100644 abc1 abc1 src/app.py
1 A. N... 000000 100644 100644 0000 abc2 new file.txt
2 R. N... 100644 100644 100644 abc3 abc3 R100 renamed name.txt\told name.txt
? untracked dir/thing.txt
"""


@pytest.fixture
def cloned(gh, sandbox):
    path = str(sandbox.home / "Projects" / "r")
    gh.g.local_cache = (1e18, {"o/r": path})
    return path


async def test_local_status_not_cloned(gh):
    d = await (await gh.client.get("/api/gh/o/myrepo/local")).json()
    assert d == {"cloned": False, "suggested": "~/Projects/myrepo"}
    assert gh.calls == []


async def test_local_status_parses_porcelain_v2(gh, cloned):
    gh.reply(["git", "status"], STATUS)
    gh.reply(["git", "log"], "abc1234\tFix bug\t2 hours ago\tAlice\n\ndef5678\tInit\t3 days ago\tBob\n")
    d = await (await gh.client.get("/api/gh/O/R/local")).json()
    assert d["cloned"] and d["cwd"] == cloned and d["path"] == "~/Projects/r"
    assert (d["branch"], d["upstream"], d["ahead"], d["behind"]) == ("feature/x", "origin/feature/x", 2, 1)
    assert d["changes"] == [
        {"status": "M", "path": "src/app.py"},
        {"status": "A", "path": "new file.txt"},
        {"status": "R", "path": "renamed name.txt"},
        {"status": "?", "path": "untracked dir/thing.txt"},
    ]
    assert d["changeCount"] == 4
    assert d["commits"] == [{"hash": "abc1234", "subject": "Fix bug", "when": "2 hours ago", "author": "Alice"},
                            {"hash": "def5678", "subject": "Init", "when": "3 days ago", "author": "Bob"}]
    assert {c.cwd for c in gh.calls} == {cloned}
    assert gh.calls[0].argv == ["git", "status", "--porcelain=v2", "--branch"]


async def test_local_status_unmerged_path_with_spaces(gh, cloned):
    gh.reply(["git", "status"], "# branch.head main\n"
                                "u UU N... 100644 100644 100644 100644 a1 a2 a3 my conflict file.txt\n")
    d = await (await gh.client.get("/api/gh/o/r/local")).json()
    assert d["changes"] == [{"status": "U", "path": "my conflict file.txt"}]
    assert d["upstream"] == "" and d["ahead"] == 0


async def test_local_status_caps_the_list(gh, cloned):
    gh.reply(["git", "status"], "".join(f"? f{i}\n" for i in range(100)))
    d = await (await gh.client.get("/api/gh/o/r/local")).json()
    assert len(d["changes"]) == 60 and d["changeCount"] == 100


async def test_local_actions(gh, cloned):
    r = await gh.client.post("/api/gh/o/r/local/rm-rf")
    assert r.status == 404
    gh.reply(["git", "status"], "# branch.head main\n")  # no upstream yet
    await gh.client.post("/api/gh/o/r/local/push")
    assert gh.calls[-1].argv == ["git", "push", "-u", "origin", "HEAD"] and gh.calls[-1].cwd == cloned
    gh.reply(["git", "status"], STATUS)
    await gh.client.post("/api/gh/o/r/local/push")
    assert gh.calls[-1].argv == ["git", "push"]
    gh.reply(["git", "pull"], "Already up to date.\n")
    d = await (await gh.client.post("/api/gh/o/r/local/pull")).json()
    assert d == {"ok": True, "message": "Already up to date."}
    assert gh.calls[-1].argv == ["git", "pull", "--ff-only"]
    d = await (await gh.client.post("/api/gh/o/r/local/pr")).json()
    assert d["message"] == "Done" and gh.calls[-1].argv == ["gh", "pr", "create", "--fill"]


async def test_local_actions_need_a_clone(gh):
    r = await gh.client.post("/api/gh/o/r/local/pull")
    assert r.status == 409 and gh.calls == []


async def test_clone(gh, sandbox):
    d = await (await gh.client.post("/api/gh/o/newrepo/local/clone")).json()
    dest = str(sandbox.home / "Projects" / "newrepo")
    assert d == {"ok": True, "message": f"Cloned to {dest}"}
    assert gh.calls[-1].argv == ["gh", "repo", "clone", "o/newrepo", dest]
    (sandbox.home / "Projects" / "taken").mkdir()
    r = await gh.client.post("/api/gh/o/taken/local/clone")
    assert r.status == 409 and "already exists" in await r.text()
    gh.g.local_cache = (1e18, {"o/have": "/somewhere"})
    r = await gh.client.post("/api/gh/o/have/local/clone")
    assert r.status == 409 and await r.text() == "Already cloned"
