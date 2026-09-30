"""The bring-up ORDER, which is where bring-up bugs actually live.

Every step needs an emulator, a simulator or a display, so none of it can be
executed here. What can be checked is the plan — and the ordering invariants are
exactly what went wrong repeatedly in CIRISAgent's gate: an app that "was alive
for its whole 120s budget and never answered /health", four runs running, with
every diagnostic channel broken.

Each test below names the failure it prevents. A test that only asserted "the
plan has seven steps" would pass through every one of them.
"""

from __future__ import annotations

import sys
import time
from pathlib import Path

import pytest

from testing.gate import bringup
from testing.gate.bringup import (
    ANDROID_TEST_SENTINEL,
    CLIENT_TEST_PORT,
    NODE_API_PORT,
    CannotRun,
    Plan,
    Step,
    android_plan,
    android_teardown,
    desktop_plan,
    ios_simulator_plan,
    ios_teardown,
    run,
)

APK = Path("/tmp/app-debug.apk")
PKG = "ai.ciris.mobile.debug"
APP = Path("/tmp/iosApp.app")
BID = "ai.ciris.mobile"


# ---- invariant 1: test mode armed before the app starts ---------------------


def test_android_launches_the_class_the_manifest_declares():
    # The debug package is ai.ciris.mobile.debug; the activity is
    # ai.ciris.mobile.MainActivity. `pkg/.MainActivity` would name a class that
    # does not exist, and `am start` exits 0 on that.
    p = android_plan(APK, PKG)
    for name in ("launch", "await-process"):
        joined = " ".join(p.steps[p.index_of(name)].cmd)
        assert f"{PKG}/ai.ciris.mobile.MainActivity" in joined, joined
        assert f"{PKG}/.MainActivity" not in joined, joined


def test_android_arms_test_mode_before_launching():
    # The sentinel is read ONCE at startup. Touched after `am start`, the app
    # runs with no automation server and a /health that never answers — which
    # is indistinguishable from a crash, and cost four diagnostic-blind runs.
    p = android_plan(APK, PKG)
    assert p.index_of("arm-test-mode") < p.index_of("launch")


def test_android_arms_test_mode_at_the_documented_path():
    # Not an env var: `am start` cannot set the launched process's environment,
    # so this file is the only handle a harness has.
    p = android_plan(APK, PKG)
    arm = p.steps[p.index_of("arm-test-mode")]
    assert ANDROID_TEST_SENTINEL in arm.cmd
    assert ANDROID_TEST_SENTINEL == "/data/local/tmp/ciris_test_mode"


# ---- invariant 2: the node is reachable before the app probes it ------------


def test_android_reverses_the_node_port_before_launching():
    # The client probes its backend during startup. Reversing after launch means
    # the first probe fails and the run then drives an error state.
    p = android_plan(APK, PKG)
    assert p.index_of("reverse-node") < p.index_of("launch")


def test_android_reverses_the_node_rather_than_forwarding_it():
    # reverse: emulator's localhost:8080 -> the runner's node. forward is the
    # other direction and would leave the app with no backend at all.
    p = android_plan(APK, PKG)
    rev = p.steps[p.index_of("reverse-node")]
    assert "reverse" in rev.cmd
    assert f"tcp:{NODE_API_PORT}" in rev.cmd


# ---- invariant 3: installed before forwarded --------------------------------


def test_android_installs_before_forwarding():
    # adb accepts on the HOST socket before it tries the device, so a forward to
    # an absent package succeeds and then fails as a socket error that looks
    # exactly like a dead app.
    p = android_plan(APK, PKG)
    assert p.index_of("install") < p.index_of("forward-automation")


def test_android_force_stops_before_installing():
    # `adb install` hangs on some devices if the app is running (client/CLAUDE.md).
    p = android_plan(APK, PKG)
    assert p.index_of("force-stop") < p.index_of("install")


def test_the_automation_forward_targets_the_port_the_client_binds():
    p = android_plan(APK, PKG, host_port=19091)
    fwd = p.steps[p.index_of("forward-automation")]
    assert f"tcp:{CLIENT_TEST_PORT}" in fwd.cmd, "device side must be the client's 9091"
    assert "tcp:19091" in fwd.cmd, "host side is ours to choose"
    assert p.test_url.endswith(":19091"), "the driver must be told the HOST port"


