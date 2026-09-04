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


def walk(drv: TestAutomationServer, rep: Report, shots: Path, platform) -> None:
    """The smallest walk that would have caught every defect of the last month.

    Deliberately not a product tour. Each assertion here maps to a real
    regression, so a green run means those cannot have come back:

      /health         the app started at all             (#28, and 4 blind runs)
      /state          it knows which node it is talking to
      /undrivable     everything interactive is drivable (#30, #31)
      ghost check     the tree is describing THIS screen (#30)
    """
    rep.add("health", True, json.dumps(drv.health()))

    state = drv.state()
    rep.add("state", True, f"clientMode={state.get('clientMode')} node={state.get('nodeUrl')}")

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
        walk(drv, rep, args.shots, platform)
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
        td = teardown_for(args)
        if td is not None:
            # check=False: teardown runs after failures too, and one that fails
            # must not hide the failure that caused it.
            bringup.run(td, check=False)

    if args.report:
        args.report.write_text(json.dumps(asdict(rep), indent=2), encoding="utf-8")
    for s in rep.steps:
        print(f"  [{'OK ' if s.ok else 'FAIL'}] {s.name}: {s.detail}")
    print(f"{args.platform}: {'PASS' if rep.ok else 'FAIL'}")
    return 0 if rep.ok else 1


if __name__ == "__main__":
    sys.exit(main())
