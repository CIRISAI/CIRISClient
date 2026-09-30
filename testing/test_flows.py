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


def _a_real_proposed_tag():
    """(CSD id, a tag it still marks `proposed:`) from the shipped documents.

    Derived, not named: this test used CSD-005's `contacts_row_trust`, and the
    chip shipped — the document stopped marking it proposed and the test went
    red for the product doing its job. Any real CSD with a proposed tag proves
    the same thing."""
    import re as _re  # noqa: PLC0415
    from testing.gate.csd_doc import CsdError, load  # noqa: PLC0415
    for path in sorted((REPO / "FSD" / "CSD").glob("CSD-*.md")):
        m = _re.match(r"(CSD-\d+)", path.name)
        try:
            doc = load(m.group(1)) if m else None
        except CsdError:
            continue
        if doc is not None and doc.proposed:
            return doc.csd_id, sorted(doc.proposed)[0]
    return None


def test_a_real_csd_refuses_a_tag_it_still_marks_proposed(tmp_path):
    """Against a shipped document, not a fixture: a tag a real CSD still marks
    `proposed:` may not be asserted by a flow."""
    found = _a_real_proposed_tag()
    if found is None:
        pytest.skip("no shipped CSD marks any tag proposed")
    csd_id, tag = found
    body = f"""\
        flow: p
        csd: {csd_id}
        steps:
          - step_id: s
            title: t
            expect:
              visible: [{tag}]
    """
    with pytest.raises(SpecError, match=f"{tag}.*proposed"):
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
    # A fixture-filled tag (`btn_receipt_${PEER_KEY_ID}`): its literal head must
    # BE one of the client's interpolated prefixes — the client builds exactly
    # `"btn_receipt_$keyId"` — not merely start like one.
    if "${" in tag:
        return tag.split("${", 1)[0] in prefixes
    return tag in literals or any(tag.startswith(p) for p in prefixes)


@pytest.mark.parametrize("tag,carried", [
    ("age_band_adult", True),        # "age_band_$token"      SetupScreen.kt
    ("trace_consent_yes", True),     # "trace_consent_$token" SetupScreen.kt
    ("radio_cohort_family", True),   # "radio_cohort_$value"  ClaimNodeScreen.kt
    ("chk_duty_box_accept", True),   # "chk_duty_box_$verb"   DutyConferralScreen.kt
    ("opt_run_with_ai", True),       # a whole literal still matches
    ("contacts_no_such_tag", False),
    ("btn_no_such_button", False),   # a one-segment prefix vouches for nothing
    ("btn_receipt_${PEER_KEY_ID}", True),    # "btn_receipt_$keyId"  PeopleSupport.kt
    ("chat_msg_${MESSAGE_ATTESTATION_ID}", True),
    ("btn_no_such_${PEER_KEY_ID}", False),  # a fixture value vouches for nothing either
])
def test_the_client_tag_check_sees_interpolated_tags_and_nothing_else(tag, carried):
    assert client_carries(tag, *_client_tag_strings()) is carried


def test_every_tag_a_seeded_flow_names_exists_in_the_client():
    """A flow naming a tag the client does not carry fails as 'element not
    found' on every leg. Checked here, at the keyboard, rather than there."""
    literals, prefixes = _client_tag_strings()
    missing = []
    for spec in run_flows.load_flows([FLOWS]):
        missing += [f"{spec.flow}/cleanup: {a.target}" for a in spec.cleanup
                    if not client_carries(a.target, literals, prefixes)]
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
        # The shell's own account of where it stands (`/state`): a circle click
        # lands at once here; `_RacyShell` below is the one that lands late.
        self.circle, self.tab = "", ""
        # Tags that are on screen but whose click the app refuses, and the
        # reason the last refusal gave (what the real helper keeps).
        self.refuse: set = set()
        self.last_error = ""

    async def get_state(self):
        self.calls.append("state")
        return {"screen": self.screen, "circle": self.circle, "tab": self.tab}

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
        # Geometry, as the real helper reads it: composed with zero size is
        # off screen (a row below the fold), not on screen.
        e = self.els.get(tag)
        if e is None:
            return False
        return e.visible if e.visible is not None else (e.width > 0 and e.height > 0)

    async def scroll_into_view(self, tag):
        self.calls.append(f"scroll {tag}")
        # This screen has nothing the harness can scroll; say so, as the real
        # helper keeps what `/scroll` answered.
        self.last_scroll = ["down: nothing on screen can scroll (no testableVerticalScroll registered)"]
        return await self.is_element_visible(tag)

    async def click(self, tag, timeout=2000):
        self.calls.append(f"click {tag}")
        if tag not in self.els or tag in self.refuse:
            self.last_error = (f"no such element {tag!r}" if tag not in self.els
                               else f"HTTP 404: No click handler for {tag!r}")
            return False
        if tag.startswith("circle_"):
            self.circle = tag[len("circle_"):].replace("_", "-")
        elif tag.startswith("tab_"):
            self.tab = tag[len("tab_"):]
        # A click can move the app: `leads` maps a tag to (screen, tags now shown).
        if tag in self.leads:
            self.screen, shown = self.leads[tag]
            self.els = {t: _El(t, "") for t in shown}
        return True

    async def click_refused(self, tag, timeout=2000):
        self.calls.append(f"click_refused {tag}")
        if tag not in self.els:
            self.last_error = f"no such element {tag!r}"
            return False
        if tag not in self.refuse:
            self.last_error = f"{tag} accepted the click and its handler ran"
            return False
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