# ---- teardown must not let the next run lie ---------------------------------


def test_teardown_disarms_test_mode():
    # A leftover sentinel puts a later NON-test run into test mode.
    assert "disarm-test-mode" in android_teardown(PKG).names()


def test_every_teardown_step_is_optional():
    # Teardown runs after a failure too, when half of it does not exist. A
    # teardown that fails hides the real failure behind its own.
    for step in android_teardown(PKG).steps + ios_teardown(BID).steps:
        assert step.optional, step.name


# ---- iOS --------------------------------------------------------------------


def test_ios_needs_no_forwarding_and_says_so_in_the_url():
    # The simulator shares the host loopback, so both ports are plain 127.0.0.1.
    p = ios_simulator_plan(APP, BID)
    assert p.test_url == f"http://127.0.0.1:{CLIENT_TEST_PORT}"
    assert not any("forward" in " ".join(s.cmd) for s in p.steps)


def test_ios_launch_carries_test_mode_across_simctl():
    # simctl forwards only SIMCTL_CHILD_*; the app reads CIRIS_TEST_MODE via getenv.
    p = ios_simulator_plan(APP, BID)
    launch = p.steps[p.index_of("launch")]
    assert launch.env.get("SIMCTL_CHILD_CIRIS_TEST_MODE") == "true"
    assert all(not s.env for s in p.steps if s.name != "launch")


def test_ios_terminates_any_existing_instance_before_launch():
    # Without it the launch is a no-op against a stale process still holding
    # 9091, and the run drives the OLD build. As a step, not a flag: simctl
    # has no --terminate-existing (that is devicectl's), and rejected it.
    p = ios_simulator_plan(APP, BID)
    assert p.index_of("terminate") < p.index_of("launch")
    assert p.steps[p.index_of("terminate")].optional
    launch = p.steps[p.index_of("launch")]
    assert "--terminate-existing" not in launch.cmd
    assert launch.cmd[:3] == ["xcrun", "simctl", "launch"]


def test_ios_waits_for_boot_before_installing():
    p = ios_simulator_plan(APP, BID)
    assert p.index_of("wait-for-boot") < p.index_of("install")


def test_ios_boot_is_optional_but_bootstatus_is_not():
    # Booting an already-booted simulator exits non-zero; waiting for boot is
    # the step whose failure actually means something.
    p = ios_simulator_plan(APP, BID)
    assert p.steps[p.index_of("boot")].optional
    assert not p.steps[p.index_of("wait-for-boot")].optional


# ---- desktop ----------------------------------------------------------------


def test_desktop_is_wrapped_for_a_headless_runner():
    assert desktop_plan(Path("/tmp/x.jar")).steps[0].cmd[:2] == ["xvfb-run", "-a"]


def test_desktop_can_run_unwrapped_on_a_real_display():
    assert desktop_plan(Path("/tmp/x.jar"), display_wrapped=False).steps[0].cmd[0] == "java"


# ---- the runner -------------------------------------------------------------


def test_a_failing_required_step_names_itself():
    # "bring-up failed" with no step and no stderr is the message their gate
    # spent four runs unable to improve on.
    plan = Plan(platform="test", steps=[Step("the-one-that-fails", ["false"])])
    with pytest.raises(CannotRun, match="the-one-that-fails"):
        run(plan)


def test_a_failing_optional_step_does_not_stop_the_plan():
    plan = Plan(platform="test", steps=[
        Step("skippable", ["false"], optional=True),
        Step("required", ["true"]),
    ])
    assert [rc for _, rc in run(plan)] == [1, 0]


def test_cannot_run_is_raised_not_returned():
    # A platform that cannot run must SKIP LOUDLY: a silent skip and a pass are
    # the same colour on a dashboard.
    assert issubclass(CannotRun, RuntimeError)


