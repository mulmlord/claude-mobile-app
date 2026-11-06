"""GitHub from the phone, through the laptop's `gh` and `git`.

The phone never holds a GitHub token: every call runs `gh` (already signed in on the
laptop) or `git` in a local clone. Arguments are passed as argv (no shell); free text
goes through stdin (`--body-file -`) or `--flag=value`, so nothing typed on the phone
can turn into an option.
"""

from __future__ import annotations

import asyncio
import glob
import json
import os
import re
import shutil
import tempfile
import time

from aiohttp import web

from . import launcher

REPO_RE = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
REMOTE_RE = re.compile(r"github\.com[:/]+([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+?)(?:\.git)?/?$")
MAX_FILE_LINES, MAX_DIFF_LINES = 400, 3000


class GhError(Exception):
    pass


_env: tuple[float, dict] | None = None


def _environ() -> dict:
    """Desktop env (ssh-agent, PATH with mise) plus switches that keep gh/git non-interactive."""
    global _env
    if not _env or time.time() - _env[0] > 300:
        env = launcher.desktop_env()
        env.update(GH_PROMPT_DISABLED="1", GH_NO_UPDATE_NOTIFIER="1", GH_PAGER="cat", PAGER="cat",
                   NO_COLOR="1", CLICOLOR="0", GIT_TERMINAL_PROMPT="0", GH_SPINNER_DISABLED="1")
        _env = (time.time(), env)
    return _env[1]


async def run(argv: list[str], cwd: str | None = None, stdin: str | None = None, timeout: float = 40) -> str:
    env = _environ()
    exe = shutil.which(argv[0], path=env.get("PATH"))
    if not exe:
        raise GhError(f"{argv[0]} is not installed on the laptop")
    proc = await asyncio.create_subprocess_exec(
        exe, *argv[1:], cwd=cwd or tempfile.gettempdir(), env=env,
        stdin=asyncio.subprocess.PIPE if stdin is not None else asyncio.subprocess.DEVNULL,
        stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE,
    )
    try:
        out, err = await asyncio.wait_for(proc.communicate(stdin.encode() if stdin is not None else None), timeout)
    except asyncio.TimeoutError:
        proc.kill()
        raise GhError(f"{argv[0]} timed out")
    if proc.returncode:
        msg = (err or out).decode(errors="replace").strip()
        raise GhError(msg[-700:] or f"{argv[0]} failed ({proc.returncode})")
    return out.decode(errors="replace")


async def gh_json(*args: str, **kw):
    out = await run(["gh", *args], **kw)
    return json.loads(out) if out.strip() else None


def checks_state(rollup) -> str | None:
    """statusCheckRollup list -> SUCCESS | FAILURE | PENDING | None."""
    if not rollup:
        return None
    states = []
    for c in rollup:
        if c.get("__typename") == "StatusContext" or "state" in c and "conclusion" not in c:
            states.append((c.get("state") or "").upper())
        elif (c.get("status") or "").upper() != "COMPLETED":
            states.append("PENDING")
        else:
            states.append((c.get("conclusion") or "").upper())
    if any(s in ("FAILURE", "ERROR", "TIMED_OUT", "ACTION_REQUIRED", "STARTUP_FAILURE") for s in states):
        return "FAILURE"
    if any(s in ("PENDING", "EXPECTED", "QUEUED", "IN_PROGRESS", "") for s in states):
        return "PENDING"
    return "SUCCESS"


def check_items(rollup) -> list[dict]:
    out = []
    for c in rollup or []:
        if "conclusion" in c or "status" in c:
            done = (c.get("status") or "").upper() == "COMPLETED"
            state = (c.get("conclusion") or "").upper() if done else "PENDING"
            out.append({"name": c.get("name") or c.get("workflowName") or "check", "state": state or "PENDING",
                        "workflow": c.get("workflowName") or "", "url": c.get("detailsUrl") or ""})
        else:
            out.append({"name": c.get("context") or "status", "state": (c.get("state") or "").upper(),
                        "workflow": "", "url": c.get("targetUrl") or ""})
    return out


