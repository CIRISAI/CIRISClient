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

#: The shared node bring-up. It was three copies inside the legs, and all three
#: asked the wrong question the same way — see the endpoint test below.
NODE_ACTION = pathlib.Path(__file__).resolve().parents[1] / ".github" / "actions" / "ciris-node" / "action.yml"

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
    assert "./.github/actions/ciris-node" in body, f"{leg} does not start the node through the shared action"
    assert "the node never became healthy" in NODE_ACTION.read_text(encoding="utf-8"), (
        "the shared action no longer fails a leg whose node never served"
    )


def test_the_node_is_probed_where_a_NODE_answers_and_not_where_an_AGENT_would():
    """THE ASSERTION THIS FILE WAS MISSING, AND THE COST OF MISSING IT.

    The guard above has always checked that a dead node FAILS the leg. It never
    checked that the liveness question was addressed to the right place — so for
    the whole life of this gate all three legs polled `:8080/v1/system/health`,
    the AGENT's endpoint, at a bare `ciris-server`. The node came up correctly
    on `:4243` every single night and the gate reported "the node never became
    healthy". It has never once been green.

    A check that reports the right failure for the wrong reason is worse than no
    check: it produces a red that everyone learns to expect and nobody reads.

        AGENT_ENDPOINT     = :8080 /v1/system/health
        NODE_ONLY_ENDPOINT = :4243 /health     <- what this gate stands up

    (client/shared/.../platform/BackendEndpoint.kt, and confirmed empirically:
    in the 2026-09-08 Windows run the only occurrence of 8080 anywhere in the
    log was our own curl command.)
    """
    action = NODE_ACTION.read_text(encoding="utf-8")
    probe = [ln for ln in action.splitlines() if "url=" in ln and "http" in ln]
    assert probe, "the action no longer contains a health URL to check"
    joined = "\n".join(probe)
    assert "4243" in joined and "/health" in joined, (
        f"the node is not probed on :4243/health — found {joined!r}"
    )
    assert "8080" not in joined, (
        f"the node is probed on the AGENT's port; a bare ciris-server never binds 8080 — {joined!r}"
    )


def test_the_node_gets_a_writable_home():
    """`/var/lib/ciris` is not creatable by any hosted runner user.

    The macOS leg died with `create /var/lib/ciris/data: Permission denied`
    before it ever reached the port question above — a second, independent
    reason the same step could never succeed. The server's DEFAULT_CIRIS_HOME
    (config.rs) is only overridable by CIRIS_HOME, so the action must set it.

    A FLAG, NOT AN ENV VAR — AND THIS TEST GOT THAT WRONG FIRST.

    Two rewrites, both instructive:

      1. It asserted `"CIRIS_HOME" in action`, which is green on a mention in a
         comment — in a file that documents itself heavily. A planted defect
         that removed the export passed.
      2. It then asserted `export CIRIS_HOME=`, which was green AND WRONG: the
         server reads no environment at all. config.rs, first line: "Server 0.5
         — zero env vars. ciris-server boots with NO environment variables. The
         bootstrap floor is conventions + a single --home flag." The dispatched
         run exported the variable, the node ignored it, and macOS died on
         /var/lib/ciris exactly as before.

    Both versions asserted a MECHANISM the test's own author had invented. The
    property is "the node is told where to write, in the way it actually reads",
    and only the flag expresses that.
    """
    action = NODE_ACTION.read_text(encoding="utf-8")
    invocations = [
        ln.strip() for ln in action.splitlines()
        if '"$bin"' in ln and not ln.strip().startswith("#")
    ]
    assert invocations, "the action no longer invokes the node binary"
    launch = [ln for ln in invocations if "--home" in ln]
    assert launch, (
        "the node is launched without --home, so it uses DEFAULT_CIRIS_HOME "
        f"(/var/lib/ciris) and cannot create it on any hosted runner. Found: {invocations!r}"
    )
    assert not any(
        ln.strip().startswith("export CIRIS_HOME=") for ln in action.splitlines()
    ), "exporting CIRIS_HOME is a no-op for ciris-server (zero env vars) and misleads the next reader"


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


