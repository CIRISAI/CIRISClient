"""Run this repo's CSD flows against a live client — the `testable` half of CSD/3.

    # against a client that is already running (a desktop at a keyboard):
    python3 -m testing.gate.run_flows --platform desktop --flows testing/flows

    # on the matrix: the same code, called by run_platform after its smoke walk,
    # in the same process and against the app that walk just brought up:
    python3 -m testing.gate.run_platform --platform desktop --jar … --flows testing/flows

CSD.md §1: a CSD reaches `testable` when "floor flips off unreleased; flow runs on
the matrix". This is the thing that runs it.

FOUR VERDICTS PER FLOW, AND ONLY TWO OF THEM ARE GREEN.

    pass          every step's requires/do/expect held
    refused       the flow's `client:` floor is above the client under test. The
                  flow cannot start HERE, and says why; it is neither passed nor
                  failed, and it does not redden the leg
    cannot-start  the floor is met, but the flow's first `requires` never held —
                  the client never reached the screen the flow starts on
    fail          it started and a step broke

`cannot-start` REDDENS THE LEG, deliberately, and differs from CIRISAgent's gate
here. Upstream reports it as a warning because it was their only way to hold a
flow for a surface no release carried. This repo has the `client:` floor for that
(`unreleased`, `>X`). With the floor met, a flow that never reached its first
screen is a flow that silently never ran — and a leg that stays green while its
only flow never ran is the vacuous green this harness exists to refuse.

LOADING IS ALL-OR-NOTHING, AND IT HAPPENS FIRST. Every flow must parse, name its
CSD, and name no `proposed:` tag before anything is driven: a spec error found
after ten minutes of emulator is ten minutes wasted, and one found after a
partial run hides behind the flows that did run.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import sys
import time
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, List, Optional, Sequence

from testing.gate.flow_spec import FlowRunner, FlowSpec, SpecError, check_client_floor, discover

REPO = Path(__file__).resolve().parents[2]
DEFAULT_FLOWS = REPO / "testing" / "flows"

PASS, FAIL, REFUSED, CANNOT_START = "pass", "fail", "refused", "cannot-start"
#: The only verdicts that leave a leg green.
GREEN = {PASS, REFUSED}

#: Screens a signed-out client can be on. Anything else is taken as signed in.
SIGNED_OUT = {"Login", "Setup"}


@dataclass
class FlowOutcome:
    flow: str
    csd: Optional[str]
    status: str
    detail: str = ""
    steps: List[dict] = field(default_factory=list)
    report: Optional[str] = None


def default_client_version() -> str:
    """This tree's version. On the matrix the artifact IS this tree's (asserted
    by candidate_artifacts), so the floor is checked against the candidate."""
    try:
        return (REPO / "VERSION").read_text(encoding="utf-8").strip()
    except OSError:
        return ""


def load_flows(paths: Sequence[str | Path], csd_root: Optional[Path] = None) -> List[FlowSpec]:
    """Every flow, loaded and bound to its CSD — or a SpecError naming the first
    that is not. Never a partial list."""
    files = discover([str(p) for p in paths])
    if not files:
        raise SpecError(f"no flows found in {[str(p) for p in paths]}")
    specs: List[FlowSpec] = []
    seen: dict[str, Path] = {}
    for path in files:
        spec = FlowSpec.load(path, csd_root=csd_root)
        if spec.csd is None:
            raise SpecError(
                f"{path}: names no `csd:`. Every flow in this repo tests a CSD, and the "
                f"runner reads that CSD's `shows:` and `states:` to check it"
            )
        if spec.flow in seen:
            raise SpecError(f"{path}: flow id {spec.flow!r} is also used by {seen[spec.flow]}")
        seen[spec.flow] = path
        specs.append(spec)
    return specs


async def _settle_on(helper, screen: str, timeout: float, poll: float = 1.0) -> str:
    """Wait for the flow's starting screen. Not navigation — a landing that is
    still composing is not a flow that cannot start. Returns the last screen."""
    deadline = time.monotonic() + timeout
    cur = await helper.get_screen()
    while cur != screen and time.monotonic() < deadline:
        await asyncio.sleep(poll)
        cur = await helper.get_screen()
    return cur


async def run_one(spec: FlowSpec, helper, *, platform=None, artifacts: Optional[Path] = None,
                  client_version: Optional[str] = None, start_timeout: float = 30.0) -> FlowOutcome:
    refusal = check_client_floor(spec.client_floor, client_version)
    if refusal:
        print(f"\n FLOW {spec.flow} ({spec.csd_id}) — REFUSED by its floor\n   {refusal}")
        return FlowOutcome(spec.flow, spec.csd_id, REFUSED, refusal)

    start = spec.steps[0].requires.screen
    if start:
        await _settle_on(helper, start, start_timeout)

    runner = FlowRunner(helper, platform=platform, artifacts=artifacts)
    try:
        ok = await runner.run(spec)
    except Exception as e:  # noqa: BLE001 — a crash in a flow is that flow's verdict
        ok = False
        runner.results.append(_crash(e))
    report = runner.write_report(spec)
    steps = [asdict(r) for r in runner.results]
    print(f"\n  {runner.summary(spec)}")

    if ok:
        status, detail = PASS, runner.summary(spec)
    else:
        bad = next((r for r in reversed(runner.results) if r.status == "fail"), None)
        first = runner.results[0] if runner.results else None
        if first is not None and len(runner.results) == 1 and first.phase == "requires":
            status, detail = CANNOT_START, f"first step {first.step_id!r}: {first.detail}"
        else:
            status = FAIL
            detail = f"step {bad.step_id!r} ({bad.phase}): {bad.detail}" if bad else "failed"
    return FlowOutcome(spec.flow, spec.csd_id, status, detail, steps, str(report) if report else None)


def _crash(e: Exception):
    from testing.gate.flow_spec import StepResult  # noqa: PLC0415
    return StepResult("<runner>", "the runner raised", "fail", "do", f"{type(e).__name__}: {e}")


def leg_ok(outcomes: Sequence[FlowOutcome]) -> bool:
    return all(o.status in GREEN for o in outcomes)


def summary(outcomes: Sequence[FlowOutcome]) -> str:
    counts = {s: sum(1 for o in outcomes if o.status == s) for s in (PASS, FAIL, CANNOT_START, REFUSED)}
    return ", ".join(f"{n} {s}" for s, n in counts.items() if n) or "no flows"


def sign_in(drv, username: str, password: str) -> str:
    """Reuse the session if the client has one; make one if it does not."""
    from testing.gate import session_fixture  # noqa: PLC0415

    screen = drv.screen()
    if screen not in SIGNED_OUT:
        return screen
    session_fixture.run_setup(drv, username, password)
    return session_fixture.log_in(drv, username, password)


def run_all(specs: Sequence[FlowSpec], drv, *, platform=None, artifacts: Optional[Path] = None,
            client_version: Optional[str] = None, username: str = "qaadmin",
            password: str = "QaAdmin!2345", establish_session: bool = True,
            helper: Any = None) -> List[FlowOutcome]:
    """Run every flow in order against one live client. Signs in once, only if
    some flow will actually run."""
    from testing.gate.flow_helper import SyncFlowHelper  # noqa: PLC0415

    helper = helper or SyncFlowHelper(drv)
    runnable = [s for s in specs if not check_client_floor(s.client_floor, client_version)]
    if runnable and establish_session and drv is not None:
        landed = sign_in(drv, username, password)
        print(f"  session: signed in, on {landed!r}")

    async def go() -> List[FlowOutcome]:
        return [await run_one(s, helper, platform=platform, artifacts=artifacts,
                              client_version=client_version) for s in specs]

    outcomes = asyncio.run(go())
    print(f"\n flows: {summary(outcomes)}")
    for o in outcomes:
        print(f"  [{o.status:^12}] {o.flow} ({o.csd}): {o.detail}")
    return outcomes


def main(argv: Optional[List[str]] = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--platform", default="desktop", choices=("desktop", "android", "ios"))
    ap.add_argument("--url", default="http://127.0.0.1:9091", help="the client's test server")
    ap.add_argument("--flows", action="append", help=f"a flow or a directory (default {DEFAULT_FLOWS})")
    ap.add_argument("--csd-root", type=Path, help="where the CSDs live (default FSD/CSD)")
    ap.add_argument("--client-version", default=None,
                    help="the version under test, for `client:` floors (default: VERSION)")
    ap.add_argument("--artifacts", type=Path, help="screenshots and per-flow JSON")
    ap.add_argument("--report", type=Path, help="write every outcome here as JSON")
    ap.add_argument("--username", default="qaadmin")
    ap.add_argument("--password", default="QaAdmin!2345")
    ap.add_argument("--no-sign-in", action="store_true",
                    help="drive whatever screen the client is on; do not make a session")
    args = ap.parse_args(argv)

    try:
        specs = load_flows(args.flows or [DEFAULT_FLOWS], args.csd_root)
    except SpecError as e:
        print(f"[FAIL] {e}")
        return 1
    print(f"loaded {len(specs)} flow(s): {', '.join(f'{s.flow} ({s.csd_id})' for s in specs)}")

    from testing.driver import DriverError, TestAutomationServer  # noqa: PLC0415
    from testing.gate.platforms import build_platform  # noqa: PLC0415
    from testing.gate.session_fixture import SessionUnavailable  # noqa: PLC0415

    drv = TestAutomationServer(base_url=args.url)
    version = args.client_version if args.client_version is not None else default_client_version()
    artifacts = args.artifacts or Path("shots") / f"flows-{args.platform}"
    try:
        drv.wait_for_server(timeout=30)
        outcomes = run_all(specs, drv, platform=build_platform(args), artifacts=artifacts,
                           client_version=version, username=args.username,
                           password=args.password, establish_session=not args.no_sign_in)
    except (DriverError, SessionUnavailable) as e:
        print(f"[FAIL] {e}")
        return 1
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps([asdict(o) for o in outcomes], indent=2), encoding="utf-8")
    ok = leg_ok(outcomes)
    print(f"flows: {'PASS' if ok else 'FAIL'}")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
