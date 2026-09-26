"""CSD flows on the matrix: loading, the floor, and the runner's verdicts.

Every rule here has a red path, and each test is the red path — a check whose
failing half has never run is a check with an untested half (AGENTS.md). The
runner tests drive `run_flows.run_one` through a FAKE helper, which is honest
here in a way it is not for the driver: what is under test is the verdict logic
over `/tree`-shaped answers, not the transport (test_driver_rules.py owns that).
"""

from __future__ import annotations

import asyncio
import re
import textwrap
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, Optional

import pytest
import yaml

from testing.gate import run_flows
from testing.gate.flow_spec import FlowSpec, SpecError

REPO = Path(__file__).resolve().parents[1]
FLOWS = REPO / "testing" / "flows"

CSD_TEXT = """\
# CSD-900 — a test surface

```yaml csd:stage
stage: sketched
owner: CIRISClient
```

```yaml csd:shows
fields:
  - ceg: x_private:score
    use: display-only
    type: float
    example: 0.5
    renders: "0.5"
    tag: value_score
  - ceg: x_private:chip
    use: display-only
    type: string
    example: "a"
    renders: "a chip"
    tag: "proposed:row_chip"
```

```yaml csd:states
populated: {tag: thing_list}
empty:     {tag: thing_empty}
loading:   {renders: "a spinner, no tag"}
error:     {tag: "proposed:thing_error"}
```
"""


def _csd_root(tmp_path: Path, text: str = CSD_TEXT, name: str = "CSD-900-test.md") -> Path:
    root = tmp_path / "csd"
    root.mkdir(exist_ok=True)
    (root / name).write_text(text, encoding="utf-8")
    return root


def _flow(tmp_path: Path, body: str, name: str = "f.yaml") -> Path:
    p = tmp_path / name
    p.write_text(textwrap.dedent(body), encoding="utf-8")
    return p


GOOD = """\
    flow: thing
    csd: CSD-900
    client: ">=0.5.224"
    steps:
      - step_id: land
        title: lands
        requires: {screen: Thing}
        expect:
          visible: [thing_list]
"""


# ── loading ─────────────────────────────────────────────────────────────────

def test_a_good_flow_loads_and_carries_its_csds_maps(tmp_path):
    spec = FlowSpec.load(_flow(tmp_path, GOOD), csd_root=_csd_root(tmp_path))
    assert spec.csd_id == "CSD-900"
    assert spec.csd.field_tags["x_private:score"] == "value_score"
    assert spec.csd.state_tags == {"populated": "thing_list", "empty": "thing_empty"}
    assert "row_chip" in spec.csd.proposed and "thing_error" in spec.csd.proposed


def test_an_unknown_top_level_key_is_a_load_error(tmp_path):
    p = _flow(tmp_path, GOOD + "    csd_id: CSD-900\n")
    with pytest.raises(SpecError, match="unknown key"):
        FlowSpec.load(p, csd_root=_csd_root(tmp_path))


def test_a_csd_that_does_not_exist_is_a_load_error_not_a_skip(tmp_path):
    p = _flow(tmp_path, GOOD.replace("CSD-900", "CSD-999"))
    with pytest.raises(SpecError, match="does not exist"):
        FlowSpec.load(p, csd_root=_csd_root(tmp_path))


def test_a_malformed_csd_id_is_a_load_error(tmp_path):
    p = _flow(tmp_path, GOOD.replace("CSD-900", "people"))
    with pytest.raises(SpecError, match="not a CSD id"):
        FlowSpec.load(p, csd_root=_csd_root(tmp_path))


def test_a_csd_whose_blocks_do_not_parse_is_a_load_error(tmp_path):
    broken = CSD_TEXT.replace("populated: {tag: thing_list}", "populated: {tag: [unclosed")
    with pytest.raises(SpecError, match="does not parse"):
        FlowSpec.load(_flow(tmp_path, GOOD), csd_root=_csd_root(tmp_path, broken))


def test_a_document_with_no_stage_block_is_not_a_csd(tmp_path):
    prose = "# CSD-900\n\nJust prose, no typed blocks.\n"
    with pytest.raises(SpecError, match="not a CSD/3 document"):
        FlowSpec.load(_flow(tmp_path, GOOD), csd_root=_csd_root(tmp_path, prose))