def test_test_mode_is_armed_or_the_automation_server_never_starts():
    """The app serves /health on 9091 only when CIRIS_TEST_MODE is set.

    `TestAutomationServer.isTestModeEnabled()` reads that variable, and the
    desktop starts its automation server only when it is true. Nothing set it,
    so every desktop leg launched an app with no automation server and then
    failed the single thing the gate exists to prove:

        [OK ] bring-up: launch
        [FAIL] drive: automation server never came up within 120s

    A launch that succeeds and a client that can be driven are different facts,
    and the gate was asserting the first while reporting the second.
    """
    env = yaml.safe_load(WF.read_text(encoding="utf-8")).get("env") or {}
    assert str(env.get("CIRIS_TEST_MODE", "")).lower() in ("true", "1", "yes"), (
        "CIRIS_TEST_MODE is not armed, so no desktop leg can reach the "
        "automation server it drives the app through"
    )


def test_the_ios_leg_names_a_gradle_task_that_exists():
    """`assembleDebugXCFramework` is not a task; `assembleSharedDebugXCFramework` is.

    The framework is declared `XCFramework("shared")`, which produces
    assembleShared{Debug,Release}XCFramework. publish.yml has used the release
    twin correctly since it was written; this leg asked for a name Gradle calls
    ambiguous and failed in 2 seconds.
    """
    # COMMANDS, NOT PROSE. A `run:` block's shell comments are part of its
    # string, so a whole-body match sees the paragraph EXPLAINING the bad task
    # name and reports the defect it documents. This test failed that way on its
    # first run — the third time today a check matched its own explanation
    # (the Android wheel step's version regex found 0.5.188 inside the comment
    # saying why the pin left 0.5.188, and a CIRIS_HOME guard passed on a
    # mention). Filter the comments out and assert on what actually executes.
    job = yaml.safe_load(WF.read_text(encoding="utf-8"))["jobs"]["macos-ios"]
    commands = [
        ln.strip()
        for step in job["steps"]
        for ln in str(step.get("run", "")).splitlines()
        if ln.strip() and not ln.strip().startswith("#")
    ]
    gradle = [ln for ln in commands if "XCFramework" in ln]
    assert gradle, "the iOS leg assembles no XCFramework at all"
    assert any("assembleSharedDebugXCFramework" in ln for ln in gradle), (
        f"the iOS leg names no valid XCFramework task: {gradle}"
    )
    assert not any(":shared:assembleDebugXCFramework" in ln for ln in gradle), (
        f"':shared:assembleDebugXCFramework' is ambiguous and does not exist: {gradle}"
    )


def test_no_step_can_leak_its_working_directory():
    """`cd X && … && cd ..` leaves the shell in X whenever the middle fails.

    That is how a bad Gradle task name was reported as a missing Xcode project:
    the gradle call failed, `cd ..` never ran, and xcodebuild resolved
    client/iosApp/… as client/client/iosApp/…. The error named the wrong thing
    entirely, which costs more than the failure it was hiding.

    A subshell or `working-directory:` cannot leak, however the command ends.
    """
    raw = WF.read_text(encoding="utf-8")
    offenders = [
        ln.strip() for ln in raw.splitlines()
        if "cd .." in ln and not ln.strip().startswith("#")
    ]
    assert not offenders, f"a step can leak its cwd on failure: {offenders}"


def test_the_emulator_script_has_no_line_continuations():
    """`script:` is not a `run:` block, and does not honour `\\`-newline.

    android-emulator-runner hands its `script` to the emulator wrapper, which
    passes backslash-newline through literally. argparse then said

        run_platform.py: error: unrecognized arguments: \\

    AFTER booting an emulator — the most expensive point in the leg at which to
    discover a quoting problem. One line reads worse and is the form that runs.
    """
    job = yaml.safe_load(WF.read_text(encoding="utf-8"))["jobs"]["linux-android"]
    scripts = [
        str(step["with"]["script"])
        for step in job["steps"]
        if "android-emulator-runner" in str(step.get("uses", "")) and "script" in (step.get("with") or {})
    ]
    assert scripts, "the Android leg runs no script"
    for script in scripts:
        assert "\\" not in script, (
            "a line continuation in `script:` reaches the runner as a literal argument"
        )
