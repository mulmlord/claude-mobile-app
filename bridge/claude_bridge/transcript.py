"""Incremental reader for Claude Code transcript JSONL files.

Turns raw transcript entries into a flat list of chat items for the phone:
  user / assistant (markdown text), tool (name + summary + result), system notes.
Items are keyed by a stable id and carry a monotonically increasing `seq`, so the
phone can upsert them (a tool item is updated when its result arrives).
"""

from __future__ import annotations

import difflib
import json
import os
import re

MAX_TEXT = 20_000
MAX_RESULT = 3_000
MAX_DIFF_LINES = 300
MAX_DIFF_CHARS = 30_000


def _clip(s: str, n: int) -> str:
    return s if len(s) <= n else s[: n - 1] + "…"


def _tag(text: str, name: str) -> str | None:
    m = re.search(rf"<{name}>(.*?)</{name}>", text, re.S)
    return m.group(1).strip() if m else None


def _rel(path: str, cwd: str) -> str:
    if cwd and path.startswith(cwd.rstrip("/") + "/"):
        return path[len(cwd.rstrip("/")) + 1:]
    home = os.path.expanduser("~")
    return "~" + path[len(home):] if path.startswith(home + "/") else path


def tool_summary(name: str, inp: dict, cwd: str) -> str:
    inp = inp if isinstance(inp, dict) else {}
    if name == "Bash":
        cmd = (inp.get("command") or "").strip()
        return inp.get("description") or (cmd.splitlines()[0][:200] if cmd else "")
    if name in ("Read", "Write", "Edit", "MultiEdit", "NotebookEdit"):
        return _rel(inp.get("file_path") or inp.get("notebook_path") or "", cwd)
    if name in ("Grep", "Glob"):
        p = inp.get("path")
        return f"{inp.get('pattern', '')}" + (f"  in {_rel(p, cwd)}" if p else "")
    if name == "WebFetch":
        return inp.get("url", "")
    if name == "WebSearch":
        return inp.get("query", "")
    if name in ("Agent", "Task"):
        return inp.get("description") or _clip(inp.get("prompt", ""), 120)
    if name == "TodoWrite":
        todos = inp.get("todos") or []
        active = [t.get("content", "") for t in todos if t.get("status") == "in_progress"]
        return active[0] if active else f"{len(todos)} todos"
    if name == "AskUserQuestion":
        qs = inp.get("questions") or []
        return qs[0].get("question", "") if qs else ""
    if name == "Skill":
        return inp.get("skill", "")
    for k in ("description", "command", "query", "prompt", "url", "path"):
        if isinstance(inp.get(k), str):
            return _clip(inp[k], 160)
    return _clip(json.dumps(inp, ensure_ascii=False), 160) if inp else ""


def tool_detail(name: str, inp: dict) -> str:
    """Longer text shown when a tool item is expanded on the phone."""
    inp = inp if isinstance(inp, dict) else {}
    if name == "Bash":
        return inp.get("command", "")
    if name == "Edit":
        return f"- {_clip(inp.get('old_string', ''), 1500)}\n+ {_clip(inp.get('new_string', ''), 1500)}"
    if name == "Write":
        return _clip(inp.get("content", ""), 3000)
    return _clip(json.dumps(inp, ensure_ascii=False, indent=1), 3000)


def tool_diff(name: str, inp: dict) -> dict | None:
    """Line diff for file edits: {"lines": ["+added", "-removed", " context", "@"], "add": n, "del": n}.

    "@" separates hunks. Counts cover the whole change; lines are capped so a big
    Write can't bloat every snapshot sent to the phone.
    """
    inp = inp if isinstance(inp, dict) else {}
    pairs = []
    if name == "Edit":
        pairs.append((inp.get("old_string") or "", inp.get("new_string") or ""))
    elif name == "MultiEdit":
        pairs += [(e.get("old_string") or "", e.get("new_string") or "") for e in inp.get("edits") or [] if isinstance(e, dict)]
    elif name == "Write":
        pairs.append(("", inp.get("content") or ""))
    else:
        return None
    lines, add, rem = [], 0, 0
    for old, new in pairs:
        if lines:
            lines.append("@")
        body = list(difflib.unified_diff(old.splitlines(), new.splitlines(), lineterm="", n=3))[2:]
        for ln in body:
            if ln.startswith("@@"):
                if lines and lines[-1] != "@":
                    lines.append("@")
                continue
            add += ln.startswith("+")
            rem += ln.startswith("-")
            lines.append(ln)
    out, size = [], 0
    for ln in lines:
        ln = _clip(ln, 400)
        if len(out) >= MAX_DIFF_LINES or size + len(ln) > MAX_DIFF_CHARS:
            out.append(f"@{len(lines) - len(out)} more lines")
            break
        out.append(ln)
        size += len(ln)
    if out and out[0] == "@":
        out.pop(0)
    return {"lines": out, "add": add, "del": rem}


