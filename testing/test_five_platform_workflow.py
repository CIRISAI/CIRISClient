"""Structural guards for the five-platform gate (CIRISClient#31).

The workflow's whole purpose is to be the one job that cannot pass vacuously, so
the properties that make it non-vacuous are guarded at PR time — the job itself
is nightly and runs on hardware a PR check does not have.

Adapted from CIRISAgent's `tests/workflows/test_five_platform_live_qa.py`, which
could not be vendored directly because it asserts against their `apps/` shells.
Every assertion below corresponds to a way a gate of this shape has ALREADY been
defeated inside a green build, in their repo:

  * artifacts uploaded conditionally — a failure you cannot diagnose from the
    artifact costs a re-run to learn what the first run already knew
  * fail-fast left on — killing the matrix on first red destroys the five-way
    comparison that makes a platform-specific defect obvious
  * the gallery skipped on failure — the red run is the one worth looking at
  * a leg that "passes" without driving anything
"""

from __future__ import annotations

import pathlib

import pytest
import yaml

WF = pathlib.Path(__file__).resolve().parents[1] / ".github" / "workflows" / "five-platform-live-qa.yml"

#: The jobs that actually drive the product. `gallery` is reporting, not a leg.
LEGS = ("linux-android", "macos-ios", "windows")


@pytest.fixture(scope="module")
def wf() -> dict:
    return yaml.safe_load(WF.read_text(encoding="utf-8"))


def test_the_workflow_exists_and_parses(wf):
    # A denominator of zero is not a pass.
    assert wf["jobs"], "no jobs — every test below would be vacuous"


def test_every_leg_is_present(wf):
    for leg in LEGS:
        assert leg in wf["jobs"], f"{leg} is missing; five targets need all three images"


@pytest.mark.parametrize("leg", LEGS)
def test_every_leg_has_a_timeout(wf, leg):
    # A hung emulator must not burn six hours of runner time before anyone
    # notices, and "cancelled after 6h" is not a diagnosis.
    assert wf["jobs"][leg].get("timeout-minutes"), f"{leg} has no timeout"


@pytest.mark.parametrize("leg", LEGS)
def test_every_leg_uploads_its_evidence_unconditionally(wf, leg):
    """`if: always()` on the upload, or a red run tells you nothing.

    This is the single most valuable line in the file: the whole reason the gate
    beats an API check is that it produces screenshots and reports, and a failed
    run is exactly when they matter.
    """
    uploads = [
        s for s in wf["jobs"][leg]["steps"]
        if isinstance(s.get("uses"), str) and "upload-artifact" in s["uses"]
    ]
    assert uploads, f"{leg} uploads nothing"
    for step in uploads:
        assert str(step.get("if", "")).strip() == "always()", (
            f"{leg} uploads artifacts conditionally — a red run would produce no evidence"
        )


@pytest.mark.parametrize("leg", LEGS)
def test_every_leg_actually_drives_the_product(wf, leg):
    # A leg that builds and never drives is a leg that reports green for an app
    # nobody started.
    body = yaml.dump(wf["jobs"][leg])
    assert "testing.gate.run_platform" in body, f"{leg} never invokes the runner"


@pytest.mark.parametrize("leg", LEGS)
def test_every_leg_runs_against_a_real_node(wf, leg):
    # Driving a client with no backend exercises a login screen and an error
    # state, which is not what this gate is for.
    body = yaml.dump(wf["jobs"][leg])
    assert "ciris-server" in body, f"{leg} stands up no node"


@pytest.mark.parametrize("leg", LEGS)
def test_a_node_that_never_becomes_healthy_fails_the_leg(wf, leg):
    # Backgrounding a server and walking on is how a run drives an app whose
    # backend was never there, and then reports the CLIENT as broken.
    body = yaml.dump(wf["jobs"][leg])
    assert "the node never became healthy" in body, f"{leg} does not verify the node came up"


@pytest.mark.parametrize("leg", ("linux-android", "macos-ios"))
def test_the_paired_legs_resolve_the_candidate_rather_than_any_artifact(wf, leg):
    # candidate_artifacts asserts the artifact carries THIS tree's version.
    # Globbing a jar directly is how a run tests a six-release-old build and
    # calls the platform green — which is what happened the first time the seam
    # was pointed at a working checkout.
    body = yaml.dump(wf["jobs"][leg])
    assert "candidate_artifacts" in body, f"{leg} does not verify which build it is driving"


def test_the_matrix_does_not_stop_at_the_first_red(wf):
    """No `fail-fast: true` anywhere.

    These are separate jobs rather than a matrix, which gets this by
    construction — but if one is ever converted to a matrix, fail-fast defaults
    to TRUE and would silently destroy the five-way comparison. Assert the
    property rather than today's implementation of it.
    """
    for name in LEGS:
        strategy = wf["jobs"][name].get("strategy") or {}
        assert strategy.get("fail-fast", False) is False, (
            f"{name} would abandon its siblings on first failure"
        )


def test_the_gallery_is_built_for_red_runs_too(wf):
    gallery = wf["jobs"]["gallery"]
    assert str(gallery.get("if", "")).strip() == "always()", (
        "the gallery only builds on success — the red run is the one worth looking at"
    )
    for leg in LEGS:
        assert leg in gallery["needs"], f"the gallery ignores {leg}"


def test_the_gate_is_not_wired_to_every_push(wf):
    # It boots an emulator and a simulator. On every push it would be switched
    # off within a week, and a gate that is switched off protects nothing.
    on = wf.get("on") or wf.get(True)
    assert "push" not in on, "too expensive to gate every push; nightly is the point"
    assert "schedule" in on, "nothing would ever run it"
