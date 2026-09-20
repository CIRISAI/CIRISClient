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
#: emulator's `localhost:4243` is the node running on the runner.
#:
#: 4243, NOT 8080. This gate downloads a released `ciris-server` — a bare NODE,
#: no brain — and a bare node binds :4242 (transport) and :4243 (read API) and
#: never binds 8080. That was confirmed empirically as well as from the client's
#: own constants: in the 2026-09-08 nightly the ONLY occurrence of 8080 anywhere
#: in the Windows node log was the gate's own curl.
#:
#:      AGENT_ENDPOINT     = :8080 /v1/system/health
#:      NODE_ONLY_ENDPOINT = :4243 /health
#:      (client/shared/.../platform/BackendEndpoint.kt)
#:
#: Reversing 8080 forwarded the emulator to a host port with nothing on it.
#:
#: SUFFICIENT ON ITS OWN. An earlier note here said the client would still look
#: for :8080 until the gate seeded `CIRIS_RUN_WITHOUT_AI` into each platform's
#: home. That was wrong, and the reason has since changed once more
#: (CIRISClient#54): with an absent .env the endpoint resolves to AGENT and
#: `syncBackendFrom` now moves the client to :8080 ONLY IF a brain answers
#: there. The gate runs a bare node and nothing on :8080, so the probe fails
#: and the app stays on its `:4243` default — the same outcome, now for a
#: reason that also serves a real with-AI install. Confirmed by the gate
#: itself — every desktop leg reports clientMode=NODE at :4243.
NODE_API_PORT = 4243

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
    #: Extra environment for THIS command, layered over the process's own.
    #: `simctl launch` forwards `SIMCTL_CHILD_*` into the app; nothing else
    #: reaches a simulator process, so the iOS launch step carries test mode
    #: this way. Read by [run].
    env: dict[str, str] = field(default_factory=dict)
    #: THE COMMAND IS THE APP, NOT A COMMAND ABOUT THE APP.
    #:
    #: Every other step here asks something to do a thing and exits: `adb
    #: install`, `am start -W`, `simctl launch`. The desktop app is different —
    #: `java -jar <app>.jar` IS the running client, and waiting for it to exit
    #: waits until somebody closes the window.
    #:
    #: So the desktop leg could never pass. It timed out at 300s with exit 124
    #: on every platform that has one, and the plan's own docstring called
    #: itself "kept as a plan for symmetry", which is what a stub that was never
    #: run looks like from the outside.
    #:
    #: A background step is SPAWNED and its liveness is proven afterwards by
    #: `wait_for_server` — which run_platform already calls, and which is the
    #: honest test anyway: a launch that returned 0 proves nothing about whether
    #: the app came up.
    background: bool = False


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


#: The launch component's CLASS. Not derived from the package: the debug APK's
#: package is `ai.ciris.mobile.debug` (applicationIdSuffix) but its activity is
#: declared `.MainActivity` relative to the NAMESPACE, `ai.ciris.mobile`. So
#: `am start -n ai.ciris.mobile.debug/.MainActivity` names
#: `ai.ciris.mobile.debug.MainActivity`, a class that does not exist — and
#: `am start` prints "Error: Activity class ... does not exist" and EXITS 0.
#: Every Android leg of this gate failed at await-process on exactly that, and
#: the failure read as "the app never started" (it never could). CIRISAgent's
#: driver has it right: ANDROID_ACTIVITY = "ai.ciris.mobile.MainActivity".
ANDROID_ACTIVITY = "ai.ciris.mobile.MainActivity"


