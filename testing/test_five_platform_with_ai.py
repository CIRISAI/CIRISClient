"""The five-platform gate's with-AI mode — the release gate (CIRISClient#166).

The node-only legs' guards live in test_five_platform_workflow.py; these hold
the with-AI legs to the same standard, and hold the node-only matrix to
"unchanged when `with_ai` is false".
"""

from __future__ import annotations

import pytest
import yaml

from testing.test_five_platform_workflow import (
    LEGS, NODE_ACTION, SERVER_JOB, WF, _commands, _run_platform_calls,
)

AI_LEGS = ("linux-ai", "windows-ai")
AGENT_ACTION = NODE_ACTION.parent.parent / "ciris-agent" / "action.yml"


@pytest.fixture(scope="module")
def wf() -> dict:
    return yaml.safe_load(WF.read_text(encoding="utf-8"))


def _on(wf: dict) -> dict:
    return wf.get("on") or wf.get(True)


def test_the_with_ai_mode_is_a_dispatch_input_that_defaults_off(wf):
    inputs = _on(wf)["workflow_dispatch"]["inputs"]
    assert inputs["with_ai"]["type"] == "boolean" and inputs["with_ai"]["default"] is False
    for name in ("agent_version", "llm_model", "candidate_version"):
        assert name in inputs, f"no `{name}` input"


@pytest.mark.parametrize("leg", AI_LEGS)
def test_a_with_ai_leg_runs_only_when_a_dispatch_asks_for_it(wf, leg):
    cond = str(wf["jobs"][leg].get("if", ""))
    assert "github.event_name == 'workflow_dispatch'" in cond and "inputs.with_ai" in cond, cond


@pytest.mark.parametrize("leg", LEGS)
def test_the_node_only_matrix_does_not_read_the_with_ai_inputs(wf, leg):
    """`with_ai: false` must leave the node-only legs exactly as they were: no
    node-only job may branch on, or be handed, anything the with-AI mode sets."""
    body = yaml.dump(wf["jobs"][leg])
    for name in ("inputs.with_ai", "inputs.agent_version", "inputs.llm_model", "agent_pypi",
                 "ANTHROPIC_API_KEY", "--with-ai"):
        assert name not in body, f"{leg} reads {name}"


@pytest.mark.parametrize("leg", AI_LEGS)
def test_a_with_ai_leg_is_a_gate_too(wf, leg):
    job = wf["jobs"][leg]
    assert job.get("timeout-minutes"), f"{leg} has no timeout"
    uploads = [s for s in job["steps"] if "upload-artifact" in str(s.get("uses", ""))]
    assert uploads and all(str(s.get("if", "")).strip() == "always()" for s in uploads), leg
    calls = _run_platform_calls(job)
    assert calls, f"{leg} never drives the product"
    for call in calls:
        assert "--with-ai" in call and "--flows testing/flows/agent" in call, call
        assert '--agent-version "${{ needs.server.outputs.agent_pypi }}"' in call, call
        assert '--node-version "v${{ needs.server.outputs.agent_server }}"' in call, call
        assert "--llm-key-env ANTHROPIC_API_KEY" in call, call


@pytest.mark.parametrize("leg", AI_LEGS)
def test_a_with_ai_leg_runs_the_run_s_one_agent_and_no_standalone_node(wf, leg):
    job = wf["jobs"][leg]
    agents = [s for s in job["steps"] if s.get("uses") == "./.github/actions/ciris-agent"]
    assert agents, f"{leg} starts no agent"
    for s in agents:
        assert s["with"]["version"] == "${{ needs.server.outputs.agent_pypi }}", s
    # The agent folds in its own node on :4243; a standalone one would hold the
    # port first (CIRISAgent#1101/#1102).
    assert not [s for s in job["steps"] if s.get("uses") == "./.github/actions/ciris-node"], leg


def test_the_agent_version_is_resolved_once_in_the_server_job(wf):
    resolvers = [(job, ln) for job, ln in _commands(wf) if "testing.gate.agent_version" in ln]
    assert len(resolvers) == 1 and resolvers[0][0] == SERVER_JOB, resolvers
    assert "inputs.agent_version" in resolvers[0][1]
    outputs = wf["jobs"][SERVER_JOB]["outputs"]
    assert {"agent_pypi", "agent_server", "agent_tag"} <= set(outputs), sorted(outputs)
    installs = [ln for _, ln in _commands(wf) if "pip install" in ln and "ciris-agent" in ln]
    assert not installs, f"a leg installs an agent outside the action: {installs}"
    action = AGENT_ACTION.read_text(encoding="utf-8")
    assert 'pip install --quiet "ciris-agent==$CIRIS_AGENT_VERSION"' in action
    assert "CIRIS_DEVICE_CLASS=laptop" in action and "CIRIS_TRACE_RUN_KIND=qa" in action


def test_the_key_is_never_on_a_command_line_and_every_upload_is_scanned_first(wf):
    """The secret reaches a step only through `env:`, and every with-AI
    artifact is scanned for it (testing/gate/secret_scan.py) under always()
    BEFORE the upload."""
    for job, ln in _commands(wf):
        assert "secrets." not in ln, f"{job}: a secret interpolated into a command: {ln}"
    for leg in AI_LEGS:
        steps = wf["jobs"][leg]["steps"]
        for s in steps:
            if "ANTHROPIC_API_KEY" in yaml.dump(s.get("env") or {}):
                assert s["env"]["ANTHROPIC_API_KEY"] == "${{ secrets.ANTHROPIC_API_KEY }}", s
        names = [s.get("name", s.get("uses", "")) for s in steps]
        scan = next(i for i, s in enumerate(steps) if "testing.gate.secret_scan" in str(s.get("run", "")))
        upload = next(i for i, s in enumerate(steps) if "upload-artifact" in str(s.get("uses", "")))
        assert scan < upload, f"{leg}: the upload runs before the scan: {names}"
        assert str(steps[scan].get("if", "")).strip() == "always()", leg
        uploaded = steps[upload]["with"]["path"]
        assert ".env" not in uploaded and "ciris-ai-" not in uploaded, f"{leg} uploads an agent home"


def test_every_harness_run_labels_its_traces_qa(wf):
    """CIRISClient#182: a real model behind a real agent, driven by CI, must
    never file its traces as production."""
    assert wf["env"].get("CIRIS_TRACE_RUN_KIND") == "qa", wf["env"]


def test_the_ios_leg_collects_the_crash_record_whatever_happened(wf):
    """PR #200 makes Documents/ciris/logs/kotlin_crash.log the record of #50's
    crash; it is collected under always(), not only when the walk got far."""
    steps = wf["jobs"]["macos-ios"]["steps"]
    [collect] = [s for s in steps if "kotlin_crash.log" in str(s.get("run", ""))
                 and "Collect" in str(s.get("name", ""))]
    assert str(collect.get("if", "")).strip() == "always()"
    assert "Documents/ciris/logs/kotlin_crash.log" in collect["run"]
    i = steps.index(collect)
    upload = next(j for j, s in enumerate(steps) if "upload-artifact" in str(s.get("uses", "")))
    assert i < upload and "ios-logs/" in steps[upload]["with"]["path"]


def test_the_gallery_waits_for_the_with_ai_legs_too(wf):
    needs = wf["jobs"]["gallery"]["needs"]
    assert set(AI_LEGS) <= set(needs), needs
