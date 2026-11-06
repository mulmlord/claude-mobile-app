import json

import pytest

from claude_bridge import transcript
from claude_bridge.transcript import Transcript, tool_detail, tool_diff, tool_summary
from helpers import Conv


@pytest.fixture
def conv(tmp_path, sandbox):
    return Conv(tmp_path / "s.jsonl", cwd=str(sandbox.home / "proj"))


def kinds(t: Transcript):
    return [(i["kind"], i.get("text") or i.get("name")) for i in t.snapshot()]


# ---- chat items ----------------------------------------------------------------------
def test_prompt_reply_and_tool_call_become_items(conv):
    bash = conv.tool_use("Bash", {"command": "ls -la\npwd", "description": "List files"})
    p = conv.prompt("Fix the bug")
    a = conv.assistant(conv.text("Looking."), bash)
    r = conv.user([conv.result(bash["id"], "file1\nfile2")])
    conv.write(p, a, r)
    t = Transcript(conv.path)
    items, reset = t.poll()
    assert not reset
    assert kinds(t) == [("user", "Fix the bug"), ("assistant", "Looking."), ("tool", "Bash")]
    tool = t.snapshot()[-1]
    assert tool["id"] == f"{a['uuid']}:1"
    assert tool["summary"] == "List files"
    assert tool["detail"] == "ls -la\npwd"
    assert tool["status"] == "done" and tool["result"] == "file1\nfile2"
    assert tool["ts"] == a["timestamp"]
    # the tool item changed twice in this poll but is reported once, with its newest seq
    assert sorted(i["id"] for i in items) == sorted(i["id"] for i in t.snapshot())
    seqs = [i["seq"] for i in t.snapshot()]
    assert seqs[0] < seqs[1] and tool["seq"] == t.seq
    assert t.cwd == conv.cwd


def test_tool_result_in_a_later_poll_updates_the_item(conv):
    read = conv.tool_use("Read", {"file_path": conv.cwd + "/src/main.py"})
    conv.write(conv.prompt("look"), conv.assistant(read))
    t = Transcript(conv.path)
    t.poll()
    tool = t.snapshot()[-1]
    assert tool["status"] == "running" and "result" not in tool
    seq = tool["seq"]

    conv.write(conv.user([conv.result(read["id"], [{"type": "text", "text": "no such file"},
                                                   {"type": "image", "source": {}}], is_error=True)]))
    items, reset = t.poll()
    assert [i["id"] for i in items] == [tool["id"]] and not reset
    assert items[0]["status"] == "error"
    assert items[0]["result"] == "no such file\n[image]"
    assert items[0]["seq"] > seq
    assert len(t.snapshot()) == 2  # updated in place, not appended


def test_long_texts_and_results_are_clipped(conv):
    bash = conv.tool_use("Bash", {"command": "yes"})
    conv.write(conv.prompt("x" * 25_000), conv.assistant(bash),
               conv.user([conv.result(bash["id"], "y" * 5000)]))
    t = Transcript(conv.path)
    t.poll()
    user, tool = t.snapshot()
    assert len(user["text"]) == transcript.MAX_TEXT and user["text"].endswith("…")
    assert len(tool["result"]) == transcript.MAX_RESULT


def test_entries_that_are_not_chat(conv):
    conv.write(
        conv.meta("file-history-snapshot", messageId="m", snapshot={}),
        conv.prompt("hi"),
        conv.entry("attachment", attachment={"type": "environment"}),
        conv.user("<local-command-caveat>Caveat: ...</local-command-caveat>"),
        conv.user("<system-reminder>be nice</system-reminder>"),
        conv.user("meta stuff", isMeta=True),
        conv.assistant({"type": "thinking", "thinking": "hmm", "signature": "x"}, conv.text("   ")),
        conv.system("turn_duration", durationMs=5),
        conv.system("stop_hook_summary"),
    )
    t = Transcript(conv.path)
    t.poll()
    assert kinds(t) == [("user", "hi")]