def android_plan(apk: Path, package: str, serial: str | None = None,
                 host_port: int = 19091, activity: str = ANDROID_ACTIVITY) -> Plan:
    """Emulator on this runner, node on the host, client reaching back to it.

    The node runs on the HOST and the app reaches it through `adb reverse`, so
    the emulator's `localhost:4243` IS the runner's node. That keeps the client
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
            Step("launch", adb + ["shell", "am", "start", "-W", "-n", f"{package}/{activity}"]),
            # A LAUNCH THAT RETURNED 0 IS NOT A PROCESS.
            #
            # `am start -W` reported success and the app never started: logcat
            # showed `START u0 {cmp=ai.ciris.mobile.debug/.MainActivity}` with no
            # matching `Start proc`. That was first read as dexopt running late
            # on a freshly installed debuggable APK; the component name was the
            # actual cause (see ANDROID_ACTIVITY). The pid loop stays, because a
            # launch that returned 0 still proves nothing about a process.
            #
            # So ask the only question that settles it — is there a pid — and
            # re-issue the start until there is. On device, because a shell loop
            # here would pay adb's round trip 30 times.
            #
            # The last `am start`'s output is KEPT and printed on failure. It was
            # sent to /dev/null, which is how a component name that resolved to
            # no class at all ("Error: Activity class {...} does not exist",
            # exit 0) spent months looking like a slow dexopt.
            Step(
                "await-process",
                adb + [
                    "shell",
                    "for i in $(seq 1 30); do "
                    f"pidof {package} > /dev/null 2>&1 && exit 0; "
                    f"out=$(am start -n {package}/{activity} 2>&1); "
                    "sleep 2; done; "
                    "echo 'no process after 60s'; echo \"last am start: $out\"; exit 1",
                ],
            ),
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
    9091 and the node's 4243 are both simply `127.0.0.1` from the runner. That
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
            # A PREVIOUS INSTANCE IS ENDED FIRST, AS ITS OWN STEP. Without
            # that a stale process keeps 9091 and the new launch is a no-op
            # against the OLD build. This used to be `launch
            # --terminate-existing`, which is devicectl's flag for a physical
            # device; simctl rejected it ("Invalid device: --terminate-existing",
            # exit 148) on the first run that ever reached the launch (run
            # 35353482427). Optional: on a fresh simulator there is nothing to
            # end.
            Step("terminate", ["xcrun", "simctl", "terminate", udid, bundle_id], optional=True),
            # TEST MODE HAS TO BE SAID HERE. The app reads CIRIS_TEST_MODE with
            # getenv (TestAutomationServer.ios.kt) and starts its automation
            # server only when it is set; `simctl launch` hands the child only
            # the SIMCTL_CHILD_* variables of ITS environment. The workflow's
            # CIRIS_TEST_MODE=true never crossed that boundary, so even a built
            # app would have come up with no server to drive.
            Step("launch", [
                "xcrun", "simctl", "launch", udid, bundle_id,
            ], env={"SIMCTL_CHILD_CIRIS_TEST_MODE": "true"}),
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
        steps=[Step("launch", cmd, background=True)],
    )


#: Handles for steps spawned with `background=True`, so a caller can reap them.
#: CI tears the whole runner down, but a developer running this locally would
#: otherwise leave a Compose window behind on every invocation.
_BACKGROUND: list[subprocess.Popen] = []

#: Open log files for spawned steps, closed alongside them.
_BACKGROUND_LOGS: list = []


def terminate_background() -> None:
    """Stop anything `run()` spawned. Safe to call twice, and never raises."""
    while _BACKGROUND_LOGS:
        handle = _BACKGROUND_LOGS.pop()
        try:
            handle.close()
        except Exception:  # noqa: BLE001
            pass
    while _BACKGROUND:
        proc = _BACKGROUND.pop()
        try:
            proc.terminate()
            proc.wait(timeout=10)
        except Exception:  # noqa: BLE001 — teardown must not hide a real failure
            pass


def run(plan: Plan, timeout: float = 300.0, check: bool = True) -> list[tuple[Step, int]]:
    """Execute a plan in order, returning (step, returncode) for each.

    A non-optional failure raises with the step NAME and the command's stderr,
    because "bring-up failed" without either is the message their gate spent
    four runs unable to improve on.
    """
    results: list[tuple[Step, int]] = []
    for step in plan.steps:
        try:
            if step.background:
                # Spawned, not awaited. Liveness is proven by wait_for_server;
                # the only failure this can report is "it would not start at
                # all", which Popen raises as FileNotFoundError below.
                # THE APP'S OWN OUTPUT IS THE DIAGNOSTIC CHANNEL, AND I THREW
                # IT AWAY. The first version of this sent stdout and stderr to
                # DEVNULL, which is how `screenshot: capture unavailable` became
                # a failure with no reason attached on three platforms at once —
                # the app was surely saying why and nobody could hear it. This
                # gate's own rule is that a failure you cannot diagnose from the
                # artifact costs a re-run to learn what the first run already
                # knew.
                #
                # Written beside the node's log, which each leg already uploads.
                log_path = Path(f"{step.name}-app.log")
                log_handle = open(log_path, "wb")  # noqa: SIM115 — closed by the reaper
                _BACKGROUND_LOGS.append(log_handle)
                proc_bg = subprocess.Popen(
                    step.cmd,
                    stdout=log_handle,
                    stderr=subprocess.STDOUT,
                    env={**os.environ, **step.env} if step.env else None,
                )
                _BACKGROUND.append(proc_bg)
                results.append((step, 0))
                continue
            proc = subprocess.run(
                step.cmd, capture_output=True, text=True, timeout=timeout,
                env={**os.environ, **step.env} if step.env else None,
            )
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