def test_a_flow_in_this_repo_must_name_its_csd(tmp_path):
    no_csd = GOOD.replace("    csd: CSD-900\n", "")
    d = tmp_path / "flows"
    d.mkdir()
    _flow(d, no_csd)
    with pytest.raises(SpecError, match="names no `csd:`"):
        run_flows.load_flows([d], csd_root=_csd_root(tmp_path))


def test_an_empty_flows_directory_is_a_load_error(tmp_path):
    d = tmp_path / "flows"
    d.mkdir()
    with pytest.raises(SpecError, match="no flows found"):
        run_flows.load_flows([d])


@pytest.mark.parametrize("where", [
    "        expect:\n          visible: [row_chip]\n",
    "        expect:\n          absent: [row_chip]\n",
    "        expect:\n          text: {row_chip: a}\n",
    "        do:\n          - click: row_chip\n",
    "        requires:\n          visible: [row_chip]\n        expect:\n          visible: [thing_list]\n",
])
def test_a_proposed_tag_named_anywhere_in_a_flow_is_a_load_error(tmp_path, where):
    body = GOOD.split("      - step_id")[0] + "      - step_id: s\n        title: t\n" + where
    with pytest.raises(SpecError, match="proposed"):
        FlowSpec.load(_flow(tmp_path, body), csd_root=_csd_root(tmp_path))


def test_the_real_csd_005_refuses_its_proposed_trust_chip(tmp_path):
    """Against the shipped document, not a fixture: `contacts_row_trust` is
    `proposed:` in CSD-005, so no flow may assert it yet."""
    body = """\
        flow: p
        csd: CSD-005
        steps:
          - step_id: s
            title: t
            expect:
              visible: [contacts_row_trust]
    """
    with pytest.raises(SpecError, match="contacts_row_trust.*proposed"):
        FlowSpec.load(_flow(tmp_path, body))


def test_a_state_whose_tag_is_proposed_is_a_load_error(tmp_path):
    body = GOOD.replace("visible: [thing_list]", "state: error")
    with pytest.raises(SpecError, match="state: error.*proposed"):
        FlowSpec.load(_flow(tmp_path, body), csd_root=_csd_root(tmp_path))


def test_a_state_the_csd_gives_no_tag_is_a_load_error(tmp_path):
    body = GOOD.replace("visible: [thing_list]", "state: loading")
    with pytest.raises(SpecError, match="names no tag"):
        FlowSpec.load(_flow(tmp_path, body), csd_root=_csd_root(tmp_path))


def test_a_relation_over_a_field_the_csd_does_not_show_is_a_load_error(tmp_path):
    body = GOOD.replace(
        "visible: [thing_list]",
        "relation: {left: 'x_private:score', op: eq, right: 'x_private:nope'}")
    with pytest.raises(SpecError, match="not a field"):
        FlowSpec.load(_flow(tmp_path, body), csd_root=_csd_root(tmp_path))


def test_a_flow_without_csd_still_loads_through_flow_spec_alone(tmp_path):
    """The delta is additive: an upstream-shaped flow (no `csd:`) loads as before."""
    spec = FlowSpec.load(_flow(tmp_path, GOOD.replace("    csd: CSD-900\n", "")))
    assert spec.csd is None


# ── the seeded flows ────────────────────────────────────────────────────────

def test_every_flow_in_the_repo_loads_against_its_real_csd():
    specs = run_flows.load_flows([FLOWS])
    assert specs, "testing/flows is empty — the matrix would run nothing and pass"
    assert all(s.csd is not None for s in specs)


def _client_tag_strings() -> tuple[set[str], set[str]]:
    """(whole tag literals, prefixes of interpolated tags) in commonMain.

    `"age_band_$token"` builds `age_band_adult`, so a literal-only read calls a
    real tag missing. A prefix counts only if it has two segments
    (`age_band_`, not `btn_`): `"btn_$x"` would otherwise vouch for every
    button a flow could ever name, which is no check at all.
    """
    src = REPO / "client" / "shared" / "src" / "commonMain"
    literals: set[str] = set()
    prefixes: set[str] = set()
    for kt in src.rglob("*.kt"):
        for body, end in re.findall(r'"([a-z][a-z0-9_]*)(["$])', kt.read_text(encoding="utf-8")):
            if end == '"':
                literals.add(body)
            elif "_" in body.rstrip("_"):
                prefixes.add(body)
    return literals, prefixes


def client_carries(tag: str, literals: set[str], prefixes: set[str]) -> bool:
    return tag in literals or any(tag.startswith(p) for p in prefixes)


