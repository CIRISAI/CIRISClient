"""The optional-skip ratchet (CIRISClient#168): a rise is red, a fall is STALE,
and the leg's board carries the count.

Each rule's red path runs here, on planted defects — a check whose failing half
has never run is a check with an untested half (AGENTS.md).
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from testing.gate import optional_skips as osk

REPO = Path(__file__).resolve().parents[1]


def _flow(flow: str, skipped: int, status: str = "pass", passed: int = 2) -> dict:
    steps = [{"step_id": f"p{i}", "status": "pass"} for i in range(passed)]
    steps += [{"step_id": f"s{i}", "status": "skipped", "phase": "requires"} for i in range(skipped)]
    return {"flow": flow, "csd": "CSD-900", "status": status, "detail": "", "steps": steps}


BASE = {"linux": {"wallet": 3, "receipt": 1, "people": 0}}


def test_counts_only_what_ran():
    now, present = osk.counts_for([_flow("wallet", 3), _flow("receipt", 1, status="refused"),
                                   _flow("people", 0, status="cannot-start")])
    assert now == {"wallet": 3}
    assert present == {"wallet", "receipt", "people"}


def test_the_ratchet_holds_when_every_count_matches():
    assert osk.judge("linux", {"wallet": 3, "receipt": 1, "people": 0},
                     {"wallet", "receipt", "people"}, BASE) == []


def test_a_rise_is_red_and_names_the_flow():
    # planted: the wallet flow skips one more optional step than recorded
    problems = osk.judge("linux", {"wallet": 4, "receipt": 1, "people": 0},
                         {"wallet", "receipt", "people"}, BASE)
    assert len(problems) == 1 and problems[0].startswith("RISE wallet: 4")
    assert "baseline 3" in problems[0]


def test_a_fall_is_STALE_not_a_quiet_improvement():
    # planted: a node release turned a wallet skip into a pass; the baseline
    # must tighten now, as csd_routes does
    problems = osk.judge("linux", {"wallet": 2, "receipt": 1, "people": 0},
                         {"wallet", "receipt", "people"}, BASE)
    assert len(problems) == 1 and problems[0].startswith("STALE wallet: 2")
    assert "--baseline" in problems[0]


def test_a_recorded_flow_that_no_longer_exists_is_STALE():
    problems = osk.judge("linux", {"wallet": 3, "receipt": 1}, {"wallet", "receipt"}, BASE)
    assert problems == [p for p in problems if p.startswith("STALE people")] and problems


def test_a_refused_or_cannot_start_flow_is_not_judged_twice():
    # present but not ran: its zero is not "debt paid down"
    assert osk.judge("linux", {"wallet": 3, "receipt": 1}, {"wallet", "receipt", "people"}, BASE) == []


def test_a_new_flow_with_skips_and_no_row_is_a_rise_and_one_with_none_is_not():
    assert osk.judge("linux", {"wallet": 3, "receipt": 1, "people": 0, "fresh": 2},
                     {"wallet", "receipt", "people", "fresh"}, BASE)[0].startswith("RISE fresh")
    assert osk.judge("linux", {"wallet": 3, "receipt": 1, "people": 0, "fresh": 0},
                     {"wallet", "receipt", "people", "fresh"}, BASE) == []


def test_a_leg_with_no_baseline_fails_naming_the_fix():
    [p] = osk.judge("ios", {"wallet": 0}, {"wallet"}, BASE)
    assert "no optional-skip baseline for leg 'ios'" in p and "--baseline" in p


def test_the_board_detail_carries_the_per_flow_breakdown():
    ok, detail = osk.verdict("linux", [_flow("wallet", 3), _flow("receipt", 1), _flow("people", 0)], BASE)
    assert ok and detail.startswith("4 (wallet 3, receipt 1); baseline 4")
    ok, detail = osk.verdict("linux", [_flow("wallet", 4), _flow("receipt", 1), _flow("people", 0)], BASE)
    assert not ok and "RISE wallet" in detail


def test_the_committed_baseline_parses_and_covers_every_leg():
    base = osk.load_baseline()
    ai = {"linux-ai", "windows-ai", "ios-ai"}
    assert set(base) == {"linux", "macos", "windows", "android", "ios"} | ai, sorted(base)
    node_flows = {p.stem.replace("-", "_") for p in (REPO / "testing" / "flows").glob("*.yaml")}
    # The with-AI legs run testing/flows/agent/ (five-platform-live-qa.yml).
    agent_flows = {p.stem.replace("-", "_") for p in (REPO / "testing" / "flows" / "agent").glob("*.yaml")}
    for leg, rows in base.items():
        flows = agent_flows if leg in ai else node_flows
        assert set(rows) <= flows, f"{leg}: baseline names flows that do not exist: {set(rows) - flows}"
        assert rows, f"{leg}: an empty row would make every count a rise"


def _reports(tmp_path: Path, **legs: list) -> Path:
    d = tmp_path / "reports"
    d.mkdir(parents=True)
    for leg, flows in legs.items():
        (d / f"{leg}.json").write_text(json.dumps({"platform": "desktop", "ok": True, "flows": flows}))
    return d


def test_the_cli_is_red_on_a_rise_red_on_stale_and_re_records(tmp_path, capsys):
    bl = tmp_path / "baseline.json"
    bl.write_text(json.dumps(BASE))
    good = [_flow("wallet", 3), _flow("receipt", 1), _flow("people", 0)]
    assert osk.main([str(_reports(tmp_path / "a", linux=good)), "--baseline-file", str(bl)]) == 0
    assert osk.main([str(_reports(tmp_path / "b", linux=[_flow("wallet", 4), _flow("receipt", 1), _flow("people", 0)])),
                     "--baseline-file", str(bl)]) == 1
    assert "RISE wallet" in capsys.readouterr().out
    assert osk.main([str(_reports(tmp_path / "c", linux=[_flow("wallet", 3), _flow("receipt", 0), _flow("people", 0)])),
                     "--baseline-file", str(bl)]) == 1
    assert "STALE receipt" in capsys.readouterr().out
    # re-record from the improved run; a leg not in the reports keeps its row
    bl.write_text(json.dumps({**BASE, "ios": {"wallet": 1}}))
    assert osk.main([str(tmp_path / "c" / "reports"), "--baseline-file", str(bl), "--baseline"]) == 0
    assert json.loads(bl.read_text()) == {"ios": {"wallet": 1}, "linux": {"people": 0, "receipt": 0, "wallet": 3}}


# ── wired where the leg's board is judged ───────────────────────────────────

def test_run_platform_judges_the_ratchet_on_its_board(monkeypatch, tmp_path):
    """The `optional-skips` step sits beside `flows` on every leg's board, and a
    planted rise turns the LEG red — not a side report nobody reads."""
    from testing.gate import run_flows, run_platform

    outcomes = [run_flows.FlowOutcome("wallet", "CSD-057", "pass", "", _flow("wallet", 3)["steps"]),
                run_flows.FlowOutcome("receipt", "CSD-006", "pass", "", _flow("receipt", 1)["steps"]),
                run_flows.FlowOutcome("people", "CSD-005", "pass", "", [])]
    monkeypatch.setattr(run_flows, "run_all", lambda *a, **k: outcomes)
    monkeypatch.setattr(run_flows, "fixture_factory", lambda *a, **k: None)
    monkeypatch.setattr(osk, "load_baseline", lambda *a, **k: BASE)

    class Args:
        report = tmp_path / "reports" / "linux.json"
        shots = tmp_path / "shots"
        client_version = "0.5.231"
        username = password = "x"
        node_binary = None

    rep = run_platform.Report(platform="desktop", node_version="v0.5.231")
    rep.add("health", True)
    run_platform.flows(None, rep, [], Args, None)
    names = [s.name for s in rep.steps]
    assert "optional-skips" in names, names
    step = next(s for s in rep.steps if s.name == "optional-skips")
    assert step.ok and step.detail.startswith("4 (wallet 3, receipt 1); baseline 4")

    # planted rise: one more optional skip in wallet
    outcomes[0].steps.append({"step_id": "s9", "status": "skipped", "phase": "requires"})
    rep2 = run_platform.Report(platform="desktop", node_version="v0.5.231")
    rep2.add("health", True)
    run_platform.flows(None, rep2, [], Args, None)
    step = next(s for s in rep2.steps if s.name == "optional-skips")
    assert not step.ok and "RISE wallet: 4" in step.detail
    assert not all(s.ok for s in rep2.steps)


def test_the_board_line_names_the_server_version_and_the_skip_count(tmp_path, capsys):
    from testing.gate import run_platform

    class Args:
        report = tmp_path / "reports" / "ios.json"
        platform = "ios"

    rep = run_platform.Report(platform="ios", ok=True, node_version="v0.5.231",
                              embedded_node_version="0.5.219")
    rep.add("flows", True, "9 pass")
    rep.flows = [_flow("wallet", 3), _flow("receipt", 1)]
    run_platform._finish(Args, rep)
    board = capsys.readouterr().out.strip().splitlines()[-1]
    assert board.startswith("ios: PASS"), board
    assert "server v0.5.231" in board and "optional skips 4" in board, board
    assert "embedded node 0.5.219" in board and "Agent" in board, board


def test_a_with_ai_board_names_the_one_agent_and_the_node_it_folds_in(tmp_path, capsys):
    """The release gate's board: the agent version beside the server version,
    and the agent's own pinned node — never the run's server claimed for it."""
    from testing.gate import run_platform

    class Args:
        report = tmp_path / "reports" / "linux-ai.json"
        platform = "desktop"

    rep = run_platform.Report(platform="desktop", ok=True, node_version="v0.5.231",
                              embedded_node_version="0.5.229", agent_version="2.16.2")
    rep.add("flows", True, "2 pass")
    run_platform._finish(Args, rep)
    board = capsys.readouterr().out.strip().splitlines()[-1]
    assert board.startswith("linux-ai: PASS"), board
    assert "server v0.5.231" in board and "agent ciris-agent 2.16.2 (with AI)" in board, board
    assert "agent's node 0.5.229" in board and "embedded node" not in board, board
