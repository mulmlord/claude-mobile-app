"""Virtual terminal per wrapped session, so the phone can see dialogs/menus."""

from __future__ import annotations

import re

import pyte
from wcwidth import wcwidth

PENDING_MAX = 4 * 1024 * 1024
_MODE_RE = re.compile(rb"\x1b\[\?([0-9;]+)([hl])")
_KITTY_PUSH_RE = re.compile(rb"\x1b\[>([0-9]+)u")
_KITTY_POP_RE = re.compile(rb"\x1b\[<[0-9]*u")
# Keyboard-protocol sequences pyte doesn't understand: kitty's CSI > … u (it would print a
# stray "u") and xterm's modifyOtherKeys CSI > 4 ; 2 m, which pyte takes for SGR 4;2 and
# underlines and dims everything after it.
_STRIP_RE = re.compile(rb"\x1b\[[?>=<][0-9;]*[um]")
# SGR with ":" sub-parameters (38:2::r:g:b, 4:3 curly underline, 58:… underline colour):
# pyte only knows ";" and would print the rest of the sequence as text.
_SGR_COLON_RE = re.compile(rb"\x1b\[([0-9;]*:[0-9;:]*)m")


def _sgr_colons(m: re.Match) -> bytes:
    out = []
    for p in m.group(1).split(b";"):
        if b":" not in p:
            out.append(p)
            continue
        head, *sub = p.split(b":")
        if head in (b"38", b"48"):
            nums = [x for x in sub if x]  # 38:2::r:g:b leaves the colour-space id empty
            if nums[:1] == [b"2"] and len(nums) >= 4:
                out += [head, b"2", *nums[-3:]]
            elif nums[:1] == [b"5"] and len(nums) >= 2:
                out += [head, b"5", nums[1]]
        elif head == b"4":
            out.append(b"24" if sub[:1] == [b"0"] else b"4")
        # anything else with sub-parameters (58:… underline colour) is dropped
    return b"\x1b[" + b";".join(out) + b"m"


class _DimScreen(pyte.Screen):
    """pyte drops SGR 2 (dim/faint), which Claude Code uses for its grey secondary text.
    Keep it in the blink attribute instead, which nothing on these screens uses."""

    def select_graphic_rendition(self, *attrs, **kwargs):
        out, i = [], 0
        while i < len(attrs):
            a = attrs[i]
            if a in (38, 48, 58):
                # 38;5;n and 38;2;r;g;b carry numbers that aren't attributes (a 2, 4 or 5 among
                # them). 58 is the same for the underline colour, which pyte doesn't know: skip it
                # whole, or its numbers would switch on dim, underline, bold...
                n = {5: 2, 2: 4}.get(attrs[i + 1], 1) if i + 1 < len(attrs) else 0
                if a != 58:
                    out.extend(attrs[i:i + 1 + n])
                i += 1 + n
                continue
            out.extend((5,) if a == 2 else (22, 25) if a == 22 else (a,))
            i += 1
        super().select_graphic_rendition(*out, **kwargs)


# Style flags sent to the phone with each run of text.
BOLD, ITALIC, UNDERLINE, REVERSE, DIM, STRIKE = 1, 2, 4, 8, 16, 32


def _color(c: str) -> str:
    """pyte colour -> "" (terminal default), a name ("red", "brightblue") or "#rrggbb"."""
    if not c or c == "default":
        return ""
    if len(c) == 6 and all(ch in "0123456789abcdefABCDEF" for ch in c):
        return "#" + c.lower()
    return c.replace("brown", "yellow").replace("bfight", "bright")  # pyte's names (and its typo)


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
        self.screen = _DimScreen(self.cols, self.rows)
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
        data = _SGR_COLON_RE.sub(_sgr_colons, _STRIP_RE.sub(b"", bytes(self.pending)))
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

    def styled(self) -> tuple[list[str], list[list[int]], list[list]]:
        """lines() plus their colours: runs[i] is a flat [length, style, length, style, ...]
        covering lines[i] (empty when it's all default), and styles[style] is
        [fg, bg, flags] with fg/bg "" for the terminal's default colours (style 0)."""
        self.flush()
        styles: list[list] = [["", "", 0]]
        index = {("", "", 0): 0}
        lines, runs = [], []
        buf = self.screen.buffer
        for y in range(self.screen.lines):
            row = buf[y]
            text, cells = [], []
            wide = False
            for x in range(self.screen.columns):
                if wide:  # the stub cell after a double-width character (as pyte's display does)
                    wide = False
                    continue
                ch = row[x]
                wide = bool(ch.data) and wcwidth(ch.data[0]) == 2
                flags = ((BOLD if ch.bold else 0) | (ITALIC if ch.italics else 0) | (UNDERLINE if ch.underscore else 0)
                         | (REVERSE if ch.reverse else 0) | (DIM if ch.blink else 0) | (STRIKE if ch.strikethrough else 0))
                key = (_color(ch.fg), _color(ch.bg), flags)
                st = index.get(key)
                if st is None:
                    st = index[key] = len(styles)
                    styles.append(list(key))
                text.append(ch.data)
                cells.append((len(ch.data), st))
            line = "".join(text).rstrip()
            # Runs over the kept text only: trailing blanks go, as in lines().
            keep, r, n = len(line), [], 0
            for length, st in cells:
                if n >= keep:
                    break
                length = min(length, keep - n)
                if r and r[-1] == st:
                    r[-2] += length
                else:
                    r += [length, st]
                n += length
            lines.append(line)
            runs.append([] if r == [keep, 0] or not r else r)
        while lines and not lines[-1]:
            lines.pop()
            runs.pop()
        return lines, runs, styles

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
            # The prompt you type into (❯ under a ─── rule), even empty: no menu is open, and a
            # ❯ further up is a message of yours in the conversation, not a menu's cursor.
            if lines[i].strip().startswith("❯") and i > 0 and lines[i - 1].strip().startswith("─"):
                return []
            m = self._SEL_RE.match(lines[i])
            if m:
                sel = (i, m)
                break
        if not sel:
            return []
        i, m = sel
        col = len(m.group(1)) + len(m.group(2)) + len(m.group(3))

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
        if len(opts) < 2:
            return []
        self._menu_top = (lines, min(rows))
        return opts

    def dialog_text(self, max_lines: int = 14) -> str:
        """The menu's prompt as shown above its options (for a permission prompt: the tool
        and the command or file it's about). This is what the user would read at the laptop,
        so approvals from the phone are tied to exactly this text, not to a guess from the
        transcript (with parallel tool calls the newest pending one isn't always the one asked)."""
        if not self.dialog_options():
            return ""
        lines, top = self._menu_top
        out: list[str] = []
        for j in range(top - 1, max(-1, top - 41), -1):
            st = lines[j].strip()
            if st.startswith(("╭", "┌", "─", "━", "●", "⎿")):  # box top, rule, or Claude's output above it
                break
            st = st.strip("│|").strip()
            if st and set(st) <= set("╌┄┈-"):
                continue  # dashed lines around the diff preview inside the prompt
            out.append(st)
            if sum(1 for x in out if x) >= max_lines:
                break
        text = "\n".join(reversed(out)).strip("\n")
        return re.sub(r"\n{3,}", "\n\n", text)


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
