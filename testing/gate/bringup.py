"""Getting the client running and reachable, per platform (CIRISClient#31).

This is the half of the five-platform gate that could NOT be vendored.
CIRISAgent's `web_ui/__main__.py` brings up *their* app shells built around this
client; ours are `client/androidApp`, `client/iosApp` and `client/desktopApp` —
the real apps. So `testing/gate/platforms.py` keeps their capture code and
raises on `bring_up`, and the bring-up lives here.

WHY A PLAN INSTEAD OF A PILE OF subprocess CALLS

Every step here needs a device, an emulator or a simulator, so a conventional
implementation is untestable until CI has hardware — which means the first time
anyone learns the adb sequence is wrong is on a runner, from a timeout, with no
useful message. That is the position their gate was in for four runs: "the app
was alive for its whole 120s budget and never answered /health", and every
channel that could have said why was broken.

So each platform builds an explicit, inspectable PLAN — a list of steps with a
name and a command — and running it is a separate, trivial function. The plan is
pure data, so the ORDER and the CONTENT are unit-testable on any machine, which
is where the real bugs live: forwarding a port before the emulator is up,
launching before test mode is armed, or reversing the node port not at all.

THE ORDERING INVARIANTS, AND WHY EACH ONE COST SOMEBODY SOMETHING

  1. TEST MODE IS ARMED BEFORE THE APP STARTS. On Android the switch is a
     sentinel file read once at startup (`/data/local/tmp/ciris_test_mode`), so
     touching it after `am start` produces an app with no automation server and
     a /health that never answers. That is indistinguishable from a crash.

  2. THE NODE IS REACHABLE BEFORE THE APP STARTS. The client probes its backend
     during startup. `adb reverse` after launch means the first probe fails and
     the app renders an error state the run then drives blindly.

  3. THE APP IS INSTALLED BEFORE ANYTHING IS FORWARDED. A forward to a package
     that is not there succeeds at the adb layer and fails at the socket, which
     is the confusion CIRISAgent#... their `test_device_failure_attribution`
     exists for: adb accepts on the HOST socket before it tries the device, so a
     dead device port and a live server with a dead handler look identical.
"""

from __future__ import annotations

import os
import shutil
import subprocess
from dataclasses import dataclass, field
from pathlib import Path

#: The automation port the client binds INSIDE the device/emulator. Fixed at
#: 9091 by TestAutomationServer on every platform; only the HOST-side port of a
#: forward is ours to choose.
CLIENT_TEST_PORT = 9091

#: The node's API port, and the port `adb reverse` maps back to the host so the
#: emulator's `localhost:8080` is the node running on the runner.
NODE_API_PORT = 8080

#: Android's test-mode switch. A file, not an env var: `am start` cannot set the
#: environment of the process it launches, so the sentinel is the only handle a
#: harness has. Read once at startup — see invariant 1.
ANDROID_TEST_SENTINEL = "/data/local/tmp/ciris_test_mode"


class CannotRun(RuntimeError):
    """This platform is not available here.

    Raised, never swallowed. A platform that cannot run must SKIP LOUDLY — the
    gate's own rule, and the reason is that a silent skip and a pass are the
    same colour on a dashboard.
    """


@dataclass(frozen=True)
class Step:
    """One command, named for the failure message it will produce."""

    name: str
    cmd: list[str]
    #: Failing this step is not fatal — teardown of something that may not exist.
    optional: bool = False


@dataclass
class Plan:
    """An ordered, inspectable bring-up. Pure data: assertable without hardware."""

    platform: str
    steps: list[Step] = field(default_factory=list)
    #: Host-side URL the driver should talk to once the plan has run.
    test_url: str = ""

    def names(self) -> list[str]:
        return [s.name for s in self.steps]

    def index_of(self, name: str) -> int:
        return self.names().index(name)


def _adb(serial: str | None = None) -> list[str]:
    """adb, resolved from the SDK before PATH.

    Not `shutil.which` first: on a GitHub runner adb lives under
    `$ANDROID_SDK_ROOT/platform-tools` and is not on PATH. Their gate lost every
    Android screenshot to exactly this, silently, on a run that otherwise passed.
    """
    for var in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        root = os.environ.get(var)
        if root and (Path(root) / "platform-tools" / "adb").exists():
            base = [str(Path(root) / "platform-tools" / "adb")]
            break
    else:
        base = [shutil.which("adb") or "adb"]
    return base + (["-s", serial] if serial else [])


def android_plan(apk: Path, package: str, serial: str | None = None,
                 host_port: int = 19091) -> Plan:
    """Emulator on this runner, node on the host, client reaching back to it.

    The node runs on the HOST and the app reaches it through `adb reverse`, so
    the emulator's `localhost:8080` IS the runner's node. That keeps the client
    in the REMOTE-node shape described by FSD/ONE_CLIENT_N_NODES.md and means no
    Android-specific node binary is needed — which is just as well, since
    CIRISServer publishes none.
    """
    adb = _adb(serial)
    return Plan(
        platform="android",
        test_url=f"http://127.0.0.1:{host_port}",
        steps=[
            # Fail here rather than 120s later with no diagnosis.
            Step("wait-for-device", adb + ["wait-for-device"]),
            # INVARIANT 1: armed before launch, because it is read once at startup.
            Step("arm-test-mode", adb + ["shell", "touch", ANDROID_TEST_SENTINEL]),
            # A running app makes `install` hang on some devices (see client/CLAUDE.md).
            Step("force-stop", adb + ["shell", "am", "force-stop", package], optional=True),
            # INVARIANT 3: installed before anything is forwarded.
            Step("install", adb + ["install", "-r", str(apk)]),
            # INVARIANT 2: the node is reachable before the app probes it.
            Step("reverse-node", adb + ["reverse", f"tcp:{NODE_API_PORT}", f"tcp:{NODE_API_PORT}"]),
            Step("forward-automation", adb + ["forward", f"tcp:{host_port}", f"tcp:{CLIENT_TEST_PORT}"]),
            Step("launch", adb + ["shell", "am", "start", "-W", "-n", f"{package}/.MainActivity"]),
        ],
    )