CLEANUP = GOOD + """\
    cleanup:
      - click: btn_close
"""


def test_a_flows_cleanup_runs_even_after_a_failed_step(tmp_path):
    """csd_092 opened the contact-code card, failed on its second step, and
    left the card open; `people`, `csd_005` and `csd_006` then failed for its
    reason on every desktop leg (2026-09-29). Only the flow knows what it
    opened; the runner guarantees the closing runs."""
    h = FakeHelper("Thing", {"btn_close": ""})  # thing_list absent: the step fails
    out = _run(_spec(tmp_path, CLEANUP), h)
    assert out.status == run_flows.FAIL
    assert "click btn_close" in h.calls


def test_a_flows_cleanup_runs_after_a_pass_too(tmp_path):
    h = FakeHelper("Thing", {"thing_list": "", "btn_close": ""})
    out = _run(_spec(tmp_path, CLEANUP), h)
    assert out.status == run_flows.PASS
    assert h.calls[-1] == "click btn_close"


def test_a_cleanup_that_fails_is_said_and_is_not_the_verdict(tmp_path):
    h = FakeHelper("Thing", {"thing_list": "", "btn_close": ""})
    h.refuse = {"btn_close"}  # on screen, and the app refuses the click
    out = _run(_spec(tmp_path, CLEANUP), h)
    assert out.status == run_flows.PASS
    assert "cleanup" in out.detail and "btn_close" in out.detail and "No click handler" in out.detail


def test_a_cleanup_whose_target_is_already_gone_is_nothing_to_close(tmp_path):
    """csd_092's own last step closes the card it opened; its cleanup then
    finds nothing to close, and that is not a failure to report."""
    h = FakeHelper("Thing", {"thing_list": ""})  # btn_close absent
    out = _run(_spec(tmp_path, CLEANUP), h)
    assert out.status == run_flows.PASS
    assert "cleanup" not in out.detail, out.detail


CLEANUP_WHEN = GOOD + """\
    cleanup:
      - click: btn_toggle
        when: card_open
"""


def test_a_cleanup_guarded_by_when_runs_only_while_its_card_is_open(tmp_path):
    """macOS, run 36600766576: csd_005 left People's add card open (its last
    step provokes a refusal in it) and csd_006's row sat below the fold. The
    control that closes the card is the header toggle that also OPENS it, so
    "already gone" has to be decided by the card, not by the toggle."""
    h = FakeHelper("Thing", {"thing_list": "", "btn_toggle": "", "card_open": ""})
    out = _run(_spec(tmp_path, CLEANUP_WHEN), h)
    assert out.status == run_flows.PASS
    assert h.calls[-1] == "click btn_toggle"
    assert "cleanup" not in out.detail, out.detail

    h = FakeHelper("Thing", {"thing_list": "", "btn_toggle": ""})  # the toggle is there, the card is not
    out = _run(_spec(tmp_path, CLEANUP_WHEN), h)
    assert out.status == run_flows.PASS
    assert "click btn_toggle" not in h.calls, "a guarded cleanup must not open what it is there to close"
    assert "cleanup" not in out.detail, out.detail


CLEANUP_AFTER_CLOSE = GOOD + """\
    cleanup:
      - click: btn_code_close
      - click: btn_toggle
        when: card_open
"""