def test_a_missing_tool_is_a_step_failure_not_an_escaping_exception():
    # subprocess raises BEFORE there is a returncode, so check=False did not
    # protect teardown from it: on a machine with no adb, tearing down after a
    # failure crashed and buried the real error under its traceback.
    plan = Plan(platform="test", steps=[Step("no-such-tool", ["definitely-not-a-real-binary"],
                                             optional=True)])
    assert [rc for _, rc in run(plan, check=False)] == [127]


def test_a_missing_tool_on_a_required_step_still_names_the_step():
    plan = Plan(platform="test", steps=[Step("needs-adb", ["definitely-not-a-real-binary"])])
    with pytest.raises(CannotRun, match="needs-adb"):
        run(plan)


def test_teardown_survives_a_machine_with_no_adb():
    # The end-to-end version of the above: this is what crashed.
    run(android_teardown(PKG), check=False)


# ── The desktop launch is the app, not a command about the app ──────────────
#
# `run()` used to execute EVERY step with `subprocess.run()`, which waits for
# exit. That is right for `adb install`, `am start -W` and `simctl launch` —
# they do a thing and return. It is wrong for the desktop, where `java -jar
# <app>.jar` IS the running client: the step waited the full 300s and failed
# with exit 124 on every platform that has a desktop leg, so that leg could
# never pass. `desktop_plan`'s own docstring called itself "kept as a plan for
# symmetry", which is what a stub nobody ran looks like from outside.
#
# Liveness is proven afterwards by `wait_for_server`, which is the honest test
# anyway: a launch that returned 0 says nothing about whether the app came up.

def test_the_desktop_launch_is_a_background_step():
    plan = bringup.desktop_plan(Path("/tmp/whatever.jar"), display_wrapped=False)
    launch = plan.steps[plan.index_of("launch")]
    assert launch.background, (
        "the desktop launch must be spawned; awaiting it waits for someone to "
        "close the window, which is exit 124 after the timeout"
    )


def test_a_background_step_returns_immediately_and_is_tracked():
    bringup.terminate_background()
    started = time.time()
    bringup.run(bringup.Plan(platform="t", steps=[
        bringup.Step("spawn", [sys.executable, "-c", "import time; time.sleep(30)"],
                     background=True),
    ]))
    elapsed = time.time() - started
    assert elapsed < 5, f"background step blocked for {elapsed:.1f}s"
    assert len(bringup._BACKGROUND) == 1, "spawned process was not tracked for teardown"
    bringup.terminate_background()
    assert not bringup._BACKGROUND


def test_a_foreground_step_still_waits():
    # The fix must not turn every step into fire-and-forget: an `adb install`
    # that is not awaited is a launch racing an installation.
    started = time.time()
    bringup.run(bringup.Plan(platform="t", steps=[
        bringup.Step("await", [sys.executable, "-c", "import time; time.sleep(1)"]),
    ]))
    assert time.time() - started >= 1


def test_the_mobile_launches_are_NOT_backgrounded():
    # `am start -W` and simctl's launch return once the activity is up; making
    # them background would drop the only synchronisation those plans have.
    android = bringup.android_plan(Path("/tmp/a.apk"), "pkg")
    assert not android.steps[android.index_of("launch")].background


# ---- the claim PIN crosses from the host's node to the device ---------------
#
# Android, run 36746575125: the app reached first-run Setup, drove the wizard,
# and gave up with "claim PIN not captured after wait — leaving node unclaimed"
# (logcat, SetupViewModel). The node never saw a claim. The app reads the PIN
# from `<its CIRIS_HOME>/claim_pin` — `files/ciris` INSIDE the emulator — while
# the node it attached to runs on the HOST and wrote its PIN there. Desktop and
# iOS share the host's filesystem and read the file the node declares; Android
# cannot, so the harness carries it, standing in for the operator at the node's
# console, exactly as two_node.py does for the peer.

PIN = "FCZX-WTDT"


def test_android_hands_the_claim_pin_over_after_install_and_before_launch():
    p = android_plan(APK, PKG, claim_pin=PIN)
    assert p.index_of("install") < p.index_of("hand-over-claim-pin") < p.index_of("launch")