def android_teardown(package: str, serial: str | None = None,
                     host_port: int = 19091) -> Plan:
    """Leave nothing behind that would make the NEXT run lie.

    The sentinel especially: a leftover `ciris_test_mode` file puts a later
    non-test run into test mode, and a stale forward makes a dead app answer on
    a port the next run trusts.
    """
    adb = _adb(serial)
    return Plan(
        platform="android",
        steps=[
            Step("stop-app", adb + ["shell", "am", "force-stop", package], optional=True),
            Step("disarm-test-mode", adb + ["shell", "rm", "-f", ANDROID_TEST_SENTINEL], optional=True),
            Step("remove-forward", adb + ["forward", "--remove", f"tcp:{host_port}"], optional=True),
            Step("remove-reverse", adb + ["reverse", "--remove", f"tcp:{NODE_API_PORT}"], optional=True),
        ],
    )


def ios_simulator_plan(app_bundle: Path, bundle_id: str, udid: str = "booted") -> Plan:
    """Simulator on a macOS runner, node on the same host.

    No forwarding: the simulator shares the host's loopback, so the client's
    9091 and the node's 8080 are both simply `127.0.0.1` from the runner. That
    is why this plan is shorter than Android's rather than more complex.

    Test mode IS an environment variable here — `simctl launch` sets the child's
    environment through `SIMCTL_CHILD_*`, which `am start` has no equivalent for.
    """
    return Plan(
        platform="ios",
        test_url=f"http://127.0.0.1:{CLIENT_TEST_PORT}",
        steps=[
            Step("boot", ["xcrun", "simctl", "boot", udid], optional=True),
            Step("wait-for-boot", ["xcrun", "simctl", "bootstatus", udid, "-b"]),
            Step("uninstall", ["xcrun", "simctl", "uninstall", udid, bundle_id], optional=True),
            Step("install", ["xcrun", "simctl", "install", udid, str(app_bundle)]),
            # --terminate-existing: without it a previous instance survives and
            # the new launch is a no-op against a stale process holding 9091.
            Step("launch", [
                "xcrun", "simctl", "launch", "--terminate-existing", udid, bundle_id,
            ]),
        ],
    )


def ios_teardown(bundle_id: str, udid: str = "booted") -> Plan:
    return Plan(
        platform="ios",
        steps=[Step("terminate", ["xcrun", "simctl", "terminate", udid, bundle_id], optional=True)],
    )


def desktop_plan(jar: Path, display_wrapped: bool = True) -> Plan:
    """The desktop app, which `testing/run_e2e.py` already launches.

    Kept as a plan for symmetry so the workflow treats all five legs alike, but
    the authority is run_e2e.py: it owns the corner matrix, the node fixture and
    the teardown that signals the whole process group. Duplicating that here
    would be a second contract for the platform that already works.
    """
    cmd = ["java", "-jar", str(jar)]
    if display_wrapped:
        # Compose Desktop needs a display and a runner has no X server.
        cmd = ["xvfb-run", "-a"] + cmd
    return Plan(
        platform="desktop",
        test_url=f"http://127.0.0.1:{CLIENT_TEST_PORT}",
        steps=[Step("launch", cmd)],
    )


def run(plan: Plan, timeout: float = 300.0, check: bool = True) -> list[tuple[Step, int]]:
    """Execute a plan in order, returning (step, returncode) for each.

    A non-optional failure raises with the step NAME and the command's stderr,
    because "bring-up failed" without either is the message their gate spent
    four runs unable to improve on.
    """
    results: list[tuple[Step, int]] = []
    for step in plan.steps:
        try:
            proc = subprocess.run(step.cmd, capture_output=True, text=True, timeout=timeout)
            code, stderr = proc.returncode, proc.stderr or ""
        except (FileNotFoundError, NotADirectoryError) as e:
            # A MISSING TOOL IS A STEP FAILURE, NOT AN EXCEPTION THAT ESCAPES.
            #
            # subprocess raises before there is a returncode, so `check=False`
            # did not protect teardown from it: on a machine with no adb, tearing
            # down after a failure crashed with FileNotFoundError and buried the
            # real error underneath its traceback. That is precisely the
            # "a teardown that fails hides the failure that caused it" rule that
            # every teardown step being optional exists to honour, defeated one
            # layer below where it was written.
            code, stderr = 127, f"{step.cmd[0]}: not found ({e})"
        except subprocess.TimeoutExpired as e:
            code, stderr = 124, f"timed out after {timeout}s ({e})"
        results.append((step, code))
        if code != 0 and not step.optional and check:
            raise CannotRun(
                f"{plan.platform} bring-up failed at {step.name!r}\n"
                f"  command: {' '.join(step.cmd)}\n"
                f"  exit:    {code}\n"
                f"  stderr:  {stderr.strip()[:2000]}"
            )
    return results