def test_a_cleanup_guard_reads_the_frame_after_the_previous_close(tmp_path):
    """Local Linux leg, 2026-09-29: closing the contact-code card brought
    People's add card back on the NEXT frame; the guard read the same
    instant, saw no card, skipped the toggle, and csd_006 started under the
    open card after all."""
    h = _NextFrame("Thing", {"thing_list": "", "btn_code_close": "", "btn_toggle": ""})
    # Closing the code card lands a frame later, and only then is the add card back.
    h.leads = {"btn_code_close": ("Thing", ["thing_list", "btn_toggle", "card_open"])}
    out = _run(_spec(tmp_path, CLEANUP_AFTER_CLOSE), h)
    assert out.status == run_flows.PASS
    assert h.calls[-1] == "click btn_toggle", h.calls
    assert "cleanup" not in out.detail, out.detail


def test_when_is_for_cleanup_actions_only(tmp_path):
    """A step's action that quietly does nothing is a step that asserts nothing."""
    body = GOOD.replace("        expect:\n", "        do:\n          - wait: thing_list\n            when: thing_list\n        expect:\n")
    assert "when: thing_list" in body
    with pytest.raises(SpecError, match="cleanup"):
        _spec(tmp_path, body)


def test_a_visible_tag_that_was_never_composed_says_so(tmp_path):
    h = FakeHelper("Thing", {})  # thing_list is not in /tree at all
    out = _run(_spec(tmp_path), h)
    assert out.status == run_flows.FAIL
    assert "'thing_list' is not composed" in out.detail, out.detail


def test_a_visible_tag_composed_below_the_fold_says_off_screen_after_scrolling(tmp_path):
    """The macOS csd_006 shape: the row is in /tree with clipped, zero-size
    bounds. "Is not on screen" sent the reader to the client; the row was
    there, under a card the previous flow had left open, on a screen the
    harness could not scroll — which is what the message now says."""
    h = FakeHelper("Thing", {"thing_list": ""})
    h.els["thing_list"] = _El("thing_list", "", visible=None, width=0, height=0)
    out = _run(_spec(tmp_path), h)
    assert out.status == run_flows.FAIL
    assert "'thing_list' is composed but off screen after scrolling" in out.detail, out.detail
    assert "no testableVerticalScroll" in out.detail, "what /scroll answered belongs in the verdict"
    assert "scroll thing_list" in h.calls, "the runner tried to bring it on screen first"


class _NextFrame(FakeHelper):
    """A click whose effect lands on the next frame — 0.3 s later on the wall
    clock, as a Compose recomposition does after `/click` returns. Reading the
    tree in the same instant sees the old screen."""

    def __init__(self, *a, **kw):
        super().__init__(*a, **kw)
        self.lands_at = None

    async def click(self, tag, timeout=2000):
        import time as _t
        if tag in self.leads:
            self.calls.append(f"click {tag}")
            self.lands_at = (_t.monotonic() + 0.3, self.leads[tag])
            return True
        return await super().click(tag, timeout)

    def _land(self):
        import time as _t
        if self.lands_at and _t.monotonic() >= self.lands_at[0]:
            self.screen, shown = self.lands_at[1]
            self.els = {t: _El(t, "") for t in shown}
            self.lands_at = None

    async def get_elements(self):
        self._land()
        return await super().get_elements()

    async def get_element(self, tag):
        self._land()
        return await super().get_element(tag)

    async def get_screen(self):
        self._land()
        return await super().get_screen()


def test_an_expect_after_an_action_waits_for_the_frame(tmp_path):
    """csd_057's back click 'succeeded' and the same-instant expect still saw
    `card_wallet_balance` (local Linux leg, 2026-09-29): the click returns
    before the frame that applies it. An assertion made in the instant of the
    click is a race, not a test — the same rule `navigate` already states."""
    body = GOOD.replace("        expect:\n          visible: [thing_list]\n",
                        "        do:\n          - click: btn_back\n        expect:\n"
                        "          absent: [thing_list]\n          screen: Elsewhere\n")
    h = _NextFrame("Thing", {"thing_list": "", "btn_back": ""})
    h.leads = {"btn_back": ("Elsewhere", ["other"])}
    out = _run(_spec(tmp_path, body), h)
    assert out.status == run_flows.PASS, out.detail


def test_an_expect_that_never_holds_still_fails_and_is_bounded(tmp_path):
    import time as _t
    body = GOOD.replace("visible: [thing_list]", "visible: [thing_list, never_there]")
    started = _t.monotonic()
    out = _run(_spec(tmp_path, body), FakeHelper("Thing", {"thing_list": ""}))
    assert out.status == run_flows.FAIL and "never_there" in out.detail
    assert _t.monotonic() - started < 10