@pytest.mark.parametrize("tag,carried", [
    ("age_band_adult", True),        # "age_band_$token"      SetupScreen.kt
    ("trace_consent_yes", True),     # "trace_consent_$token" SetupScreen.kt
    ("radio_cohort_family", True),   # "radio_cohort_$value"  ClaimNodeScreen.kt
    ("chk_duty_box_accept", True),   # "chk_duty_box_$verb"   DutyConferralScreen.kt
    ("opt_run_with_ai", True),       # a whole literal still matches
    ("contacts_no_such_tag", False),
    ("btn_no_such_button", False),   # a one-segment prefix vouches for nothing
])
def test_the_client_tag_check_sees_interpolated_tags_and_nothing_else(tag, carried):
    assert client_carries(tag, *_client_tag_strings()) is carried


def test_every_tag_a_seeded_flow_names_exists_in_the_client():
    """A flow naming a tag the client does not carry fails as 'element not
    found' on every leg. Checked here, at the keyboard, rather than there."""
    literals, prefixes = _client_tag_strings()
    missing = []
    for spec in run_flows.load_flows([FLOWS]):
        for step in spec.steps:
            tags = [a.target for a in step.do]
            for cond in (step.requires, step.expect):
                tags += cond.visible + cond.absent + list(cond.text)
                if cond.state:
                    tags.append(spec.csd.state_tags[cond.state])
            missing += [f"{spec.flow}/{step.step_id}: {t}" for t in tags
                        if not client_carries(t, literals, prefixes)]
    assert not missing, f"tags no client source carries: {missing}"


# ── the runner, over a fake helper ──────────────────────────────────────────

@dataclass
class _El:
    test_tag: str
    text: Optional[str] = ""
    visible: Optional[bool] = True
    width: int = 10
    height: int = 10


class FakeHelper:
    """`/tree` as a dict. Records every call so a refusal can be shown to have
    driven NOTHING."""

    def __init__(self, screen: str, tags: Dict[str, str]):
        self.screen = screen
        self.els = {t: _El(t, txt) for t, txt in tags.items()}
        self.calls: list[str] = []
        self.leads: dict = {}

    async def get_elements(self):
        self.calls.append("tree")
        return list(self.els.values())

    async def get_element(self, tag):
        self.calls.append(f"get {tag}")
        return self.els.get(tag)

    async def get_screen(self):
        self.calls.append("screen")
        return self.screen

    async def is_element_visible(self, tag):
        return tag in self.els

    async def scroll_into_view(self, tag):
        return tag in self.els

    async def click(self, tag, timeout=2000):
        self.calls.append(f"click {tag}")
        if tag not in self.els:
            return False
        # A click can move the app: `leads` maps a tag to (screen, tags now shown).
        if tag in self.leads:
            self.screen, shown = self.leads[tag]
            self.els = {t: _El(t, "") for t in shown}
        return True

    async def input_text(self, tag, text):
        self.calls.append(f"input {tag}")
        return tag in self.els

    async def wait_for_element(self, tag, timeout=2000):
        return tag in self.els


def _run(spec, helper, version="0.5.224"):
    return asyncio.run(run_flows.run_one(spec, helper, client_version=version, start_timeout=0))


def _spec(tmp_path, body=GOOD):
    return FlowSpec.load(_flow(tmp_path, body), csd_root=_csd_root(tmp_path))


def test_a_flow_whose_expects_hold_passes(tmp_path):
    out = _run(_spec(tmp_path), FakeHelper("Thing", {"thing_list": ""}))
    assert out.status == run_flows.PASS
    assert run_flows.leg_ok([out])


def test_a_failing_expect_fails_the_flow_and_the_leg(tmp_path):
    out = _run(_spec(tmp_path), FakeHelper("Thing", {"something_else": ""}))
    assert out.status == run_flows.FAIL
    assert "thing_list" in out.detail and "expect" in out.detail
    assert out.steps and out.steps[-1]["status"] == "fail"
    assert not run_flows.leg_ok([out])


def test_a_flow_that_never_reaches_its_first_screen_cannot_start_and_reddens_the_leg(tmp_path):
    out = _run(_spec(tmp_path), FakeHelper("Login", {"thing_list": ""}))
    assert out.status == run_flows.CANNOT_START
    assert "Thing" in out.detail
    assert not run_flows.leg_ok([out]), "a flow that never ran must not leave the leg green"