def parse_diff(text: str) -> list[dict]:
    files, cur, total = [], None, 0
    for ln in text.splitlines():
        if ln.startswith("diff --git "):
            m = re.match(r"diff --git a/(.*?) b/(.*)$", ln)
            cur = {"path": m.group(2) if m else ln[11:], "lines": [], "add": 0, "del": 0, "cut": 0}
            files.append(cur)
            continue
        if cur is None or ln.startswith(("index ", "--- ", "+++ ", "new file mode", "deleted file mode",
                                         "similarity index", "rename from", "rename to", "old mode", "new mode")):
            continue
        if ln.startswith("Binary files"):
            cur["lines"].append("@binary file")
            continue
        if ln.startswith("@@"):
            ln = "@"
        elif ln.startswith("+"):
            cur["add"] += 1
        elif ln.startswith("-"):
            cur["del"] += 1
        elif not ln.startswith(" ") and ln != "":
            continue
        if len(cur["lines"]) >= MAX_FILE_LINES or total >= MAX_DIFF_LINES:
            cur["cut"] += 1
            continue
        if ln == "@" and (not cur["lines"]):
            continue
        cur["lines"].append(ln[:400] if ln else " ")
        total += 1
    for f in files:
        if f["cut"]:
            f["lines"].append(f"@{f['cut']} more lines")
        del f["cut"]
    return files


def _who(a) -> str:
    return (a or {}).get("login") or ""