def test_a_failed_action_carries_the_drivers_reason(tmp_path):
    """"did not succeed" was the whole verdict on csd_047; the driver knew why."""
    body = GOOD.replace("        expect:\n", "        do:\n          - click: btn_gone\n        expect:\n")
    out = _run(_spec(tmp_path, body), FakeHelper("Thing", {"thing_list": ""}))
    assert out.status == run_flows.FAIL
    assert "no such element 'btn_gone'" in out.detail, out.detail


REFUSED = GOOD.replace("        expect:\n", "        do:\n          - click_refused: btn_submit\n        expect:\n", 1)


def test_click_refused_parses_as_its_own_verb(tmp_path):
    spec = _spec(tmp_path, REFUSED)
    act = spec.steps[0].do[0]
    assert (act.kind, act.target) == ("click_refused", "btn_submit")
    assert act.describe() == "click_refused 'btn_submit'"


def test_click_refused_is_one_verb_among_the_others(tmp_path):
    body = GOOD.replace("        expect:\n",
                        "        do:\n          - {click_refused: btn_submit, click: btn_submit}\n        expect:\n", 1)
    with pytest.raises(SpecError, match="exactly one of"):
        _spec(tmp_path, body)


def test_a_refused_click_passes_the_step_that_claims_it_does_nothing(tmp_path):
    """csd_068 `empty_submit_does_nothing` on iOS, run 36733112700: the
    platform refused the disabled submit, and a plain `click:` called that a
    failure. The refusal is the outcome the step claims."""
    h = FakeHelper("Thing", {"thing_list": "", "btn_submit": ""})
    h.refuse = {"btn_submit"}
    out = _run(_spec(tmp_path, REFUSED), h)
    assert out.status == run_flows.PASS, out.detail
    assert "click_refused btn_submit" in h.calls


def test_a_disabled_control_whose_handler_fires_fails_click_refused(tmp_path):
    h = FakeHelper("Thing", {"thing_list": "", "btn_submit": ""})
    out = _run(_spec(tmp_path, REFUSED), h)
    assert out.status == run_flows.FAIL
    assert "handler ran" in out.detail, out.detail


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
    # THE EVIDENCE TRAVELS WITH THE VERDICT. Four cannot-starts on the
    # 2026-09-29 run said "never appeared ... on 'CircleTab'" and nothing
    # else; which rows WERE listed was the fact that named the cause.
    assert "on screen and drivable now" in out.detail and "circle_x" in out.detail
    assert not run_flows.leg_ok([out])


# ── the circle hop must LAND before the tab is clicked ──────────────────────

class _RacyShell(FakeHelper):
    """`CIRISApp.openTab` as it behaves: a circle click changes the circle on
    the NEXT FRAME (modelled as the next `/state` read), and a tab click opens
    the tab of whichever circle the last frame saw. Sign-in lands a node
    client in Neighbours (`defaultCircle`), whose Chats tab is Rooms; the
    flow wants Just me › Chats › Notes."""

    def __init__(self):
        super().__init__("Contacts", {"circle_agent": "", "tab_chats": ""})
        self.circle, self.tab, self.pending = "local-community", "people", None

    async def get_state(self):
        self.calls.append("state")
        if self.pending:
            self.circle, self.pending = self.pending, None
        return {"screen": self.screen, "circle": self.circle, "tab": self.tab}

    async def click(self, tag, timeout=2000):
        self.calls.append(f"click {tag}")
        if tag.startswith("circle_"):
            self.pending = tag[len("circle_"):].replace("_", "-")
            return True
        if tag == "tab_chats":
            self.tab = "chats"
            self.screen = "Notes" if self.circle == "agent" else "CommunityChats"
            body = "input_note" if self.screen == "Notes" else "rooms_list"
            self.els = {t: _El(t, "") for t in ("circle_agent", "tab_chats", body)}
            return True
        return tag in self.els


def test_navigate_waits_for_the_circle_hop_to_land_before_the_tab():
    """macOS, 2026-09-29: `circle_agent -> tab_chats` landed on CommunityChats —
    the tab was clicked with the circle the previous frame had."""
    h = _RacyShell()
    got = asyncio.run(run_flows.navigate(h, "Notes", ["circle_agent", "tab_chats"],
                                         hop_timeout=1.0, arrive_timeout=0.1))
    assert got is None, got
    assert h.screen == "Notes"
    assert h.calls.index("state") < h.calls.index("click tab_chats"), \
        "the circle was read back before the tab was clicked"