def test_special_user_texts(conv):
    conv.write(
        conv.user("<command-name>/model</command-name>\n<command-message>model</command-message>\n"
                  "<command-args>opus</command-args>"),
        conv.user("<local-command-stdout>Set model to \x1b[1mopus\x1b[22m</local-command-stdout>"),
        conv.user("<local-command-stdout></local-command-stdout>"),
        conv.user("<bash-input>git status</bash-input>"),
        conv.user("<bash-stdout>clean</bash-stdout><bash-stderr>warn</bash-stderr>"),
        conv.user("[Request interrupted by user]"),
        conv.user("<task-notification><status>done</status><summary>Tests passed</summary></task-notification>"),
        conv.user("This session is being continued from a previous conversation that ran out of context."),
        conv.user([conv.text("look at this"), {"type": "image", "source": {}}]),
        conv.system("compact_boundary"),
        conv.system("api_error", content="Overloaded"),
        conv.system("api_error"),
    )
    t = Transcript(conv.path)
    t.poll()
    assert kinds(t) == [
        ("user", "/model opus"),
        ("system", "Set model to opus"),
        ("user", "! git status"),
        ("system", "clean\nwarn"),
        ("system", "Interrupted"),
        ("system", "Tests passed"),
        ("system", "Continued from a previous conversation"),
        ("user", "look at this\n[image]"),
        ("system", "Conversation compacted"),
        ("system", "Overloaded"),
    ]


def test_sidechain_entries_are_skipped_and_stay_out_of_the_chain(conv):
    p = conv.prompt("main")
    side = conv.prompt("subagent prompt", sidechain=True, parent=p["uuid"])
    side_reply = conv.assistant(conv.text("subagent reply"), sidechain=True, parent=side["uuid"])
    reply = conv.assistant(conv.text("main reply"))
    nxt = conv.prompt("next")
    conv.write(p, side, side_reply, reply, nxt)
    t = Transcript(conv.path)
    items, reset = t.poll()
    assert kinds(t) == [("user", "main"), ("assistant", "main reply"), ("user", "next")]
    assert side["uuid"] not in t.chain_pos and not reset


# ---- titles --------------------------------------------------------------------------
def test_ai_title_then_custom_title_wins(conv):
    conv.write(conv.meta("ai-title", aiTitle="Fixing a bug"))
    t = Transcript(conv.path)
    t.poll()
    assert t.title == "Fixing a bug"
    conv.write(conv.meta("custom-title", customTitle="my-name"), conv.meta("ai-title", aiTitle="Later AI title"))
    t.poll()
    assert t.title == "my-name"


def test_custom_title_beats_a_later_summary(conv):
    conv.write(conv.meta("summary", summary="Old summary", leafUuid="x"))
    t = Transcript(conv.path)
    t.poll()
    assert t.title == "Old summary"
    conv.write(conv.meta("custom-title", customTitle="renamed"))
    t.poll()
    assert t.title == "renamed"
    conv.write(conv.meta("summary", summary="Newer summary", leafUuid="y"))
    t.poll()
    assert t.title == "renamed"


def test_empty_titles_are_ignored(conv):
    conv.write(conv.meta("ai-title", aiTitle="A"), conv.meta("custom-title", customTitle=""),
               conv.meta("ai-title", aiTitle=""))
    t = Transcript(conv.path)
    t.poll()
    assert t.title == "A"


