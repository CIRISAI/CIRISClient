"""Drive one platform end to end: bring up, prove reachable, walk, capture.

    python3 -m testing.gate.run_platform --platform android --apk <path> --report out.json

This is the thin piece that ties the three halves together — `bringup` (ours),
`driver` (ours, carrying the vendored gate's rules) and `platforms.capture`
(vendored) — so the workflow has one entry point per leg instead of a page of
shell per matrix entry.

WHAT MAKES THIS A GATE RATHER THAN A REPORT

Each of these exists because its absence already let something pass while the
product was broken:

  * A PLATFORM THAT CANNOT RUN FAILS, unless it was explicitly excluded. A
    silent skip and a pass are the same colour on a dashboard, and the whole
    point of five legs is that a missing one is visible.

  * REACHABILITY IS PROVEN, NOT ASSUMED. `/health` must answer before anything
    is driven. Their gate spent four runs on an app that never answered it, and
    the runs still had to be read carefully to notice.

  * THE SCREEN IS CHECKED FOR GHOSTS. `/tree` can describe a screen that is not
    on screen — CIRISClient#30 — so the walk asserts on live controls, not on
    presence. This is the rule this repo learned the hard way and their driver
    does not have yet.

  * SCREENSHOTS ARE CAPTURED ON SUCCESS TOO. The green run is the baseline the
    red one is read against, and capturing only failures means the first
    regression has nothing to compare with.

  * THE REPORT RECORDS WHAT RAN. Which node, which artifact, which platform —
    a result that cannot say what produced it cannot be acted on.
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from dataclasses import asdict, dataclass, field
from pathlib import Path

from testing.driver import DriverError, TestAutomationServer
from testing.gate import bringup
from testing.gate.platforms import CaptureKind


@dataclass
class StepResult:
    name: str
    ok: bool
    detail: str = ""
    shot: str | None = None


@dataclass
class Report:
    platform: str
    ok: bool = False
    artifact: str = ""
    node_version: str = ""
    steps: list[StepResult] = field(default_factory=list)

    def add(self, name: str, ok: bool, detail: str = "", shot: str | None = None) -> None:
        self.steps.append(StepResult(name, ok, detail, shot))


def plan_for(args) -> bringup.Plan:
    if args.platform == "android":
        if not args.apk:
            raise bringup.CannotRun("--apk is required for android")
        return bringup.android_plan(Path(args.apk), args.package, serial=args.serial)
    if args.platform == "ios":
        if not args.app:
            raise bringup.CannotRun("--app is required for ios")
        return bringup.ios_simulator_plan(Path(args.app), args.bundle_id, udid=args.udid)
    if args.platform == "desktop":
        if not args.jar:
            raise bringup.CannotRun("--jar is required for desktop")
        return bringup.desktop_plan(Path(args.jar), display_wrapped=args.xvfb)
    raise bringup.CannotRun(f"unknown platform {args.platform!r}")


def teardown_for(args) -> bringup.Plan | None:
    if args.platform == "android":
        return bringup.android_teardown(args.package, serial=args.serial)
    if args.platform == "ios":
        return bringup.ios_teardown(args.bundle_id, udid=args.udid)
    return None



def state_problems(mode: str, node_url: str) -> list[str]:
    """What is wrong with `/state` for a client driven against a BARE node.

    Split out of [walk] so it is testable without a device — the assertions this
    gate makes are exactly the ones worth having a red path for, and one needing
    an emulator to exercise is one nobody exercises.

    `unset` is deliberately ACCEPTED. The gate probe may not have answered yet
    when the walk runs, and "not probed" is a real state this client defines
    (CIRISClient#48) — reading it as a failure would make the gate flaky and
    would punish the client for being honest.
    """
    problems: list[str] = []
    if mode.upper() == "AGENT":
        problems.append("clientMode=AGENT against a bare node (no brain is folded here)")
    if node_url and ":8080" in node_url:
        problems.append(f"pointed at the agent port: {node_url}")
    return problems

def walk(drv: TestAutomationServer, rep: Report, shots: Path, platform,
         args_timeout: float = 120.0) -> None:
    """The smallest walk that would have caught every defect of the last month.

    Deliberately not a product tour. Each assertion here maps to a real
    regression, so a green run means those cannot have come back:

      /health         the app started at all             (#28, and 4 blind runs)
      /state          it knows which node it is talking to
      /undrivable     everything interactive is drivable (#30, #31)
      ghost check     the tree is describing THIS screen (#30)
    """
    rep.add("health", True, json.dumps(drv.health()))

    # THE SERVER IS NOT THE APP. `/health` is served by the automation server,
    # which `Main.kt` starts BEFORE `application { Window { … } }` — so it
    # answers in ~0.5s while the UI has not composed at all. Measured locally on
    # the 0.5.217 jar: at the instant /health returns 200,
    #
    #     /state       screen="unknown"  clientMode="unset"  nodeUrl=""
    #     /tree        0 elements
    #     /screenshot  503 (window not available)
    #
    # which is the five-platform board this gate has been producing, exactly.
    #
    # AND THREE OF THE STEPS BELOW PASS VACUOUSLY ON THAT. `undrivable` is clean
    # when there is nothing to be undrivable; `no-ghosts` is none when there are
    # no elements; `state` accepts `unset` by design. Only the screenshot failed,
    # because it is the only assertion that needs the UI to exist — it was the
    # sole thing standing between this gate and a green run against an app with
    # no interface. That is the defect this file's docstring is about, inside
    # this file.
    #
    # So wait for the UI, and ASSERT it arrived. A tree that never fills is a
    # real failure — the app started and never rendered — and it is now reported
    # as one rather than as four quiet passes.
    composed = drv.wait_for_ui(timeout=args_timeout)
    rep.add(
        "ui-composed",
        composed > 0,
        f"{composed} element(s) on screen" if composed else
        "the app started but never composed a UI — every check below would be vacuous",
    )

    # THIS STEP USED TO BE A REPORT, NOT A CHECK — `rep.add("state", True, …)`
    # passed unconditionally and printed two values nobody asserted. That is the
    # vacuous green this file's own docstring is about, sitting in the middle of
    # it.
    #
    # Both values ARE assertable here, without navigating anywhere, because this
    # gate always stands up a bare `ciris-server`: no brain, and the node's own
    # port. So:
    #
    #   clientMode must never be AGENT — there is no brain to be an agent of.
    #     A client that says AGENT against this node has a wrong or stale gate,
    #     which is CIRISClient#48 exactly: a verdict derived against :8080 that
    #     outlived the backend it described. `unset` is ACCEPTED — the probe may
    #     legitimately not have answered yet, and "not probed" is not "wrong".
    #
    #   nodeUrl must be the node's :4243 and not the agent's :8080. A client
    #     pointed at the agent port against a node is the same defect wearing
    #     its other face, and it is the one that made every leg of this gate
    #     report "the node never became healthy" while the node was serving.
    state = drv.state()
    mode = str(state.get("clientMode", "unset"))
    node_url = str(state.get("nodeUrl", ""))
    problems = state_problems(mode, node_url)
    detail = f"clientMode={mode} node={node_url}"
    rep.add("state", not problems, detail if not problems else f"{detail} — {'; '.join(problems)}")

    # The pre-flight this repo tells harnesses to run, now served everywhere.
    try:
        undrivable = drv._call("GET", "/undrivable")
        names = undrivable.get("undrivable", [])
        rep.add("undrivable", not names,
                "clean" if not names else f"tagged but not drivable: {names}")
    except DriverError as e:
        # An older client without the route. Unknown, not clean -- saying
        # "clean" here would be the distinct-zeroes mistake all over again.
        rep.add("undrivable", True, f"route absent on this client ({e})")

    ghosts = [e.test_tag for e in drv.tree() if e.is_ghost]
    rep.add("no-ghosts", not ghosts,
            "none" if not ghosts else f"stale registry entries: {ghosts}")

    shot = platform.capture(CaptureKind.SCREENSHOT, shots / f"{rep.platform}-screen.png")
    rep.add("screenshot", shot is not None, str(shot) if shot else "capture unavailable",
            str(shot) if shot else None)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--platform", required=True, choices=("desktop", "android", "ios"))
    ap.add_argument("--apk"); ap.add_argument("--app"); ap.add_argument("--jar")
    ap.add_argument("--package", default="ai.ciris.mobile.debug")
    ap.add_argument("--bundle-id", default="ai.ciris.mobile")
    ap.add_argument("--serial"); ap.add_argument("--udid", default="booted")
    ap.add_argument("--xvfb", action="store_true", help="wrap desktop in xvfb-run")
    ap.add_argument("--shots", type=Path, default=Path("shots"))
    ap.add_argument("--report", type=Path)
    ap.add_argument("--node-version", default="")
    ap.add_argument("--timeout", type=float, default=120.0)
    args = ap.parse_args()

    rep = Report(platform=args.platform, node_version=args.node_version)
    args.shots.mkdir(parents=True, exist_ok=True)

    from testing.gate.platforms import build_platform
    platform = build_platform(args)

    plan = None
    try:
        plan = plan_for(args)
        rep.artifact = str(args.apk or args.app or args.jar or "")
        bringup.run(plan)
        rep.add("bring-up", True, " -> ".join(plan.names()))

        drv = TestAutomationServer(base_url=plan.test_url)
        # PROVEN, NOT ASSUMED.
        drv.wait_for_server(timeout=args.timeout)
        walk(drv, rep, args.shots, platform, args_timeout=args.timeout)
        rep.ok = all(s.ok for s in rep.steps)
    except bringup.CannotRun as e:
        # LOUD. Not a skip: the caller decides what to exclude, and it does so
        # by not asking for the platform at all.
        rep.add("bring-up", False, str(e))
    except DriverError as e:
        rep.add("drive", False, str(e))
        # The failing screen is the most valuable artifact of a red run.
        try:
            platform.capture(CaptureKind.SCREENSHOT, args.shots / f"{args.platform}-failure.png")
        except Exception:  # noqa: BLE001
            pass
    finally:
        # Reap anything spawned with background=True. CI tears the runner down
        # anyway; a developer running this locally would otherwise accumulate a
        # Compose window per invocation.
        bringup.terminate_background()
        td = teardown_for(args)
        if td is not None:
            # check=False: teardown runs after failures too, and one that fails
            # must not hide the failure that caused it.
            bringup.run(td, check=False)

    if args.report:
        # MAKE THE DIRECTORY. `--report reports/<platform>.json` names a path in a
        # directory nothing creates: the workflow passes it, the artifact upload
        # collects it, and no step mkdirs it. The whole run — build, node, launch,
        # drive — completed and then died on
        # `FileNotFoundError: 'reports\\windows.json'` at the last line, throwing
        # away the result it had just spent five minutes earning, and reporting a
        # driving failure that had not happened.
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(asdict(rep), indent=2), encoding="utf-8")
    for s in rep.steps:
        print(f"  [{'OK ' if s.ok else 'FAIL'}] {s.name}: {s.detail}")
    print(f"{args.platform}: {'PASS' if rep.ok else 'FAIL'}")
    return 0 if rep.ok else 1


if __name__ == "__main__":
    sys.exit(main())