def test_a_circle_hop_that_never_lands_is_named_as_the_circle_not_the_row():
    h = _RacyShell()

    async def stuck():
        h.calls.append("state")
        return {"screen": h.screen, "circle": "local-community", "tab": h.tab}
    h.get_state = stuck
    got = asyncio.run(run_flows.navigate(h, "Notes", ["circle_agent", "tab_chats"],
                                         hop_timeout=0.3, arrive_timeout=0.1))
    assert got and "circle_agent" in got and "local-community" in got, got
    assert "click tab_chats" not in h.calls, "no tab is clicked in the wrong circle"


def test_a_client_whose_state_has_no_circle_is_walked_without_verification():
    """An older client serves no `circle` in /state: the runner cannot verify
    the hop, says so, and still walks it rather than refusing every flow."""
    h = _walkable()

    async def old_state():
        return {"screen": h.screen}
    h.get_state = old_state
    got = asyncio.run(run_flows.navigate(h, "Thing", ["circle_x", "tab_y", "nav_thing"],
                                         hop_timeout=0.2, arrive_timeout=0.1))
    assert got is None, got
    assert h.screen == "Thing"


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


def test_already_on_the_first_screen_still_walks_its_hop(tmp_path):
    """Being on the screen says nothing about WHICH circle it is shown in —
    Contacts sits in every circle's People tab — and the last flow left the
    shell wherever it left it. The hop is re-walked, and verified, so every
    flow starts from a known circle and tab, not from the previous flow's."""
    h = _walkable()
    h.screen = "Thing"
    h.els["thing_list"] = _El("thing_list", "")
    out = _nav_run(_spec(tmp_path), h)
    assert out.status == run_flows.PASS, out.detail
    assert [c for c in h.calls if c.startswith("click")] == [
        "click circle_x", "click tab_y", "click nav_thing"]


def test_the_real_nav_map_reaches_the_seeded_flows_first_screens():
    hops, flow_only = run_flows.nav_hops(has_agent=False)
    for spec in run_flows.load_flows([FLOWS]):
        start = spec.steps[0].requires.screen
        assert start in hops or start in flow_only, f"{spec.flow}: no way to Screen.{start}"


def test_navigate_opens_the_single_card_when_a_compact_tab_lists_it():
    """Phones list a one-card tab before opening it (iOS, #97); the runner
    opens the only row instead of reporting 'landed on CircleTab'."""
    import asyncio
    from testing.gate import run_flows

    class E:
        def __init__(self, t): self.test_tag = t

    class H:
        def __init__(self): self.screen = "Login"; self.clicked = []
        async def wait_for_element(self, tag, timeout=0): return True
        async def click(self, tag, timeout=0):
            self.clicked.append(tag)
            self.screen = {"tab_people": "CircleTab", "nav_epistemic_contacts": "Contacts"}.get(tag, self.screen)
            return True
        async def get_screen(self): return self.screen
        async def get_elements(self): return [E("nav_epistemic_contacts"), E("tab_people")]

    h = H()
    got = asyncio.run(run_flows.navigate(h, "Contacts", ["circle_agent", "tab_people"],
                                         hop_timeout=0.1, arrive_timeout=0.1))
    assert got is None, got
    assert h.clicked[-1] == "nav_epistemic_contacts"


def test_navigate_prefers_the_target_row_when_a_tab_lists_several():
    import asyncio
    from testing.gate import run_flows

    class E:
        def __init__(self, t): self.test_tag = t

    class H:
        def __init__(self): self.screen = "Login"; self.clicked = []
        async def wait_for_element(self, tag, timeout=0): return True
        async def click(self, tag, timeout=0):
            self.clicked.append(tag)
            self.screen = {"tab_people": "CircleTab", "nav_epistemic_contacts": "Contacts",
                           "nav_epistemic_community_roster": "CommunityRoster"}.get(tag, self.screen)
            return True
        async def get_screen(self): return self.screen
        async def get_elements(self):
            return [E("nav_epistemic_community_roster"), E("nav_epistemic_contacts")]

    h = H()
    got = asyncio.run(run_flows.navigate(h, "Contacts", ["circle_agent", "tab_people"],
                                         hop_timeout=0.1, arrive_timeout=0.1))
    assert got is None, got
    assert h.clicked[-1] == "nav_epistemic_contacts"
