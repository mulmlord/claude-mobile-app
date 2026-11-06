"""Virtual terminal per wrapped session, so the phone can see dialogs/menus."""

from __future__ import annotations

import re

import pyte

PENDING_MAX = 4 * 1024 * 1024
_MODE_RE = re.compile(rb"\x1b\[\?([0-9;]+)([hl])")
_KITTY_PUSH_RE = re.compile(rb"\x1b\[>([0-9]+)u")
_KITTY_POP_RE = re.compile(rb"\x1b\[<[0-9]*u")
# keyboard-protocol queries pyte doesn't understand (it would print a stray "u")
_STRIP_RE = re.compile(rb"\x1b\[[?>=<][0-9;]*u")


class Screen:
    def __init__(self, cols: int = 120, rows: int = 40):
        self.cols, self.rows = max(cols, 20), max(rows, 5)
        self._new()
        self.pending = bytearray()
        self.version = 0
        self.bracketed_paste = False
        self.app_cursor = False
        self.kitty = False

    def _new(self):
        self.screen = pyte.Screen(self.cols, self.rows)
        self.stream = pyte.ByteStream(self.screen)

    def feed(self, data: bytes):
        for m in _MODE_RE.finditer(data):
            on = m.group(2) == b"h"
            for mode in m.group(1).split(b";"):
                if mode == b"2004":
                    self.bracketed_paste = on
                elif mode == b"1":
                    self.app_cursor = on
        for m in _KITTY_PUSH_RE.finditer(data):
            self.kitty = int(m.group(1)) > 0
        if _KITTY_POP_RE.search(data):
            self.kitty = False
        self.pending += data
        if len(self.pending) > PENDING_MAX:
            del self.pending[: len(self.pending) - PENDING_MAX // 2]
        self.version += 1

    def reset(self):
        self.pending.clear()
        self._new()
        self.version += 1

    def resize(self, rows: int, cols: int):
        self.flush()
        self.rows, self.cols = max(rows, 5), max(cols, 20)
        try:
            self.screen.resize(self.rows, self.cols)
        except Exception:
            self._new()
        self.version += 1

    def flush(self):
        if not self.pending:
            return
        data = _STRIP_RE.sub(b"", bytes(self.pending))
        self.pending.clear()
        try:
            self.stream.feed(data)
        except Exception:
            self._new()

    def lines(self) -> list[str]:
        self.flush()
        out = [line.rstrip() for line in self.screen.display]
        while out and not out[-1]:
            out.pop()
        return out

    def cursor(self) -> tuple[int, int]:
        self.flush()
        return self.screen.cursor.x, self.screen.cursor.y

    # ---- dialog detection ----------------------------------------------
    _SEL_RE = re.compile(r"^(\s*[│|]?\s*)([❯›])(\s+)(\S.*?)\s*[│|]?\s*$")
    _NUM_RE = re.compile(r"^(\d+)\.\s+(.*)$")

    def dialog_options(self) -> list[dict]:
        """Options of a selection menu (permission prompt, trust dialog,
        AskUserQuestion...) found around the ❯ cursor near the bottom of the
        screen: [{"index", "key" (digit or None), "label", "selected"}]."""
        lines = self.lines()[-40:]
        sel = None
        for i in range(len(lines) - 1, -1, -1):
            m = self._SEL_RE.match(lines[i])
            if m:
                sel = (i, m)
                break
        if not sel:
            return []
        i, m = sel
        col = len(m.group(1)) + len(m.group(2)) + len(m.group(3))
        # the prompt input box (between two ─── rules) is not a menu
        above = lines[i - 1].strip() if i > 0 else ""
        below = lines[i + 1].strip() if i + 1 < len(lines) else ""
        if above.startswith("─") and below.startswith("─"):
            return []

        def label_at(line):
            if len(line) <= col or line[col] == " ":
                return None
            head = line[:col]
            if head.strip() not in ("", "│", "|") and not self._SEL_RE.match(line):
                return None
            return line[col:].strip().rstrip("│|").strip()

        rows = {i: label_at(lines[i]) or m.group(4)}
        for step in (-1, 1):
            j, blanks = i + step, 0
            while 0 <= j < len(lines):
                line = lines[j]
                indent = len(line) - len(line.lstrip(" │|"))
                if not line.strip():
                    blanks += 1
                    if blanks > 1:
                        break
                elif indent > col:
                    pass  # option description / wrapped line
                elif indent == col and label_at(line):
                    rows[j] = label_at(line)
                    blanks = 0
                else:
                    break
                j += step
        opts = []
        for n, j in enumerate(sorted(rows)):
            label = rows[j]
            nm = self._NUM_RE.match(label)
            opts.append({"index": n, "key": nm.group(1) if nm else None,
                         "label": nm.group(2) if nm else label, "selected": j == i})
        return opts if len(opts) >= 2 else []


def match_option(opts: list[dict], label: str) -> dict | None:
    """The dialog option meant by `label` (an AskUserQuestion option as the transcript
    has it), or None when nothing fits or it's ambiguous: a wrong pick is worse than
    asking the phone to open the chat.

    Exact first, then ignoring case/spacing, then by prefix: the screen can show a
    long label cut short ("…") or wrapped onto the next line, or with more text after it."""
    def norm(s: str) -> str:
        return " ".join(s.split()).rstrip("…").strip().casefold()

    for o in opts:
        if o["label"] == label:
            return o
    want = norm(label)
    if not want:
        return None
    labels = [(o, norm(o["label"])) for o in opts]
    same = [o for o, n in labels if n == want]
    if same:
        return same[0] if len(same) == 1 else None
    # Cut short on screen: the longest shown part of the label is the option meant
    # ("Use Redis" and a wrapped "Use Redis with a" for "Use Redis with a cache").
    cut = [(o, n) for o, n in labels if n and want.startswith(n)]
    if cut:
        longest = max(len(n) for _, n in cut)
        cut = [o for o, n in cut if len(n) == longest]
        return cut[0] if len(cut) == 1 else None
    # More text after the label on the same line; only at a word break, so "Use A"
    # never picks "Use AB".
    more = [o for o, n in labels if n.startswith(want) and not n[len(want)].isalnum()]
    return more[0] if len(more) == 1 else None