@pytest.mark.parametrize("floor,version", [
    (">=9.9.9", "0.5.224"),
    (">0.5.224", "0.5.224"),
    ("unreleased", "0.5.224"),
])
def test_a_flow_above_its_floor_is_refused_neither_passed_nor_failed(tmp_path, floor, version):
    spec = _spec(tmp_path, GOOD.replace('">=0.5.224"', f'"{floor}"'))
    helper = FakeHelper("Thing", {"thing_list": ""})
    out = _run(spec, helper, version)
    assert out.status == run_flows.REFUSED
    assert helper.calls == [], "a refused flow must drive nothing"
    assert run_flows.leg_ok([out]), "refused is not a failure"
    assert "refused" in run_flows.summary([out])


def test_a_met_floor_is_not_refused(tmp_path):
    out = _run(_spec(tmp_path), FakeHelper("Thing", {"thing_list": ""}), "0.5.225+preview.gabc")
    assert out.status == run_flows.PASS


def test_one_red_flow_among_green_ones_reddens_the_leg(tmp_path):
    ok = run_flows.FlowOutcome("a", "CSD-900", run_flows.PASS)
    refused = run_flows.FlowOutcome("b", "CSD-900", run_flows.REFUSED)
    bad = run_flows.FlowOutcome("c", "CSD-900", run_flows.FAIL)
    assert run_flows.leg_ok([ok, refused])
    assert not run_flows.leg_ok([ok, refused, bad])


# ── `state:` is checked now, against the CSD's `states:` ────────────────────

STATE_FLOW = GOOD.replace("visible: [thing_list]", "state: empty")


def test_state_holds_when_its_tag_is_on_screen_and_no_other_states_is(tmp_path):
    out = _run(_spec(tmp_path, STATE_FLOW), FakeHelper("Thing", {"thing_empty": ""}))
    assert out.status == run_flows.PASS


def test_state_fails_when_its_tag_is_not_on_screen(tmp_path):
    out = _run(_spec(tmp_path, STATE_FLOW), FakeHelper("Thing", {"other": ""}))
    assert out.status == run_flows.FAIL and "thing_empty" in out.detail


def test_state_fails_when_another_states_tag_is_also_on_screen(tmp_path):
    """Empty and populated at once is not 'empty' — the point of the map."""
    out = _run(_spec(tmp_path, STATE_FLOW),
               FakeHelper("Thing", {"thing_empty": "", "thing_list": ""}))
    assert out.status == run_flows.FAIL and "populated" in out.detail


def test_state_with_no_csd_map_fails_rather_than_passing(tmp_path):
    """Upstream's `state:` was a no-op. Unanchored, it now refuses to be green."""
    from testing.gate.flow_spec import FlowRunner
    spec = FlowSpec.load(_flow(tmp_path, STATE_FLOW.replace("    csd: CSD-900\n", "")))
    runner = FlowRunner(FakeHelper("Thing", {"thing_empty": ""}))
    assert asyncio.run(runner.run(spec)) is False
    assert "cannot be checked" in runner.results[-1].detail


# ── the workflow runs them on every leg ─────────────────────────────────────

def test_every_leg_of_the_matrix_runs_the_flows():
    wf = yaml.safe_load((REPO / ".github" / "workflows" / "five-platform-live-qa.yml").read_text())
    legs = 0
    for job in wf["jobs"].values():
        for step in job.get("steps", []):
            body = str(step.get("run", "")) + str((step.get("with") or {}).get("script", ""))
            body = body.replace("\\\n", " ")  # one logical command per line
            for call in re.findall(r"testing\.gate\.run_platform[^;\n]*", body):
                legs += 1
                assert "--flows testing/flows" in call, f"a leg runs the smoke walk without flows: {call}"
    assert legs == 5, f"expected five run_platform legs, found {legs}"


# ── run_platform: flows ride the smoke walk, never ahead of it ──────────────