def _result_text(content) -> str:
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        parts = []
        for b in content:
            if isinstance(b, dict):
                if b.get("type") == "text":
                    parts.append(b.get("text", ""))
                elif b.get("type") == "image":
                    parts.append("[image]")
        return "\n".join(parts)
    return ""


class Transcript:
    def __init__(self, path: str, cwd: str = ""):
        self.path = path
        self.cwd = cwd
        self.offset = 0
        self.partial = b""
        self.items: dict[str, dict] = {}     # id -> item
        self.order: list[str] = []            # item ids in display order
        self.seq = 0
        self.title: str | None = None
        self.chain: list[str] = []            # entry uuids in conversation order
        self.chain_pos: dict[str, int] = {}
        self.item_entry: dict[str, int] = {}  # item id -> chain position of its source entry
        self.tool_items: dict[str, str] = {}  # tool_use_id -> item id
        self.pending_question: str | None = None  # tool_use_id of an unanswered AskUserQuestion
        self.last_entry_kind: str | None = None
        self._custom_title = False

    # ---- reading -------------------------------------------------------
    def poll(self) -> tuple[list[dict], bool]:
        """Read new lines. Returns (changed items, reset) where reset means the
        phone should drop its copy (file truncated/rewound)."""
        try:
            size = os.path.getsize(self.path)
        except OSError:
            return [], False
        reset = False
        if size < self.offset:
            self.__init__(self.path, self.cwd)
            reset = True
        if size == self.offset:
            return [], reset
        with open(self.path, "rb") as f:
            f.seek(self.offset)
            data = f.read(size - self.offset)
        self.offset = size
        data = self.partial + data
        lines = data.split(b"\n")
        self.partial = lines.pop()
        changed: dict[str, dict] = {}
        removed = False
        for line in lines:
            if not line.strip():
                continue
            try:
                entry = json.loads(line)
            except ValueError:
                continue
            for item, rm in self._entry(entry):
                if rm:
                    removed = True
                else:
                    changed[item["id"]] = item
        if removed:
            reset = True
        return list(changed.values()), reset

    def snapshot(self, limit: int = 400) -> list[dict]:
        return [self.items[i] for i in self.order[-limit:]]

    # ---- parsing -------------------------------------------------------
    def _bump(self, item: dict) -> dict:
        self.seq += 1
        item["seq"] = self.seq
        return item

    def _add(self, item: dict, chain_idx: int) -> dict:
        self.items[item["id"]] = item
        self.order.append(item["id"])
        self.item_entry[item["id"]] = chain_idx
        return self._bump(item)

    def _truncate_after(self, idx: int):
        """Conversation was rewound to chain[idx]; drop everything after it."""
        for u in self.chain[idx + 1:]:
            self.chain_pos.pop(u, None)
        del self.chain[idx + 1:]
        keep = []
        for iid in self.order:
            if self.item_entry.get(iid, -1) <= idx:
                keep.append(iid)
            else:
                self.items.pop(iid, None)
                self.item_entry.pop(iid, None)
        self.order = keep
        self.tool_items = {k: v for k, v in self.tool_items.items() if v in self.items}

    def _entry(self, e: dict):
        t = e.get("type")
        if t == "ai-title" and e.get("aiTitle"):
            if not self._custom_title:
                self.title = e["aiTitle"]
            return
        if t in ("custom-title", "summary") and (e.get("customTitle") or e.get("summary")):
            self.title = e.get("customTitle") or e.get("summary")
            self._custom_title = t == "custom-title"
            return
        uuid = e.get("uuid")
        if not uuid or e.get("isSidechain"):
            return
        parent = e.get("parentUuid")
        if e.get("cwd"):
            self.cwd = e["cwd"]

        # Rewind detection: a typed prompt whose parent is not the newest entry
        # (or that has no parent at all) replaces everything after its parent.
        human = t == "user" and (e.get("promptSource") or (e.get("origin") or {}).get("kind") == "human")
        if human and self.chain and parent != self.chain[-1] and (parent is None or parent in self.chain_pos):
            cut = self.chain_pos[parent] if parent is not None else -1
            if any(self.item_entry.get(i, -1) > cut for i in self.order):
                self._truncate_after(cut)
                yield {}, True
        self.chain_pos[uuid] = len(self.chain)
        self.chain.append(uuid)
        idx = self.chain_pos[uuid]
        if t not in ("user", "assistant", "system"):
            return
        ts = e.get("timestamp", "")
        self.last_entry_kind = t

        if t == "system":
            sub = e.get("subtype")
            if sub == "compact_boundary":
                yield self._add({"id": uuid, "kind": "system", "text": "Conversation compacted", "ts": ts}, idx), False
            elif sub in ("api_error", "error") and e.get("content"):
                yield self._add({"id": uuid, "kind": "system", "text": _clip(str(e.get("content")), 500), "ts": ts}, idx), False
            return

        content = (e.get("message") or {}).get("content")

        if t == "assistant":
            blocks = content if isinstance(content, list) else [{"type": "text", "text": str(content or "")}]
            for n, b in enumerate(blocks):
                bt = b.get("type")
                iid = f"{uuid}:{n}"
                if bt == "text" and b.get("text", "").strip():
                    yield self._add({"id": iid, "kind": "assistant", "text": _clip(b["text"], MAX_TEXT), "ts": ts}, idx), False
                elif bt == "tool_use":
                    name = b.get("name", "?")
                    inp = b.get("input") or {}
                    item = {"id": iid, "kind": "tool", "name": name, "summary": tool_summary(name, inp, self.cwd),
                            "detail": tool_detail(name, inp), "ts": ts, "status": "running"}
                    diff = tool_diff(name, inp)
                    if diff:
                        item["diff"], item["add"], item["del"] = diff["lines"], diff["add"], diff["del"]
                    if name == "AskUserQuestion":
                        item["questions"] = [
                            {"question": q.get("question", ""), "header": q.get("header", ""),
                             "multiSelect": bool(q.get("multiSelect")),
                             "options": [{"label": o.get("label", ""), "description": o.get("description", "")}
                                         for o in q.get("options") or []]}
                            for q in inp.get("questions") or []]
                        self.pending_question = b.get("id")
                    self.tool_items[b.get("id", iid)] = iid
                    yield self._add(item, idx), False
            return

        # user entries
        if e.get("isMeta"):
            return
        if isinstance(content, str):
            yield from self._user_text(uuid, content, ts, idx)
            return
        if not isinstance(content, list):
            return
        texts = []
        for b in content:
            bt = b.get("type")
            if bt == "tool_result":
                tid = b.get("tool_use_id")
                if tid == self.pending_question:
                    self.pending_question = None
                iid = self.tool_items.get(tid)
                if iid and iid in self.items:
                    item = self.items[iid]
                    item["result"] = _clip(_result_text(b.get("content")), MAX_RESULT)
                    item["status"] = "error" if b.get("is_error") else "done"
                    yield self._bump(item), False
            elif bt == "text":
                texts.append(b.get("text", ""))
            elif bt == "image":
                texts.append("[image]")
        if texts:
            yield from self._user_text(uuid, "\n".join(texts), ts, idx)

    def _user_text(self, uuid, text, ts, idx):
        s = text.strip()
        if not s or s.startswith("<local-command-caveat>") or s.startswith("<system-reminder>"):
            return
        if s.startswith("[Request interrupted"):
            yield self._add({"id": uuid, "kind": "system", "text": "Interrupted", "ts": ts}, idx), False
            return
        if s.startswith("<command-name>") or s.startswith("<command-message>"):
            name = _tag(s, "command-name") or ""
            args = _tag(s, "command-args") or ""
            yield self._add({"id": uuid, "kind": "user", "text": f"{name} {args}".strip(), "ts": ts}, idx), False
            return
        if s.startswith("<local-command-stdout>") or s.startswith("<local-command-stderr>"):
            out = _tag(s, "local-command-stdout") or _tag(s, "local-command-stderr") or ""
            if out:
                yield self._add({"id": uuid, "kind": "system", "text": _clip(re.sub(r"\x1b\[[0-9;]*m", "", out), 1500), "ts": ts}, idx), False
            return
        if s.startswith("<bash-input>"):
            yield self._add({"id": uuid, "kind": "user", "text": "! " + (_tag(s, "bash-input") or ""), "ts": ts}, idx), False
            return
        if s.startswith("<bash-stdout>") or s.startswith("<bash-stderr>"):
            out = ((_tag(s, "bash-stdout") or "") + "\n" + (_tag(s, "bash-stderr") or "")).strip()
            if out:
                yield self._add({"id": uuid, "kind": "system", "text": _clip(out, 1500), "ts": ts}, idx), False
            return
        if s.startswith("<task-notification>"):
            summ = _tag(s, "summary") or _tag(s, "status") or "background task update"
            yield self._add({"id": uuid, "kind": "system", "text": _clip(summ, 300), "ts": ts}, idx), False
            return
        if s.startswith("This session is being continued from a previous conversation"):
            yield self._add({"id": uuid, "kind": "system", "text": "Continued from a previous conversation", "ts": ts}, idx), False
            return
        yield self._add({"id": uuid, "kind": "user", "text": _clip(s, MAX_TEXT), "ts": ts}, idx), False
