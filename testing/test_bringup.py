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

from pathlib import Path

import pytest

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


def test_ios_terminates_any_existing_instance_on_launch():
    # Without --terminate-existing the launch is a no-op against a stale process
    # still holding 9091, and the run drives the OLD build.
    p = ios_simulator_plan(APP, BID)
    launch = p.steps[p.index_of("launch")]
    assert "--terminate-existing" in launch.cmd


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
