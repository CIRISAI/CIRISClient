"""`${NAME}` substitution and the `fixture:` key — the flow runner's side of the
two-node fixture (testing/gate/two_node.py). Stdlib + PyYAML, no app, no node."""

from __future__ import annotations

import asyncio

import pytest

from testing.gate import run_flows
from testing.gate.flow_spec import (
    FlowSpec,
    SpecError,
    UnresolvedVariable,
    resolve_step,
    substitute,
)
from testing.test_flows import FakeHelper, _csd_root, _flow

FIXTURE_FLOW = """\
    flow: receipt
    csd: CSD-900
    client: ">=0.5.224"
    fixture: two_node
    steps:
      - step_id: land
        title: lands
        requires:
          screen: Thing
        do:
          - click: "btn_receipt_${PEER_KEY_ID}"
        expect:
          visible: ["row_${PEER_KEY_ID}"]
          matches: {"row_${PEER_KEY_ID}": "${PEER_KEY_ID}"}
"""

PLAIN_FLOW = """\
    flow: plain
    csd: CSD-900
    client: ">=0.5.224"
    steps:
      - step_id: land
        title: lands
        requires:
          screen: Thing
        expect:
          visible: [thing_list]
"""


def _load(tmp_path, body, name="f.yaml"):
    return FlowSpec.load(_flow(tmp_path, body, name), csd_root=_csd_root(tmp_path))


# ── substitute ──────────────────────────────────────────────────────────────

def test_a_known_name_is_replaced_everywhere_it_occurs():
    assert substitute("btn_receipt_${K}_${K}", {"K": "ab-1"}) == "btn_receipt_ab-1_ab-1"


def test_text_with_no_names_is_returned_untouched():
    assert substitute("btn_contacts_refresh", {}) == "btn_contacts_refresh"


def test_a_name_the_fixture_did_not_produce_raises_and_says_why():
    with pytest.raises(UnresolvedVariable, match=r"\$\{MESSAGE_ATTESTATION_ID\}.*did not arrive"):
        substitute("chat_msg_${MESSAGE_ATTESTATION_ID}", {"PEER_KEY_ID": "x"},
                   ["the peer's message did not arrive within 120s"])


def test_an_empty_value_is_as_missing_as_an_absent_one():
    """An empty key id would turn `btn_receipt_${K}` into `btn_receipt_`, which
    a glob-ish reader could mistake for a class of tags. Refused."""
    with pytest.raises(UnresolvedVariable):
        substitute("btn_receipt_${K}", {"K": ""})


def test_a_regex_value_is_escaped_so_a_key_id_matches_itself_only():
    assert substitute("^${K}$", {"K": "a.b+c"}, escape=True) == r"^a\.b\+c$"


def test_lowercase_dollar_braces_are_not_names():
    """Kotlin's `${peer.keyId}` in a flow's COMMENT-like text is not ours."""
    assert substitute("peer_pick_row_${peer.keyId}", {}) == "peer_pick_row_${peer.keyId}"


# ── loading ─────────────────────────────────────────────────────────────────

def test_a_fixture_flow_loads_and_lists_its_names(tmp_path):
    spec = _load(tmp_path, FIXTURE_FLOW)
    assert spec.fixture == "two_node"
    assert spec.variables() == ["PEER_KEY_ID"]


def test_a_name_with_no_fixture_to_fill_it_is_a_load_error(tmp_path):
    body = FIXTURE_FLOW.replace("    fixture: two_node\n", "")
    with pytest.raises(SpecError, match=r"\$\{PEER_KEY_ID\}.*no `fixture:`"):
        _load(tmp_path, body)


def test_an_unknown_fixture_is_a_load_error(tmp_path):
    with pytest.raises(SpecError, match="fixture: 'three_node'"):
        _load(tmp_path, FIXTURE_FLOW.replace("two_node", "three_node"))


def test_resolving_a_step_leaves_the_loaded_spec_untouched(tmp_path):
    spec = _load(tmp_path, FIXTURE_FLOW)
    step = resolve_step(spec.steps[0], {"PEER_KEY_ID": "k1"})
    assert step.do[0].target == "btn_receipt_k1"
    assert step.expect.visible == ["row_k1"]
    assert step.expect.matches == {"row_k1": "k1"}
    assert spec.steps[0].do[0].target == "btn_receipt_${PEER_KEY_ID}"


# ── running ─────────────────────────────────────────────────────────────────

def _run(spec, helper, **kw):
    return asyncio.run(run_flows.run_one(spec, helper, client_version="0.5.224",
                                         start_timeout=0, **kw))