class GitHub:
    def __init__(self, history: launcher.History):
        self.history = history
        self.local_cache: tuple[float, dict[str, str]] = (0.0, {})
        self.repos_cache: tuple[float, dict] | None = None

    # ---- local clones --------------------------------------------------------
    def local(self) -> dict[str, str]:
        """nameWithOwner (lowercase) -> clone path, from your project folders' origin remotes."""
        now = time.time()
        if now - self.local_cache[0] < 60:
            return self.local_cache[1]
        home = os.path.expanduser("~")
        dirs = set()
        for pat in ("~/Projects/*", "~/Work", "~/Work/*", "~/*", "~/src/*", "~/code/*"):
            dirs.update(glob.glob(os.path.expanduser(pat)))
        for p in self.history.projects({}, set()):
            dirs.add(p["cwd"])
        out: dict[str, str] = {}
        for d in sorted(dirs):
            cfg = os.path.join(d, ".git", "config")
            if not d.startswith(home) or not os.path.isfile(cfg):
                continue
            try:
                with open(cfg) as f:
                    text = f.read()
            except OSError:
                continue
            m = re.search(r'\[remote "origin"\][^\[]*?url\s*=\s*(\S+)', text)
            r = REMOTE_RE.search(m.group(1)) if m else None
            if r:
                out.setdefault(r.group(1).lower(), d)
        self.local_cache = (now, out)
        return out

    def path_for(self, repo: str) -> str | None:
        return self.local().get(repo.lower())

    def repo_for_cwd(self, cwd: str) -> str | None:
        """Which repo a session folder belongs to (for the chat header)."""
        if not cwd:
            return None
        for name, path in self.local_cache[1].items():
            if cwd == path or cwd.startswith(path.rstrip("/") + "/"):
                return name
        return None

    # ---- request helpers -------------------------------------------------------
    @staticmethod
    def repo(request: web.Request) -> str:
        r = f"{request.match_info['owner']}/{request.match_info['name']}"
        if not REPO_RE.match(r):
            raise web.HTTPBadRequest(text="bad repository name")
        return r

    @staticmethod
    def num(request: web.Request, key: str = "n") -> str:
        v = request.match_info[key]
        if not v.isdigit():
            raise web.HTTPBadRequest(text="bad number")
        return v

    @staticmethod
    async def body(request: web.Request) -> dict:
        try:
            d = await request.json()
        except ValueError:
            d = {}
        return d if isinstance(d, dict) else {}

    @staticmethod
    def text(d: dict, key: str, required: bool = True, limit: int = 60_000) -> str:
        v = str(d.get(key) or "").strip()
        if required and not v:
            raise web.HTTPBadRequest(text=f"{key} is empty")
        return v[:limit]

    # ---- repositories ----------------------------------------------------------
    REPO_FIELDS = """nameWithOwner description isPrivate isArchived pushedAt url
      pullRequests(states: OPEN) { totalCount } issues(states: OPEN) { totalCount }
      defaultBranchRef { name target { ... on Commit { statusCheckRollup { state } } } }"""

    async def repos(self, request):
        if self.repos_cache and time.time() - self.repos_cache[0] < 30 and not request.query.get("fresh"):
            return web.json_response(self.repos_cache[1])
        local = await asyncio.to_thread(self.local)
        q = "query { viewer { login repositories(first: 50, orderBy: {field: PUSHED_AT, direction: DESC}, " \
            "ownerAffiliations: [OWNER, COLLABORATOR, ORGANIZATION_MEMBER]) { nodes { " + self.REPO_FIELDS + " } } } }"
        d = await gh_json("api", "graphql", "-f", f"query={q}")
        viewer = d["data"]["viewer"]
        nodes = [n for n in viewer["repositories"]["nodes"] if n]
        seen = {n["nameWithOwner"].lower() for n in nodes}
        extra = [r for r in local if r not in seen]
        if extra:
            parts = []
            for i, r in enumerate(extra[:20]):
                owner, name = r.split("/", 1)
                parts.append(f'r{i}: repository(owner: {json.dumps(owner)}, name: {json.dumps(name)}) {{ {self.REPO_FIELDS} }}')
            try:
                d2 = await gh_json("api", "graphql", "-f", "query=query { " + " ".join(parts) + " }")
                nodes += [v for v in (d2.get("data") or {}).values() if v]
            except GhError:
                pass  # a clone of a repo you can't see any more; skip it
        out = []
        for n in nodes:
            target = ((n.get("defaultBranchRef") or {}).get("target") or {})
            ci = (target.get("statusCheckRollup") or {}).get("state")
            out.append({
                "repo": n["nameWithOwner"], "description": n.get("description") or "",
                "private": n.get("isPrivate", False), "archived": n.get("isArchived", False),
                "pushed": n.get("pushedAt") or "", "url": n.get("url") or "",
                "prs": n["pullRequests"]["totalCount"], "issues": n["issues"]["totalCount"],
                "ci": ci, "local": local.get(n["nameWithOwner"].lower()),
            })
        out.sort(key=lambda r: r["pushed"], reverse=True)
        result = {"login": viewer["login"], "repos": out}
        self.repos_cache = (time.time(), result)
        return web.json_response(result)

    # ---- pull requests -----------------------------------------------------------
    async def prs(self, request):
        r = self.repo(request)
        state = request.query.get("state", "open")
        if state not in ("open", "closed", "merged", "all"):
            state = "open"
        rows = await gh_json("pr", "list", "-R", r, "--state", state, "--limit", "40", "--json",
                             "number,title,author,headRefName,baseRefName,isDraft,reviewDecision,updatedAt,"
                             "statusCheckRollup,additions,deletions,state")
        return web.json_response({"prs": [{
            "number": p["number"], "title": p["title"], "author": _who(p.get("author")),
            "head": p.get("headRefName", ""), "base": p.get("baseRefName", ""), "draft": p.get("isDraft", False),
            "review": p.get("reviewDecision") or "", "updated": p.get("updatedAt", ""), "state": p.get("state", ""),
            "checks": checks_state(p.get("statusCheckRollup")), "add": p.get("additions", 0), "del": p.get("deletions", 0),
        } for p in rows or []]})

    async def pr(self, request):
        r, n = self.repo(request), self.num(request)
        fields = ("number,title,body,author,state,isDraft,mergeable,mergeStateStatus,reviewDecision,statusCheckRollup,"
                  "additions,deletions,changedFiles,comments,reviews,headRefName,baseRefName,url,createdAt,updatedAt")
        p, diff = await asyncio.gather(
            gh_json("pr", "view", n, "-R", r, "--json", fields),
            run(["gh", "pr", "diff", n, "-R", r], timeout=60),
            return_exceptions=True,
        )
        if isinstance(p, Exception):
            raise p
        convo = [{"kind": "comment", "author": _who(c.get("author")), "body": c.get("body", ""),
                  "at": c.get("createdAt", ""), "state": ""} for c in p.get("comments") or []]
        convo += [{"kind": "review", "author": _who(v.get("author")), "body": v.get("body", ""),
                   "at": v.get("submittedAt", ""), "state": v.get("state", "")}
                  for v in p.get("reviews") or [] if v.get("body") or v.get("state") in ("APPROVED", "CHANGES_REQUESTED")]
        convo.sort(key=lambda c: c["at"])
        return web.json_response({
            "number": p["number"], "title": p["title"], "body": p.get("body") or "", "author": _who(p.get("author")),
            "state": p.get("state", ""), "draft": p.get("isDraft", False), "mergeable": p.get("mergeable", ""),
            "mergeState": p.get("mergeStateStatus", ""), "review": p.get("reviewDecision") or "",
            "head": p.get("headRefName", ""), "base": p.get("baseRefName", ""), "url": p.get("url", ""),
            "created": p.get("createdAt", ""), "add": p.get("additions", 0), "del": p.get("deletions", 0),
            "changedFiles": p.get("changedFiles", 0),
            "checks": check_items(p.get("statusCheckRollup")), "checksState": checks_state(p.get("statusCheckRollup")),
            "files": [] if isinstance(diff, Exception) else parse_diff(diff),
            "diffError": str(diff) if isinstance(diff, Exception) else "",
            "conversation": convo, "local": self.path_for(r),
        })

    async def pr_merge(self, request):
        r, n = self.repo(request), self.num(request)
        d = await self.body(request)
        method = d.get("method", "squash")
        if method not in ("squash", "merge", "rebase"):
            raise web.HTTPBadRequest(text="method must be squash, merge or rebase")
        argv = ["gh", "pr", "merge", n, "-R", r, f"--{method}"]
        if d.get("delete_branch"):
            argv.append("--delete-branch")
        out = await run(argv, timeout=90)
        self.repos_cache = None
        return web.json_response({"ok": True, "message": out.strip()[-300:]})

    async def pr_comment(self, request):
        r, n = self.repo(request), self.num(request)
        body = self.text(await self.body(request), "body")
        await run(["gh", "pr", "comment", n, "-R", r, "--body-file", "-"], stdin=body)
        return web.json_response({"ok": True})

    async def pr_review(self, request):
        r, n = self.repo(request), self.num(request)
        d = await self.body(request)
        flag = {"approve": "--approve", "request_changes": "--request-changes", "comment": "--comment"}.get(d.get("event"))
        if not flag:
            raise web.HTTPBadRequest(text="event must be approve, request_changes or comment")
        body = self.text(d, "body", required=flag != "--approve")
        await run(["gh", "pr", "review", n, "-R", r, flag, "--body-file", "-"], stdin=body)
        return web.json_response({"ok": True})

    async def pr_state(self, request):
        r, n = self.repo(request), self.num(request)
        action = (await self.body(request)).get("action")
        if action not in ("close", "reopen", "ready"):
            raise web.HTTPBadRequest(text="action must be close, reopen or ready")
        await run(["gh", "pr", action, n, "-R", r])
        self.repos_cache = None
        return web.json_response({"ok": True})

    # ---- issues ------------------------------------------------------------------
    async def issues(self, request):
        r = self.repo(request)
        state = request.query.get("state", "open")
        if state not in ("open", "closed", "all"):
            state = "open"
        rows = await gh_json("issue", "list", "-R", r, "--state", state, "--limit", "40",
                             "--json", "number,title,author,labels,comments,updatedAt,state")
        return web.json_response({"issues": [{
            "number": i["number"], "title": i["title"], "author": _who(i.get("author")),
            "labels": [l.get("name", "") for l in i.get("labels") or []], "comments": len(i.get("comments") or []),
            "updated": i.get("updatedAt", ""), "state": i.get("state", ""),
        } for i in rows or []]})

    async def issue(self, request):
        r, n = self.repo(request), self.num(request)
        i = await gh_json("issue", "view", n, "-R", r, "--json",
                          "number,title,body,author,state,labels,comments,url,createdAt")
        return web.json_response({
            "number": i["number"], "title": i["title"], "body": i.get("body") or "", "author": _who(i.get("author")),
            "state": i.get("state", ""), "labels": [l.get("name", "") for l in i.get("labels") or []],
            "url": i.get("url", ""), "created": i.get("createdAt", ""),
            "conversation": [{"kind": "comment", "author": _who(c.get("author")), "body": c.get("body", ""),
                              "at": c.get("createdAt", ""), "state": ""} for c in i.get("comments") or []],
            "local": self.path_for(r),
        })

    async def issue_create(self, request):
        r = self.repo(request)
        d = await self.body(request)
        title = self.text(d, "title", limit=250).replace("\n", " ")
        body = self.text(d, "body", required=False)
        out = await run(["gh", "issue", "create", "-R", r, f"--title={title}", "--body-file", "-"], stdin=body)
        m = re.search(r"/issues/(\d+)", out)
        self.repos_cache = None
        return web.json_response({"ok": True, "number": int(m.group(1)) if m else None, "url": out.strip()})

    async def issue_comment(self, request):
        r, n = self.repo(request), self.num(request)
        body = self.text(await self.body(request), "body")
        await run(["gh", "issue", "comment", n, "-R", r, "--body-file", "-"], stdin=body)
        return web.json_response({"ok": True})

    async def issue_state(self, request):
        r, n = self.repo(request), self.num(request)
        action = (await self.body(request)).get("action")
        if action not in ("close", "reopen"):
            raise web.HTTPBadRequest(text="action must be close or reopen")
        await run(["gh", "issue", action, n, "-R", r])
        self.repos_cache = None
        return web.json_response({"ok": True})

    # ---- actions (CI) ------------------------------------------------------------
    async def runs(self, request):
        r = self.repo(request)
        rows = await gh_json("run", "list", "-R", r, "--limit", "25", "--json",
                             "databaseId,displayTitle,workflowName,headBranch,status,conclusion,createdAt,event,url")
        return web.json_response({"runs": [{
            "id": x["databaseId"], "title": x.get("displayTitle", ""), "workflow": x.get("workflowName", ""),
            "branch": x.get("headBranch", ""), "status": x.get("status", ""), "conclusion": x.get("conclusion") or "",
            "created": x.get("createdAt", ""), "event": x.get("event", ""), "url": x.get("url", ""),
        } for x in rows or []]})

    async def run_rerun(self, request):
        r, rid = self.repo(request), self.num(request, "id")
        await run(["gh", "run", "rerun", rid, "-R", r, "--failed"])
        return web.json_response({"ok": True})

    async def run_log(self, request):
        r, rid = self.repo(request), self.num(request, "id")
        try:
            log = await run(["gh", "run", "view", rid, "-R", r, "--log-failed"], timeout=60)
        except GhError as e:
            log = f"(no failed-step log: {e})"
        lines = log.splitlines()
        return web.json_response({"log": "\n".join(lines[-250:]), "truncated": len(lines) > 250})

    # ---- the clone on the laptop ---------------------------------------------------
    def need_local(self, r: str) -> str:
        path = self.path_for(r)
        if not path:
            raise web.HTTPConflict(text="This repository isn't cloned on the laptop")
        return path

    async def local_status(self, request):
        r = self.repo(request)
        path = self.path_for(r)
        if not path:
            return web.json_response({"cloned": False, "suggested": os.path.join("~/Projects", r.split("/")[1])})
        st, log = await asyncio.gather(
            run(["git", "status", "--porcelain=v2", "--branch"], cwd=path),
            run(["git", "log", "-8", "--format=%h%x09%s%x09%cr%x09%an"], cwd=path),
        )
        branch, upstream, ahead, behind, changes = "", "", 0, 0, []
        for ln in st.splitlines():
            if ln.startswith("# branch.head "):
                branch = ln.split(" ", 2)[2]
            elif ln.startswith("# branch.upstream "):
                upstream = ln.split(" ", 2)[2]
            elif ln.startswith("# branch.ab "):
                m = re.match(r"# branch\.ab \+(\d+) -(\d+)", ln)
                if m:
                    ahead, behind = int(m.group(1)), int(m.group(2))
            elif ln.startswith(("1 ", "2 ")):
                xy = ln.split(" ")[1]
                # "1 XY sub mH mI mW hH hI path" / "2 XY sub mH mI mW hH hI score path<TAB>orig"
                p = ln.split(" ", 8)[8] if ln.startswith("1 ") else ln.split("\t")[0].split(" ", 9)[9]
                changes.append({"status": (xy[0] if xy[0] != "." else xy[1]), "path": p})
            elif ln.startswith("? "):
                changes.append({"status": "?", "path": ln[2:]})
            elif ln.startswith("u "):
                changes.append({"status": "U", "path": ln.split(" ")[-1]})
        commits = [dict(zip(("hash", "subject", "when", "author"), c.split("\t"))) for c in log.splitlines() if c]
        home = os.path.expanduser("~")
        return web.json_response({
            "cloned": True, "path": "~" + path[len(home):] if path.startswith(home) else path, "cwd": path,
            "branch": branch, "upstream": upstream, "ahead": ahead, "behind": behind,
            "changes": changes[:60], "changeCount": len(changes), "commits": commits,
        })

    async def local_action(self, request):
        r = self.repo(request)
        action = request.match_info["action"]
        if action == "clone":
            if self.path_for(r):
                raise web.HTTPConflict(text="Already cloned")
            dest = os.path.expanduser(os.path.join("~/Projects", r.split("/")[1]))
            if os.path.exists(dest):
                raise web.HTTPConflict(text=f"{dest} already exists")
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            await run(["gh", "repo", "clone", r, dest], timeout=600)
            self.local_cache = (0.0, {})
            self.repos_cache = None
            return web.json_response({"ok": True, "message": f"Cloned to {dest}"})
        path = self.need_local(r)
        if action == "pull":
            out = await run(["git", "pull", "--ff-only"], cwd=path, timeout=120)
        elif action == "push":
            st = await run(["git", "status", "--porcelain=v2", "--branch"], cwd=path)
            has_upstream = "# branch.upstream " in st
            out = await run(["git", "push"] if has_upstream else ["git", "push", "-u", "origin", "HEAD"], cwd=path, timeout=120)
        elif action == "pr":
            out = await run(["gh", "pr", "create", "--fill"], cwd=path, timeout=90)
            self.repos_cache = None
        else:
            raise web.HTTPNotFound(text="unknown action")
        return web.json_response({"ok": True, "message": out.strip()[-400:] or "Done"})

    def routes(self, app: web.Application):
        g = "/api/gh/{owner}/{name}"
        app.router.add_get("/api/gh/repos", self.repos)
        app.router.add_get(g + "/prs", self.prs)
        app.router.add_get(g + "/prs/{n}", self.pr)
        app.router.add_post(g + "/prs/{n}/merge", self.pr_merge)
        app.router.add_post(g + "/prs/{n}/comment", self.pr_comment)
        app.router.add_post(g + "/prs/{n}/review", self.pr_review)
        app.router.add_post(g + "/prs/{n}/state", self.pr_state)
        app.router.add_get(g + "/issues", self.issues)
        app.router.add_post(g + "/issues", self.issue_create)
        app.router.add_get(g + "/issues/{n}", self.issue)
        app.router.add_post(g + "/issues/{n}/comment", self.issue_comment)
        app.router.add_post(g + "/issues/{n}/state", self.issue_state)
        app.router.add_get(g + "/runs", self.runs)
        app.router.add_post(g + "/runs/{id}/rerun", self.run_rerun)
        app.router.add_get(g + "/runs/{id}/log", self.run_log)
        app.router.add_get(g + "/local", self.local_status)
        app.router.add_post(g + "/local/{action}", self.local_action)


@web.middleware
async def errors_mw(request, handler):
    """gh/git failures become a readable 502 the phone can show as is."""
    try:
        return await handler(request)
    except GhError as e:
        raise web.HTTPBadGateway(text=str(e))