# ---- rewinds ---------------------------------------------------------------------------
def test_rewind_drops_everything_after_the_new_prompts_parent(conv):
    bash = conv.tool_use("Bash", {"command": "make"})
    a = conv.prompt("first")
    a_reply = conv.assistant(conv.text("first reply"))
    b = conv.prompt("second")
    b_reply = conv.assistant(bash)
    conv.write(a, a_reply, b, b_reply)
    t = Transcript(conv.path)
    t.poll()
    assert len(t.snapshot()) == 4 and bash["id"] in t.tool_items

    # Esc Esc at the laptop: edit "second" -> new prompt hangs off "first reply"
    c = conv.prompt("second, edited", parent=a_reply["uuid"])
    conv.write(c)
    items, reset = t.poll()
    assert reset
    assert kinds(t) == [("user", "first"), ("assistant", "first reply"), ("user", "second, edited")]
    assert bash["id"] not in t.tool_items
    assert t.chain == [a["uuid"], a_reply["uuid"], c["uuid"]]

    # the conversation carries on normally from the new branch
    conv.write(conv.assistant(conv.text("ok")))
    items, reset = t.poll()
    assert not reset and [i["text"] for i in items] == ["ok"]


def test_rewind_to_the_very_start(conv):
    conv.write(conv.prompt("one"), conv.assistant(conv.text("reply")))
    t = Transcript(conv.path)
    t.poll()
    conv.write(conv.prompt("fresh start", parent=None))
    items, reset = t.poll()
    assert reset and kinds(t) == [("user", "fresh start")]


def test_origin_human_without_prompt_source_also_counts_as_typed(conv):
    a = conv.prompt("one")
    a_reply = conv.assistant(conv.text("reply"))
    conv.write(a, a_reply, conv.prompt("two"), conv.assistant(conv.text("reply 2")))
    t = Transcript(conv.path)
    t.poll()
    e = conv.entry("user", parent=a_reply["uuid"], message={"role": "user", "content": "two again"},
                   origin={"kind": "human"})
    conv.write(e)
    _, reset = t.poll()
    assert reset and kinds(t)[-1] == ("user", "two again") and len(t.snapshot()) == 3


def test_not_a_rewind(conv):
    a = conv.prompt("one")
    a_reply = conv.assistant(conv.text("reply"))
    tail = conv.system("turn_duration")  # in the chain, but shows nothing
    conv.write(a, a_reply, tail)
    t = Transcript(conv.path)
    t.poll()
    # parent is not the newest entry, but nothing visible came after it: no reset
    conv.write(conv.prompt("two", parent=a_reply["uuid"]))
    # a tool result / non-typed entry with an odd parent never rewinds
    conv.write(conv.user("injected", parent=a["uuid"]))
    # a prompt whose parent this transcript never saw (e.g. continued elsewhere) doesn't either
    conv.write(conv.prompt("three", parent="ffffffff-0000-4000-8000-000000000000"))
    _, reset = t.poll()
    assert not reset
    assert [k[1] for k in kinds(t)] == ["one", "reply", "two", "injected", "three"]


# ---- questions, usage, model -----------------------------------------------------------
def test_ask_user_question_is_pending_until_answered(conv):
    ask = conv.tool_use("AskUserQuestion", {"questions": [{
        "question": "Which database?", "header": "DB", "multiSelect": False,
        "options": [{"label": "PostgreSQL", "description": "Relational"}, {"label": "SQLite"}]}]})
    conv.write(conv.prompt("pick"), conv.assistant(ask))
    t = Transcript(conv.path)
    t.poll()
    assert t.pending_question == ask["id"]
    item = t.items[t.tool_items[ask["id"]]]
    assert item["summary"] == "Which database?"
    assert item["questions"] == [{"question": "Which database?", "header": "DB", "multiSelect": False,
                                  "options": [{"label": "PostgreSQL", "description": "Relational"},
                                              {"label": "SQLite", "description": ""}]}]
    conv.write(conv.user([conv.result(ask["id"], "User answered: SQLite")]))
    t.poll()
    assert t.pending_question is None and item["status"] == "done"


