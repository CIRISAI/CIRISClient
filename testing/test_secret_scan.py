"""The provider key never leaves a with-AI leg inside an artifact."""

from __future__ import annotations

import base64
import urllib.parse

from testing.gate import secret_scan as ss

KEY = "sk-ant-api03-" + "Zx9" * 20 + "-AA"


def test_a_file_holding_the_key_in_any_spelling_is_overwritten_and_fails_the_step(tmp_path, monkeypatch):
    (tmp_path / "logs").mkdir()
    plain = tmp_path / "logs" / "agent.log"
    plain.write_text(f"INFO configured provider anthropic key={KEY}\n")
    quoted = tmp_path / "logs" / "http.log"
    quoted.write_text("POST /v1/setup?key=" + urllib.parse.quote(KEY, safe=""))
    b64 = tmp_path / "report.json"
    b64.write_text('{"auth": "' + base64.b64encode(KEY.encode()).decode() + '"}')
    clean = tmp_path / "logs" / "node.log"
    clean.write_text("node is serving\n")

    monkeypatch.setenv("ANTHROPIC_API_KEY", KEY)
    assert ss.main(["--env", "ANTHROPIC_API_KEY", str(tmp_path)]) == 1
    for f in (plain, quoted, b64):
        assert f.read_text() == ss.REDACTED, f
        assert KEY not in f.read_text()
    assert clean.read_text() == "node is serving\n"


def test_a_clean_tree_passes(tmp_path, monkeypatch):
    (tmp_path / "a.log").write_text("nothing to see")
    monkeypatch.setenv("ANTHROPIC_API_KEY", KEY)
    assert ss.main(["--env", "ANTHROPIC_API_KEY", str(tmp_path), str(tmp_path / "absent")]) == 0


def test_an_unset_key_scans_nothing_rather_than_matching_everything(tmp_path, monkeypatch):
    (tmp_path / "a.log").write_text("x" * 100)
    monkeypatch.delenv("ANTHROPIC_API_KEY", raising=False)
    assert ss.main(["--env", "ANTHROPIC_API_KEY", str(tmp_path)]) == 0
    assert (tmp_path / "a.log").read_text() == "x" * 100


def test_redact_removes_the_key_from_a_message_the_harness_composes():
    msg = f"txt_llm_verdict says 'invalid x-api-key {KEY}'"
    out = ss.redact(msg, [KEY])
    assert KEY not in out and "[redacted]" in out
    assert ss.redact("short", ["abc"]) == "short"
