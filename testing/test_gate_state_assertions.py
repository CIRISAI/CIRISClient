"""The `/state` step is a CHECK now, and these are its red paths (CIRISClient#48).

It used to be `rep.add("state", True, …)` — a step that printed `clientMode` and
`nodeUrl` and asserted neither, sitting inside a file whose own docstring is
titled "WHAT MAKES THIS A GATE RATHER THAN A REPORT". A step that cannot fail is
the thing this repo keeps finding, and it was in the gate built to find it.

Both values are assertable at launch, with no navigation, because the five-platform
gate always stands up a bare `ciris-server`: no brain, and the node's own port.
That is precisely the shape of the two defects that cost this month:

  * #48 — a `clientMode` of AGENT derived against `:8080` that outlived the
    backend it described, so the client waited 30s for a brain setup had removed.
  * the gate's own bug — every leg polled `:8080/v1/system/health` at a node that
    binds `:4243`, and reported "the node never became healthy" while it served.

Neither needed a device to catch. Both needed someone to assert.
"""

from __future__ import annotations

import pytest

from testing.gate.run_platform import state_problems

NODE_URL = "http://127.0.0.1:4243"
AGENT_URL = "http://127.0.0.1:8080"


def test_a_node_reporting_itself_a_node_is_clean():
    assert state_problems("NODE", NODE_URL) == []


def test_an_unprobed_gate_is_accepted_and_not_a_failure():
    """`unset` is a real state, not a defect.

    The probe may not have answered when the walk runs, and CIRISClient#48's fix
    turns on `null` meaning "not asked yet". Failing the leg for it would make
    the gate flaky AND would punish the client for being honest — the client now
    says `unset` precisely so nobody infers AGENT from silence.
    """
    assert state_problems("unset", NODE_URL) == []
    assert state_problems("", NODE_URL) == []


def test_AGENT_against_a_bare_node_fails():
    """THE #48 SHAPE. There is no brain folded on this gate's node, so a client
    that says AGENT is reporting a verdict it cannot have earned here."""
    problems = state_problems("AGENT", NODE_URL)
    assert problems, "a client claiming AGENT against a brainless node must fail the leg"
    assert "AGENT" in problems[0]


def test_pointing_at_the_agent_port_fails():
    """THE GATE'S OWN SHAPE. `:8080` is the agent's; a bare ciris-server binds
    :4242 and :4243 and never 8080."""
    problems = state_problems("NODE", AGENT_URL)
    assert problems and "8080" in problems[0]


def test_both_wrong_reports_both():
    # One failing step should name everything wrong with it, not stop at the
    # first — a leg re-run to discover the second problem is a leg wasted.
    assert len(state_problems("AGENT", AGENT_URL)) == 2


@pytest.mark.parametrize("mode", ["node", "Node", "NODE"])
def test_the_mode_comparison_is_case_insensitive(mode):
    # /state is a string field with no schema; a client that changes its casing
    # must not silently turn this check green.
    assert state_problems(mode, NODE_URL) == []


@pytest.mark.parametrize("mode", ["agent", "Agent", "AGENT"])
def test_and_so_is_the_failing_one(mode):
    assert state_problems(mode, NODE_URL)


def test_an_absent_node_url_is_not_read_as_the_agent_port():
    # Absent means "the client did not say", which is not evidence of :8080.
    # Reading an empty string as a failure is the distinct-zeroes mistake.
    assert state_problems("NODE", "") == []
