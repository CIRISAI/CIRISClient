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


def test_the_gallery_step_calls_the_script_as_written(wf):
    """Run 36588619656: `build_qa_gallery.py: error: unrecognized arguments:
    --shots` — the script takes the artifacts directory positionally, and the
    step passed it as a flag, so the gallery never built on any run."""
    body = "\n".join(str(s.get("run", "")) for s in wf["jobs"]["gallery"]["steps"])
    # `-m testing.gate.build_qa_gallery`: it imports testing.gate.optional_skips
    # now, which a script invocation (sys.path[0] = testing/gate) cannot find.
    call = re.search(r"build_qa_gallery(?:\.py)?\s+([^\n|]*)", body)
    assert call, "no build_qa_gallery.py call in the gallery job"
    import shlex
    argv = shlex.split(call.group(1).replace("\\", " "))
    from testing.gate import build_qa_gallery
    ns = build_qa_gallery.parser().parse_args(argv)
    assert str(ns.root) == "collected" and str(ns.out) == "gallery.html"
    assert ns.server_version and ns.server_source, "the gallery is not told the run's server version"


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
    # The build moved into .github/actions/ios-sim-app (both iOS legs use it).
    commands = _action_commands(IOS_APP_ACTION)
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
    offenders = [
        ln.strip()
        for path in (WF, *sorted(WF.parents[1].glob("actions/*/action.yml")))
        for ln in path.read_text(encoding="utf-8").splitlines()
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


# ── the two-node fixture (testing/gate/two_node.py) ─────────────────────────

import re  # noqa: E402

#: Each run_platform invocation and the peer work dir it must name.
_PLATFORM_LEGS = {
    "linux-android": ("two-node-linux", "two-node-android"),
    "macos-ios": ("two-node-macos", "two-node-ios"),
    "windows": ("two-node-windows",),
}


def _run_platform_calls(job: dict) -> list[str]:
    """Every `run_platform` command line in a job, continuations joined."""
    calls = []
    for step in job["steps"]:
        for text in (step.get("run"), (step.get("with") or {}).get("script")):
            if not text:
                continue
            joined = re.sub(r"\\\n\s*", " ", str(text))
            calls += [ln for ln in joined.splitlines() if "testing.gate.run_platform" in ln]
    return calls


@pytest.mark.parametrize("leg", LEGS)
def test_every_platform_can_stand_up_the_second_node(wf, leg):
    """A `fixture: two_node` flow on a leg with no --node-binary is
    cannot-start: red, but for the gate's reason, not the client's."""
    calls = _run_platform_calls(wf["jobs"][leg])
    assert len(calls) == len(_PLATFORM_LEGS[leg]), calls
    for call, work in zip(calls, _PLATFORM_LEGS[leg]):
        assert "--node-binary node/ciris-server" in call, f"{leg}: {call}"
        assert f'--peer-work "$RUNNER_TEMP/{work}"' in call, f"{leg}: {call}"


@pytest.mark.parametrize("leg", LEGS)
def test_every_peer_is_torn_down_even_when_the_leg_failed(wf, leg):
    """The runner's `finally` does not survive a SIGKILL; an always() step does."""
    teardown = [s for s in wf["jobs"][leg]["steps"]
                if "testing.gate.two_node down" in str(s.get("run", ""))
                and str(s.get("if", "")).strip() == "always()"]
    assert teardown, f"{leg} never tears its peer down under always()"
    body = " ".join(str(s["run"]) for s in teardown)
    for work in _PLATFORM_LEGS[leg]:
        leg_name = work.removeprefix("two-node-")
        assert work in body or leg_name in body, f"{leg}: {work} is never torn down"


def test_the_android_leg_gets_a_fresh_node_when_the_fixture_seeded_the_shared_one(wf):
    """The desktop leg's fixture leaves the shared node with a contact; the
    Android leg's bare-node flows must not inherit it."""
    steps = wf["jobs"]["linux-android"]["steps"]
    names = [s.get("name", "") for s in steps]
    fresh = next(s for s in steps if s.get("name") == "A fresh node for the Android leg")
    assert "seeded" in str(fresh.get("if", ""))
    assert fresh["uses"] == "./.github/actions/ciris-node"
    assert fresh["with"]["home"] != "", "a fresh node needs a fresh home"
    assert names.index("A fresh node for the Android leg") < names.index("Android emulator")
    assert names.index("Linux desktop") < names.index("A fresh node for the Android leg")



def test_the_workflow_labels_every_trace_qa(wf):
    # CIRISClient#182: desktop and the iOS host inherit this; only a run outside
    # a harness is `production`.
    assert wf["env"].get("CIRIS_TRACE_RUN_KIND") == "qa"


# ── one server version per run (CIRISClient#166) ────────────────────────────

BUILD_WF = WF.parent / "build.yml"

#: What the legs USED to do, each on its own: the CLIENT's version as a server
#: tag (never a release — the client leads), and "latest" (a moving target that
#: three legs read at three different minutes: run 39023854089 drove two legs
#: against v0.5.228 and three against v0.5.229).
_OWN_LOOKUPS = ("v$(cat VERSION)", "gh release view", "releases/latest")

#: The one source. A leg reads it; nothing else computes a version.
SERVER_JOB = "server"
SERVER_REF = "${{ needs.server.outputs.version }}"


def _commands(wf: dict) -> list[tuple[str, str]]:
    """(job, line) for every executing line — `run:` and emulator `script:` —
    with shell comments dropped, so a comment EXPLAINING the old lookup is not
    reported as the lookup (this file's own lesson, three times over)."""
    out = []
    for job, body in wf["jobs"].items():
        for step in body.get("steps", []):
            for text in (step.get("run"), (step.get("with") or {}).get("script")):
                if not text:
                    continue
                joined = re.sub(r"\\\n\s*", " ", str(text))
                out += [(job, ln.strip()) for ln in joined.splitlines()
                        if ln.strip() and not ln.strip().startswith("#")]
    return out


def test_the_workflow_has_exactly_one_server_version_source(wf):
    resolvers = [(job, ln) for job, ln in _commands(wf) if "testing.gate.server_version" in ln]
    assert len(resolvers) == 1, f"the server version must be resolved exactly once: {resolvers}"
    assert resolvers[0][0] == SERVER_JOB, f"the resolver runs in {resolvers[0][0]!r}, not {SERVER_JOB!r}"
    own = [(job, ln) for job, ln in _commands(wf) if any(p in ln for p in _OWN_LOOKUPS)]
    assert not own, f"a leg still picks its own server version: {own}"
    outputs = wf["jobs"][SERVER_JOB].get("outputs") or {}
    assert "version" in outputs and "pypi" in outputs, f"{SERVER_JOB} exposes {sorted(outputs)}"


@pytest.mark.parametrize("leg", LEGS)
def test_every_leg_reads_the_one_server_version(wf, leg):
    job = wf["jobs"][leg]
    needs = job.get("needs") or []
    assert SERVER_JOB in ([needs] if isinstance(needs, str) else needs), f"{leg} does not wait for {SERVER_JOB}"
    calls = _run_platform_calls(job)
    assert calls, f"{leg} never invokes the runner"
    for call in calls:
        assert f'--node-version "{SERVER_REF}"' in call, f"{leg} boards a version it did not resolve: {call}"
    body = "\n".join(ln for _, ln in _commands(wf) if _ == leg)
    downloads = [ln for ln in body.splitlines() if "gh release download" in ln and "CIRISServer" in ln]
    assert downloads, f"{leg} downloads no server binary"
    for ln in downloads:
        assert SERVER_REF in ln, f"{leg} downloads a version other than the run's: {ln}"


def test_the_android_install_resolves_the_run_s_server_version(wf):
    """`androidApp/build.gradle` pins `ciris-server==` from PyPI; on the matrix
    that pin is the run's version, handed in as a Gradle property, so the APK's
    embedded node and the node the legs talk to are the same release."""
    gradle = [ln for job, ln in _commands(wf) if job == "linux-android" and "assembleDebug" in ln]
    assert gradle, "the Android leg builds no APK"
    assert any("-PcirisServerVersion=${{ needs.server.outputs.pypi }}" in ln for ln in gradle), gradle


def test_the_ios_leg_records_the_embedded_node_it_did_not_choose(wf):
    """The simulator app embeds CIRISAgent's pinned ciris-server, not the run's;
    the board says both rather than claiming the run's version for it."""
    [call] = [c for c in _run_platform_calls(wf["jobs"]["macos-ios"]) if "--platform ios" in c]
    assert '--embedded-node-version "${{ steps.ios.outputs.embedded }}"' in call, call
    body = "\n".join(_action_commands(IOS_APP_ACTION))
    assert "embedded=" in body, "the iOS substrate step does not export the agent's pin for the board"


def test_the_dispatch_input_feeds_the_one_source_and_nothing_else(wf):
    on = wf.get("on") or wf.get(True)
    inputs = on["workflow_dispatch"]["inputs"]
    assert "server_version" in inputs, "no way to run the matrix against a named release"
    resolver = [ln for job, ln in _commands(wf) if "testing.gate.server_version" in ln]
    assert "inputs.server_version" in resolver[0], resolver
    elsewhere = [(job, ln) for job, ln in _commands(wf) if "inputs.server_version" in ln and job != SERVER_JOB]
    assert not elsewhere, f"the input is read outside the one source: {elsewhere}"


def test_build_yml_s_e2e_walk_uses_the_same_resolver():
    """build.yml:709 had the same CLIENT-version lookup with the same fallback."""
    bwf = yaml.safe_load(BUILD_WF.read_text(encoding="utf-8"))
    cmds = _commands(bwf)
    assert any("testing.gate.server_version" in ln for _, ln in cmds), "build.yml picks its own server"
    own = [(job, ln) for job, ln in cmds if any(p in ln for p in _OWN_LOOKUPS)]
    assert not own, f"build.yml still looks a server version up on its own: {own}"


# ── iOS with AI (CIRISClient#50) ────────────────────────────────────────────
#
# #50 is a SIGABRT as the iOS wizard enters the AI step, and until this leg no
# CI run of this repo reached that step on iOS: the macos-ios leg answers "run
# without AI". The simulator app needs no host agent to get there — its
# embedded runtime IS the agent (Main.ios.kt: apiBaseUrl :8080, the bundle's
# ciris_engine), so the leg builds the same app at the run's agent tag and
# answers WITH.

IOS_AI = "ios-ai"
IOS_APP_ACTION = NODE_ACTION.parent.parent / "ios-sim-app" / "action.yml"


def _action_commands(path: pathlib.Path) -> list[str]:
    """Executing lines of a composite action, comments dropped."""
    act = yaml.safe_load(path.read_text(encoding="utf-8"))
    out = []
    for step in act["runs"]["steps"]:
        joined = re.sub(r"\\\n\s*", " ", str(step.get("run", "")))
        out += [ln.strip() for ln in joined.splitlines() if ln.strip() and not ln.strip().startswith("#")]
    return out


def test_both_ios_legs_build_the_app_through_one_action(wf):
    """ONE bring-up for the simulator app. The substrate recipe is 250 lines
    that took a dozen runs to get right; a second copy for the with-AI leg
    would be a second thing to keep right."""
    assert IOS_APP_ACTION.is_file(), "no .github/actions/ios-sim-app"
    for leg in ("macos-ios", IOS_AI):
        uses = [s for s in wf["jobs"][leg]["steps"] if s.get("uses") == "./.github/actions/ios-sim-app"]
        assert len(uses) == 1, f"{leg} does not build the simulator app through the shared action"
    xcodebuilds = [(job, ln) for job, ln in _commands(wf) if ln.startswith("xcodebuild")]
    assert not xcodebuilds, f"a leg builds the iOS app outside the action: {xcodebuilds}"


def test_the_ios_ai_leg_runs_only_when_a_with_ai_dispatch_asks_for_ios(wf):
    job = wf["jobs"][IOS_AI]
    cond = str(job.get("if", ""))
    assert "github.event_name == 'workflow_dispatch'" in cond and "inputs.with_ai" in cond, cond
    assert "contains(inputs.platforms, 'ios')" in cond and "inputs.platforms == 'all'" in cond, cond
    assert str(job["runs-on"]).startswith("macos-15"), job["runs-on"]
    assert job.get("timeout-minutes"), "ios-ai has no timeout"
    needs = job.get("needs") or []
    assert SERVER_JOB in ([needs] if isinstance(needs, str) else needs)


def test_the_ios_ai_leg_embeds_the_run_s_one_agent_and_starts_no_host_backend(wf):
    """The run's agent is resolved once (`server`); on iOS it is EMBEDDED, so
    the bundle's engine is checked out at that tag. A host agent or node would
    hold :8080/:4243 on the loopback the simulator shares and the embedded one
    would die on 'Address already in use'."""
    steps = wf["jobs"][IOS_AI]["steps"]
    [build] = [s for s in steps if s.get("uses") == "./.github/actions/ios-sim-app"]
    assert build["with"]["agent-ref"] == "${{ needs.server.outputs.agent_tag }}", build
    for action in ("./.github/actions/ciris-agent", "./.github/actions/ciris-node"):
        assert not [s for s in steps if s.get("uses") == action], f"ios-ai starts {action}"
    act = yaml.safe_load(IOS_APP_ACTION.read_text(encoding="utf-8"))
    [checkout] = [s for s in act["runs"]["steps"]
                  if "actions/checkout" in str(s.get("uses", "")) and (s.get("with") or {}).get("repository") == "CIRISAI/CIRISAgent"]
    assert checkout["with"]["ref"] == "${{ inputs.agent-ref }}", checkout


def test_the_ios_ai_leg_drives_the_with_ai_wizard(wf):
    calls = _run_platform_calls(wf["jobs"][IOS_AI])
    assert len(calls) == 1, calls
    [call] = calls
    for want in ("--platform ios", "--with-ai", "--flows testing/flows/agent",
                 '--agent-version "${{ needs.server.outputs.agent_pypi }}"',
                 '--node-version "v${{ needs.server.outputs.agent_server }}"',
                 "--embedded-node-version", "--llm-key-env LLM_API_KEY",
                 "--report reports/ios-ai.json"):
        assert want in call, f"ios-ai: {want!r} not in {call}"


def test_the_ios_ai_leg_keeps_the_key_out_and_the_crash_record_in(wf):
    """Key via env: only; the crash record and the app's logs are collected
    under always(), scanned for the key under always(), then uploaded under
    always() — and the app's data container (its .env holds the key) is not."""
    steps = wf["jobs"][IOS_AI]["steps"]
    for s in steps:
        env = s.get("env") or {}
        if "API_KEY" in yaml.dump(env):
            assert set(k for k in env if k.endswith("API_KEY")) == {"LLM_API_KEY"}, s
            assert env["LLM_API_KEY"] == "${{ inputs.llm_provider == 'openrouter' && secrets.OPENROUTER_API_KEY || secrets.ANTHROPIC_API_KEY }}", s
    idx = lambda pred: next(i for i, s in enumerate(steps) if pred(s))  # noqa: E731
    collect = idx(lambda s: "Documents/ciris/logs/kotlin_crash.log" in str(s.get("run", "")))
    scan = idx(lambda s: "testing.gate.secret_scan" in str(s.get("run", "")))
    upload = idx(lambda s: "upload-artifact" in str(s.get("uses", "")))
    assert collect < scan < upload, (collect, scan, upload)
    for i in (collect, scan, upload):
        assert str(steps[i].get("if", "")).strip() == "always()", steps[i].get("name")
    assert "ios-ai-logs" in steps[scan]["run"], "the scan does not cover the collected app logs"
    paths = steps[upload]["with"]["path"]
    assert "ios-ai-logs/kotlin_crash.log" in paths and "ios-ai-logs/" in paths, paths
    assert ".env" not in paths and "Documents" not in paths, paths
    run = steps[collect]["run"]
    assert '-name "*.log*"' in run, "the app's own logs (kmp_app.log, ciris-server.log.*) are not collected"


def test_the_gallery_waits_for_the_ios_ai_leg(wf):
    assert IOS_AI in wf["jobs"]["gallery"]["needs"]
