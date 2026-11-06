import pytest

from claude_bridge import screen as screen_mod
from claude_bridge.screen import Screen, match_option
from helpers import PERMISSION, PERMISSION_BOXED, PROMPT_BOX, QUESTION, TRUST, screen_bytes


def show(lines, cols=80, rows=30, styled=False) -> Screen:
    s = Screen(cols, rows)
    s.feed(screen_bytes(lines, styled=styled))
    return s


def labels(opts):
    return [(o["key"], o["label"], o["selected"]) for o in opts]


def test_boxed_permission_prompt():
    opts = show(PERMISSION_BOXED).dialog_options()
    assert labels(opts) == [
        ("1", "Yes", True),
        ("2", "Yes, and don't ask again for rm commands in ~/proj", False),
        ("3", "No, and tell Claude what to do differently (esc)", False),
    ]
    assert [o["index"] for o in opts] == [0, 1, 2]


def test_permission_prompt_with_colours():
    opts = show(PERMISSION, styled=True).dialog_options()
    assert labels(opts) == [
        ("1", "Yes", True),
        ("2", "Yes, allow all edits during this session (shift+tab)", False),
        ("3", "No, and tell Claude what to do differently (esc)", False),
    ]


def test_ask_user_question_skips_descriptions():
    opts = show(QUESTION).dialog_options()
    assert labels(opts) == [("1", "PostgreSQL", True), ("2", "SQLite", False), ("3", "Type something.", False)]


def test_folder_trust_dialog():
    assert labels(show(TRUST).dialog_options()) == [("1", "Yes, proceed", True), ("2", "No, exit", False)]


def test_prompt_input_box_is_not_a_menu():
    assert show(PROMPT_BOX).dialog_options() == []


def test_no_menu_and_single_option():
    assert show(["● Hello", "", "  some text"]).dialog_options() == []
    assert show(["Choose", "❯ 1. Only one"]).dialog_options() == []
    assert Screen().dialog_options() == []


def test_unnumbered_menu_and_the_other_pointer():
    opts = show(["Select model", "", "  Default", "› Opus", "  Sonnet"]).dialog_options()
    assert labels(opts) == [(None, "Default", False), (None, "Opus", True), (None, "Sonnet", False)]


def test_selection_moves_on_redraw():
    """Ink redraws in place: cursor up, clear line, write. The screen follows."""
    s = show(QUESTION)
    menu_top = QUESTION.index("❯ 1. PostgreSQL")
    up = len(QUESTION) - 1 - menu_top
    redraw = (f"\x1b[{up}A\r\x1b[2K  1. PostgreSQL\x1b[3B\r\x1b[2K\x1b[38;5;153m❯\x1b[39m 2. SQLite"
              f"\x1b[{up - 3}B\r")
    s.feed(redraw.encode())
    assert labels(s.dialog_options())[:2] == [("1", "PostgreSQL", False), ("2", "SQLite", True)]


def test_cursor_addressed_drawing():
    s = Screen(60, 12)
    s.feed(b"\x1b[2J"
           b"\x1b[5;2H Do you want to proceed?"
           b"\x1b[6;2H\x1b[36m\xe2\x9d\xaf\x1b[0m 1. Yes"
           b"\x1b[7;2H  2. No")
    assert labels(s.dialog_options()) == [("1", "Yes", True), ("2", "No", False)]


def test_the_newest_menu_near_the_bottom_is_used():
    old = ["Pick", "❯ 1. Old A", "  2. Old B", "", "", "text in between", ""]
    assert labels(show(old + TRUST).dialog_options()) == [("1", "Yes, proceed", True), ("2", "No, exit", False)]


def test_text_after_a_blank_gap_is_not_an_option():
    lines = ["Pick", "❯ 1. A", "  2. B", "", "", "  3. not part of it"]
    assert [o["label"] for o in show(lines).dialog_options()] == ["A", "B"]


def test_wide_long_labels_survive_narrow_screens():
    s = show(PERMISSION_BOXED, cols=40)  # pyte wraps the box lines; still no crash
    assert isinstance(s.dialog_options(), list)


# ---- terminal modes ----------------------------------------------------------------------
def test_tracks_bracketed_paste_and_cursor_keys_modes():
    s = Screen()
    assert not s.bracketed_paste and not s.app_cursor and not s.kitty
    s.feed(b"\x1b[?2004h\x1b[?1h")
    assert s.bracketed_paste and s.app_cursor
    s.feed(b"\x1b[?1;2004l")
    assert not s.bracketed_paste and not s.app_cursor
    s.feed(b"\x1b[>1u")
    assert s.kitty
    s.feed(b"\x1b[<u")
    assert not s.kitty


def test_keyboard_protocol_queries_dont_print():
    s = Screen(40, 5)
    s.feed(b"\x1b[?u\x1b[>1uhello\x1b[<u")
    assert s.lines() == ["hello"]


def test_resize_reset_and_versions():
    s = Screen(10, 2)
    assert (s.cols, s.rows) == (20, 5)  # minimum size
    v = s.version
    s.feed(b"abc")
    s.resize(10, 50)
    assert (s.screen.columns, s.screen.lines) == (50, 10)
    assert s.lines() == ["abc"]
    s.reset()
    assert s.lines() == [] and s.version > v
    s.feed(b"x\r\ny")
    assert s.cursor() == (1, 1)


def test_pending_output_is_bounded(monkeypatch):
    monkeypatch.setattr(screen_mod, "PENDING_MAX", 1000)
    s = Screen()
    s.feed(b"x" * 600)
    s.feed(b"y" * 600)
    assert len(s.pending) <= 1000
    assert s.pending.endswith(b"y" * 500)


# ---- match_option --------------------------------------------------------------------------
def opts(*labels_):
    return [{"index": i, "key": str(i + 1), "label": lab, "selected": i == 0} for i, lab in enumerate(labels_)]


@pytest.mark.parametrize("menu,want,picked", [
    (("PostgreSQL", "SQLite"), "SQLite", 1),                       # exact
    (("PostgreSQL", "SQLite"), "  sqlite ", 1),                    # case / spacing
    (("Use  Redis", "Other"), "use redis", 0),
    (("Use Redis", "Use Redis with a", "Other"), "Use Redis with a cache", 1),  # wrapped: longest part
    (("Use Redis with a c…", "Other"), "Use Redis with a cache", 0),            # cut short with …
    (("Use A (recommended)", "Use AB"), "Use A", 0),               # more text after, at a word break
    (("Use AB", "Other"), "Use A", None),                          # ...but never mid-word
    (("Same", "same"), "SAME", None),                              # ambiguous
    (("Foo bar", "Foo baz"), "Foo", None),                         # ambiguous prefix
    (("A", "B"), "", None),
    (("A", "B"), "…", None),
    (("PostgreSQL", "SQLite"), "MySQL", None),
])
def test_match_option(menu, want, picked):
    got = match_option(opts(*menu), want)
    assert (got["index"] if got else None) == picked


def test_match_option_prefers_exact_over_normalised():
    menu = opts("yes", "Yes")
    assert match_option(menu, "Yes")["index"] == 1