def test_a_flow_that_does_not_load_stops_the_leg_before_anything_boots(tmp_path, monkeypatch):
    from testing.gate import run_platform
    bad = tmp_path / "flows"
    bad.mkdir()
    _flow(bad, GOOD.replace("CSD-900", "CSD-999"))
    booted = []
    monkeypatch.setattr(run_platform, "plan_for", lambda a: booted.append(a))
    report = tmp_path / "r.json"
    monkeypatch.setattr("sys.argv", ["run_platform", "--platform", "desktop", "--jar", "x.jar",
                                     "--shots", str(tmp_path / "s"), "--report", str(report),
                                     "--flows", str(bad)])
    assert run_platform.main() == 1
    assert booted == [], "a spec error must be found before the app is brought up"
    got = yaml.safe_load(report.read_text())
    assert got["steps"][0]["name"] == "flows-load" and not got["ok"]


def test_flows_do_not_run_after_a_failed_smoke_walk(tmp_path):
    from types import SimpleNamespace
    from testing.gate import run_platform
    rep = run_platform.Report(platform="desktop")
    rep.add("ui-composed", False, "never composed")
    run_platform.flows(None, rep, [_spec(tmp_path)], SimpleNamespace(), None)
    assert rep.steps[-1].name == "flows" and not rep.steps[-1].ok
    assert "not run" in rep.steps[-1].detail


# ── navigation: the runner walks to a flow's first screen ───────────────────

HOPS = {"Thing": ["circle_x", "tab_y", "nav_thing"]}


def _walkable(missing: str = "") -> FakeHelper:
    """Lands on Contacts; circle_x -> tab_y -> nav_thing reaches Thing."""
    h = FakeHelper("Contacts", {"circle_x": ""})
    h.leads = {
        "circle_x": ("CircleTab", ["circle_x", "tab_y"]),
        "tab_y": ("CircleTab", ["circle_x", "tab_y", "nav_thing"]),
        "nav_thing": ("Thing", ["thing_list"]),
    }
    if missing:
        for screen, shown in h.leads.values():
            if missing in shown:
                shown.remove(missing)
    return h


def _nav_run(spec, helper, hops=HOPS, flow_only=frozenset()):
    return asyncio.run(run_flows.run_one(spec, helper, client_version="0.5.224",
                                         start_timeout=0, hops=hops, flow_only=flow_only))


def test_the_runner_walks_the_derived_hop_to_the_first_screen(tmp_path):
    h = _walkable()
    out = _nav_run(_spec(tmp_path), h)
    assert out.status == run_flows.PASS, out.detail
    assert [c for c in h.calls if c.startswith("click")] == [
        "click circle_x", "click tab_y", "click nav_thing"]


def test_a_missing_hop_tag_is_cannot_start_and_names_the_tag(tmp_path):
    out = _nav_run(_spec(tmp_path), _walkable(missing="tab_y"))
    assert out.status == run_flows.CANNOT_START
    assert "'tab_y'" in out.detail and "hop 2 of 3" in out.detail
    assert "never appeared" in out.detail, "waited for, not blindly clicked"
    assert not run_flows.leg_ok([out])


def test_a_screen_with_no_hop_that_is_not_flow_only_cannot_start(tmp_path):
    h = _walkable()
    out = _nav_run(_spec(tmp_path), h, hops={})
    assert out.status == run_flows.CANNOT_START
    assert "no nav hop for Screen.Thing" in out.detail
    assert not [c for c in h.calls if c.startswith("click")], "nothing to walk, nothing clicked"


def test_a_flow_only_screen_is_waited_for_not_walked_to(tmp_path):
    """No hop exists, and that is not a defect: the flow must already be there."""
    h = FakeHelper("Thing", {"thing_list": ""})
    out = _nav_run(_spec(tmp_path), h, hops={}, flow_only={"Thing"})
    assert out.status == run_flows.PASS
    elsewhere = FakeHelper("Contacts", {"thing_list": ""})
    out = _nav_run(_spec(tmp_path), elsewhere, hops={}, flow_only={"Thing"})
    assert out.status == run_flows.CANNOT_START and "Thing" in out.detail
    assert "no nav hop" not in out.detail, "flow-only is not a missing hop"


def test_already_on_the_first_screen_walks_nothing(tmp_path):
    h = FakeHelper("Thing", {"thing_list": ""})
    assert _nav_run(_spec(tmp_path), h).status == run_flows.PASS
    assert not [c for c in h.calls if c.startswith("click")]


def test_the_real_nav_map_reaches_the_seeded_flows_first_screens():
    hops, flow_only = run_flows.nav_hops(has_agent=False)
    for spec in run_flows.load_flows([FLOWS]):
        start = spec.steps[0].requires.screen
        assert start in hops or start in flow_only, f"{spec.flow}: no way to Screen.{start}"