def test_the_pin_lands_in_the_home_the_app_reads():
    step = android_plan(APK, PKG, claim_pin=PIN).steps[
        android_plan(APK, PKG, claim_pin=PIN).index_of("hand-over-claim-pin")]
    cmd = " ".join(step.cmd)
    # CirisVerify.setup(): CIRIS_HOME = filesDir/ciris; PythonRuntime.android
    # readLocalClaimPin() reads File(CIRIS_HOME, "claim_pin").
    assert f"run-as {PKG}" in cmd, "only the app's own uid can write its private files"
    assert f"/data/data/{PKG}/files/ciris/claim_pin" in cmd
    assert PIN in cmd


def test_no_pin_no_handover_step():
    # An owned node has no PIN; the fixture logs in instead of claiming.
    assert "hand-over-claim-pin" not in android_plan(APK, PKG).names()


@pytest.mark.parametrize("bad", ["ABCD-EFGH; rm -rf /", "$(id)", "a'b", "", "X" * 200])
def test_a_pin_that_is_not_a_pin_is_refused_before_it_reaches_a_shell(bad):
    with pytest.raises(CannotRun):
        android_plan(APK, PKG, claim_pin=bad)


def test_teardown_takes_the_handed_over_pin_back():
    # A copy left on the device outlives the claim that consumed the node's own
    # file — the stale-PIN shape CIRISClient#49 was about, planted by the gate.
    td = android_teardown(PKG)
    assert "remove-claim-pin" in td.names()
    step = td.steps[td.index_of("remove-claim-pin")]
    assert step.optional and f"/data/data/{PKG}/files/ciris/claim_pin" in " ".join(step.cmd)


class _StatusNode:
    """A node's read API, serving only `/v1/setup/status`."""

    def __init__(self, body: dict):
        import http.server
        import json
        import threading

        payload = json.dumps(body).encode()

        class H(http.server.BaseHTTPRequestHandler):
            def do_GET(self):  # noqa: N802
                if self.path != "/v1/setup/status":
                    self.send_error(404)
                    return
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(payload)

            def log_message(self, *a):
                pass

        self.srv = http.server.HTTPServer(("127.0.0.1", 0), H)
        threading.Thread(target=self.srv.serve_forever, daemon=True).start()
        self.url = f"http://127.0.0.1:{self.srv.server_address[1]}"

    def close(self):
        self.srv.shutdown()


def test_the_pin_is_read_from_the_file_the_node_declares(tmp_path):
    pin_file = tmp_path / "claim_pin"
    pin_file.write_text(PIN + "\n")
    node = _StatusNode({"data": {"is_first_run": True, "setup_required": True,
                                 "claim_pin_file": str(pin_file)}})
    try:
        assert bringup.node_claim_pin(node.url, timeout=2) == PIN
    finally:
        node.close()


def test_an_owned_node_has_no_pin_to_hand_over():
    node = _StatusNode({"data": {"is_first_run": False, "setup_required": False}})
    try:
        assert bringup.node_claim_pin(node.url, timeout=2) is None
    finally:
        node.close()


def test_a_first_run_node_whose_pin_cannot_be_read_fails_loudly(tmp_path):
    # Not None: None means "owned, log in", and the leg would then fail at the
    # claim two minutes later for a reason this line already knew.
    missing = tmp_path / "nowhere" / "claim_pin"
    node = _StatusNode({"data": {"is_first_run": True, "setup_required": True,
                                 "claim_pin_file": str(missing)}})
    try:
        with pytest.raises(CannotRun) as e:
            bringup.node_claim_pin(node.url, timeout=1, poll=0.2)
        assert str(missing) in str(e.value)
    finally:
        node.close()


def test_the_android_leg_asks_its_node_for_the_pin(monkeypatch):
    from testing.gate import run_platform

    asked = []
    monkeypatch.setattr(bringup, "node_claim_pin", lambda url, **kw: asked.append(url) or PIN)
    args = type("A", (), dict(platform="android", apk=str(APK), package=PKG, serial=None,
                              activity=bringup.ANDROID_ACTIVITY,
                              node_url="http://127.0.0.1:4243"))()
    plan = run_platform.plan_for(args)
    assert asked == ["http://127.0.0.1:4243"]
    assert "hand-over-claim-pin" in plan.names()