def test_context_usage_and_model(conv):
    usage = {"input_tokens": 10, "cache_creation_input_tokens": 1000, "cache_read_input_tokens": 50_000,
             "output_tokens": 200}
    conv.write(conv.prompt("hi"), conv.assistant(conv.text("a"), usage=usage, model="claude-opus-5-5"))
    t = Transcript(conv.path)
    t.poll()
    assert t.context_tokens == 51_210 and t.model == "claude-opus-5-5"
    # zero usage (synthetic error replies) and "<synthetic>" models don't overwrite
    conv.write(conv.assistant(conv.text("b"), usage={}, model="<synthetic>"))
    t.poll()
    assert t.context_tokens == 51_210 and t.model == "claude-opus-5-5"
    conv.write(conv.assistant(conv.text("c"), usage={"input_tokens": 7, "output_tokens": None},
                              model="claude-sonnet-5"))
    t.poll()
    assert t.context_tokens == 7 and t.model == "claude-sonnet-5"


# ---- incremental reading ----------------------------------------------------------------
def test_incremental_polling_of_a_growing_file(conv):
    t = Transcript(conv.path)
    assert t.poll() == ([], False)  # not there yet
    p = conv.prompt("hello")
    line = json.dumps(p)
    with open(conv.path, "w") as f:
        f.write(line[:20])  # Claude Code is mid-write
    assert t.poll() == ([], False)
    with open(conv.path, "a") as f:
        f.write(line[20:] + "\n" + "{not json}\n")
    items, reset = t.poll()
    assert [i["text"] for i in items] == ["hello"] and not reset
    assert t.poll() == ([], False)  # nothing new
    conv.write(conv.assistant(conv.text("hi there")))
    items, _ = t.poll()
    assert [i["text"] for i in items] == ["hi there"]
    assert len(t.snapshot()) == 2 and t.snapshot(limit=1)[0]["text"] == "hi there"


def test_truncated_file_is_read_again_from_the_start(conv):
    conv.write(conv.prompt("one"), conv.assistant(conv.text("two")))
    t = Transcript(conv.path)
    t.poll()
    with open(conv.path, "w") as f:
        f.write(json.dumps(Conv(conv.path).prompt("new")) + "\n")
    items, reset = t.poll()
    assert reset and kinds(t) == [("user", "new")]


# ---- tool summaries / details -------------------------------------------------------------
@pytest.mark.parametrize("name,inp,want", [
    ("Bash", {"command": "make test\nmore"}, "make test"),
    ("Bash", {"command": "make", "description": "Run the tests"}, "Run the tests"),
    ("Read", {"file_path": "{cwd}/src/a.py"}, "src/a.py"),
    ("Edit", {"file_path": "{home}/other/b.py"}, "~/other/b.py"),
    ("Write", {"file_path": "/etc/hosts"}, "/etc/hosts"),
    ("NotebookEdit", {"notebook_path": "{cwd}/n.ipynb"}, "n.ipynb"),
    ("Grep", {"pattern": "TODO", "path": "{cwd}/src"}, "TODO  in src"),
    ("Glob", {"pattern": "**/*.kt"}, "**/*.kt"),
    ("WebFetch", {"url": "https://example.com"}, "https://example.com"),
    ("WebSearch", {"query": "pyte docs"}, "pyte docs"),
    ("Agent", {"description": "Explore", "prompt": "long"}, "Explore"),
    ("Task", {"prompt": "p" * 200}, "p" * 119 + "…"),
    ("TodoWrite", {"todos": [{"content": "a", "status": "completed"},
                             {"content": "b", "status": "in_progress"}]}, "b"),
    ("TodoWrite", {"todos": [{"content": "a", "status": "pending"}]}, "1 todos"),
    ("AskUserQuestion", {"questions": []}, ""),
    ("Skill", {"skill": "tdd"}, "tdd"),
    ("mcp__x__y", {"query": "q", "url": "u"}, "q"),
    ("mcp__x__y", {"n": 1}, '{"n": 1}'),
    ("mcp__x__y", {}, ""),
    ("Bash", "not a dict", ""),
])
def test_tool_summary(sandbox, name, inp, want):
    cwd = str(sandbox.home / "proj")
    if isinstance(inp, dict):
        inp = {k: v.format(cwd=cwd, home=sandbox.home) if isinstance(v, str) else v for k, v in inp.items()}
    assert tool_summary(name, inp, cwd) == want