def test_the_runner_clicks_the_tag_the_fixture_named(tmp_path):
    helper = FakeHelper("Thing", {"btn_receipt_k1": "", "row_k1": "k1"})
    out = _run(_load(tmp_path, FIXTURE_FLOW), helper, variables={"PEER_KEY_ID": "k1"})
    assert out.status == run_flows.PASS, out.detail
    assert "click btn_receipt_k1" in helper.calls


def test_a_missing_value_fails_the_step_naming_the_fixtures_reason(tmp_path):
    helper = FakeHelper("Thing", {"btn_receipt_k1": ""})
    out = _run(_load(tmp_path, FIXTURE_FLOW), helper, variables={},
               variable_notes=["the peer's owner key never crossed"])
    assert out.status == run_flows.CANNOT_START  # the first step's requires
    assert "PEER_KEY_ID" in out.detail and "never crossed" in out.detail
    assert not any(c.startswith("click") for c in helper.calls), "nothing literal was clicked"


def test_a_missing_value_fails_even_an_optional_step(tmp_path):
    """A flow that asked for a two-node value and did not get it is broken,
    not 'not applicable here'."""
    body = FIXTURE_FLOW.replace("        title: lands\n", "        title: lands\n        optional_step: true\n")
    out = _run(_load(tmp_path, body), FakeHelper("Thing", {}), variables={})
    assert out.status != run_flows.PASS


# ── run_all: who pays for the fixture, and when ─────────────────────────────

class _Fixture:
    def __init__(self, log, values=None, fail=None):
        self.log, self._values, self.fail = log, values, fail
        self.values = type("V", (), {"notes": ["a note"]})()

    def up(self):
        self.log.append("up")
        if self.fail:
            raise RuntimeError(self.fail)
        return dict(self._values or {})

    def down(self):
        self.log.append("down")


def _all(specs, helper, fixtures):
    return run_flows.run_all(specs, None, client_version="0.5.224", helper=helper,
                             establish_session=False, navigate_to_start=False,
                             fixtures=fixtures)


def test_plain_flows_run_before_any_fixture_whatever_the_file_order(tmp_path):
    fx = _load(tmp_path, FIXTURE_FLOW, "a.yaml")
    plain = _load(tmp_path, PLAIN_FLOW, "b.yaml")
    assert [s.flow for s in run_flows.fixture_order([fx, plain])] == ["plain", "receipt"]


def test_a_run_with_no_fixture_flow_never_stands_one_up(tmp_path):
    log: list = []
    _all([_load(tmp_path, PLAIN_FLOW)], FakeHelper("Thing", {"thing_list": ""}),
         lambda name: _Fixture(log))
    assert log == []


def test_the_fixture_is_up_once_and_down_after_even_when_a_flow_fails(tmp_path):
    log: list = []
    specs = [_load(tmp_path, FIXTURE_FLOW, "a.yaml"),
             _load(tmp_path, FIXTURE_FLOW.replace("flow: receipt", "flow: receipt2"), "b.yaml")]
    outs = _all(specs, FakeHelper("Thing", {}), lambda name: _Fixture(log, {"PEER_KEY_ID": "k1"}))
    assert log == ["up", "down"]
    assert all(o.status == run_flows.FAIL for o in outs)


def test_a_fixture_that_cannot_stand_up_leaves_its_flows_cannot_start(tmp_path):
    log: list = []
    specs = [_load(tmp_path, PLAIN_FLOW, "p.yaml"), _load(tmp_path, FIXTURE_FLOW, "f.yaml")]
    outs = _all(specs, FakeHelper("Thing", {"thing_list": ""}),
                lambda name: _Fixture(log, fail="no claim PIN"))
    by = {o.flow: o for o in outs}
    assert by["plain"].status == run_flows.PASS
    assert by["receipt"].status == run_flows.CANNOT_START
    assert "no claim PIN" in by["receipt"].detail
    assert log == ["up", "down"], "a half-started fixture is still torn down"
    assert not run_flows.leg_ok(outs)


def test_no_fixture_provider_is_cannot_start_saying_what_was_missing(tmp_path):
    outs = _all([_load(tmp_path, FIXTURE_FLOW)], FakeHelper("Thing", {}), None)
    assert outs[0].status == run_flows.CANNOT_START
    assert "--node-binary" in outs[0].detail


def test_a_refused_fixture_flow_does_not_stand_the_fixture_up(tmp_path):
    log: list = []
    spec = _load(tmp_path, FIXTURE_FLOW.replace('">=0.5.224"', '"unreleased"'))
    outs = _all([spec], FakeHelper("Thing", {}), lambda name: _Fixture(log))
    assert outs[0].status == run_flows.REFUSED
    assert log == [], "a flow its floor refuses must not cost a second node"