def test_tool_detail():
    assert tool_detail("Bash", {"command": "ls"}) == "ls"
    assert tool_detail("Edit", {"old_string": "a", "new_string": "b"}) == "- a\n+ b"
    assert len(tool_detail("Write", {"content": "x" * 5000})) == 3000
    assert tool_detail("Glob", {"pattern": "*"}) == '{\n "pattern": "*"\n}'
    assert tool_detail("Bash", None) == ""


# ---- diffs ------------------------------------------------------------------------------
def test_edit_diff_with_context_and_counts():
    d = tool_diff("Edit", {"old_string": "a\nb\nc", "new_string": "a\nB\nc\nd"})
    assert d == {"lines": [" a", "-b", "+B", " c", "+d"], "add": 2, "del": 1}


def test_write_is_all_additions():
    d = tool_diff("Write", {"content": "one\ntwo\n"})
    assert d == {"lines": ["+one", "+two"], "add": 2, "del": 0}


def test_separate_hunks_are_split_by_at():
    old = "\n".join(f"line {i}" for i in range(20))
    new = old.replace("line 1\n", "LINE 1\n").replace("line 18", "LINE 18")
    d = tool_diff("Edit", {"old_string": old, "new_string": new})
    assert d["add"] == 2 and d["del"] == 2
    assert d["lines"].count("@") == 1
    first, second = "\n".join(d["lines"]).split("\n@\n")
    assert "-line 1" in first and "+LINE 1" in first
    assert "-line 18" in second and "+LINE 18" in second


def test_multiedit_joins_its_edits():
    d = tool_diff("MultiEdit", {"edits": [
        {"old_string": "x = 1", "new_string": "x = 2"},
        "not an edit",
        {"old_string": "same", "new_string": "same"},
        {"old_string": "", "new_string": "added"},
    ]})
    assert d == {"lines": ["-x = 1", "+x = 2", "@", "+added"], "add": 2, "del": 1}
    d = tool_diff("MultiEdit", {"edits": [{"old_string": "a", "new_string": "b"},
                                          {"old_string": "same", "new_string": "same"}]})
    assert d["lines"] == ["-a", "+b"]  # no dangling separator
    assert tool_diff("MultiEdit", {"edits": []}) == {"lines": [], "add": 0, "del": 0}


def test_diff_is_capped_but_counts_cover_everything():
    d = tool_diff("Write", {"content": "\n".join(f"l{i}" for i in range(1000))})
    assert d["add"] == 1000
    assert len(d["lines"]) == transcript.MAX_DIFF_LINES + 1
    assert d["lines"][-1] == f"@{1000 - transcript.MAX_DIFF_LINES} more lines"


def test_diff_char_cap_and_long_lines():
    d = tool_diff("Write", {"content": "\n".join("y" * 1000 for _ in range(200))})
    assert d["add"] == 200
    shown = d["lines"][:-1]
    assert all(len(ln) == 400 and ln.endswith("…") for ln in shown)
    assert sum(map(len, shown)) <= transcript.MAX_DIFF_CHARS
    assert d["lines"][-1] == f"@{200 - len(shown)} more lines"


def test_only_file_edits_have_diffs():
    assert tool_diff("Bash", {"command": "rm x"}) is None
    assert tool_diff("Read", {"file_path": "x"}) is None


def test_tool_items_carry_their_diff(conv):
    edit = conv.tool_use("Edit", {"file_path": conv.cwd + "/a.txt", "old_string": "a", "new_string": "b"})
    conv.write(conv.prompt("go"), conv.assistant(edit))
    t = Transcript(conv.path)
    t.poll()
    item = t.snapshot()[-1]
    assert item["diff"] == ["-a", "+b"] and item["add"] == 1 and item["del"] == 1
    assert item["summary"] == "a.txt"
