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
    cannot-start  the floor is met, but the flow never reached its first screen:
                  nav_map has no hop to it on this build, a hop tag was missing
                  mid-walk, or the first `requires` still did not hold

THE RUNNER WALKS TO THE FIRST SCREEN. Sign-in lands on Contacts; a flow for any
other surface starts elsewhere. Before step one, the runner clicks the hop
`nav_map` derives for the flow's first `requires: screen:` (circle, tab, row),
waiting for each tag. The flow never encodes the hop (FSD/CSD_STANDARD.md §5).
Flow-only screens (pre-login, wizards, leaves) have no hop and are waited for.
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
import re
import json
import sys
import time
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Callable, List, Optional, Sequence

from testing.gate.console import utf8_console
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


async def _on_screen(helper) -> List[str]:
    """Tags on screen now, by the runner's own rule (FlowRunner._drivable)."""
    try:
        elements = await helper.get_elements()
    except Exception:  # noqa: BLE001 — diagnosis must never raise
        return []
    out = []
    for e in elements:
        vis = getattr(e, "visible", None)
        shown = vis if vis is not None else (getattr(e, "width", 1) > 0 and getattr(e, "height", 1) > 0)
        if shown:
            out.append(e.test_tag)
    return sorted(out)


async def _hop_landed(helper, tag: str, timeout: float, poll: float = 0.25) -> Optional[str]:
    """After a circle or tab hop is clicked, wait for the shell to SAY it stands
    there (`/state`'s `circle` / `tab`). None once it does, or on a client that
    serves neither (an older client: the hop is walked unverified); else why.

    THE CLICK IS NOT THE HOP. `CIRISApp.openTab` runs with the `circleNow` the
    last composition captured, so a tab clicked before the frame after the
    circle click has recomposed opens the OLD circle's tab. Every desktop leg
    of the 2026-09-29 run lost four flows to that: Just me's Rules tab has no
    Wallet row, its Safety tab has one card and opens ChildSafety directly, its
    People tab opens Contacts directly — each reported as a row that "never
    appeared", and on macOS, where a node client signs in under Neighbours,
    even `circle_agent -> tab_chats` landed on Rooms.
    """
    if tag.startswith("circle_"):
        key, want = "circle", tag[len("circle_"):].replace("_", "-")
    elif tag.startswith("tab_"):
        key, want = "tab", tag[len("tab_"):]
    else:
        return None
    read = getattr(helper, "get_state", None)
    if read is None:
        return None
    deadline = time.monotonic() + timeout
    while True:
        state = await read()
        if not isinstance(state, dict) or key not in state:
            return None
        got = state.get(key)
        if got == want:
            return None
        if time.monotonic() >= deadline:
            return (f"{tag!r} was clicked, but the shell still stands in {key} {got!r} "
                    f"after {timeout:.0f}s — the hop did not take")
        await asyncio.sleep(poll)


async def navigate(helper, screen: str, chain: Sequence[str], *, hop_timeout: float = 20.0,
                   arrive_timeout: float = 20.0) -> Optional[str]:
    """Walk `chain` (nav_map's derived hop) to `screen`. None on arrival, else
    the reason — naming the hop tag that was missing, because "could not reach
    Screen.X" alone sends the reader to the wrong end of the chain, and listing
    what WAS on screen, because that is what names the cause.

    Each tag is WAITED for before it is clicked: a circle's tabs compose after
    the circle is chosen, and clicking before they exist is a race, not a test.
    And each circle or tab hop is VERIFIED to have landed before the next is
    clicked (`_hop_landed`): a click that succeeded is not a hop that took.
    """
    for i, tag in enumerate(chain, 1):
        where = f"hop {i} of {len(chain)} ({' -> '.join(chain)})"
        if not await helper.wait_for_element(tag, timeout=int(hop_timeout * 1000)):
            return (f"navigation to Screen.{screen}: hop tag {tag!r} never appeared, {where}; "
                    f"on {await helper.get_screen()!r}; on screen and drivable now: "
                    f"{await _on_screen(helper)}")
        if not await helper.click(tag, timeout=int(hop_timeout * 1000)):
            return f"navigation to Screen.{screen}: clicking hop tag {tag!r} failed, {where}"
        landed = await _hop_landed(helper, tag, hop_timeout)
        if landed:
            return f"navigation to Screen.{screen}: {landed}, {where}"
    got = await _settle_on(helper, screen, arrive_timeout)
    if got == "CircleTab" and screen != "CircleTab":
        # A tab with ONE card opens it directly in the wide layout (nav_map
        # drops the row hop), but the compact layout (phones) lists the one
        # card first. Open it when there is exactly one row; otherwise name
        # the rows rather than guess.
        rows = sorted({e.test_tag for e in await helper.get_elements()
                       if e.test_tag.startswith("nav_epistemic_")})
        # The row for THIS screen, when the list has it (Contacts sits in every
        # circle's People tab; a tab can list several cards).
        want = "nav_epistemic_" + re.sub(r"(?<!^)(?=[A-Z])", "_", screen).lower()
        pick = want if want in rows else (rows[0] if len(rows) == 1 else None)
        if pick:
            await helper.click(pick, timeout=int(hop_timeout * 1000))
            got = await _settle_on(helper, screen, arrive_timeout)
        elif rows:
            return (f"navigation to Screen.{screen}: walked {' -> '.join(chain)} and landed on "
                    f"the tab's card list with {len(rows)} rows ({', '.join(rows)}); nav_map "
                    f"expected one card, and none is {want!r} — was the circle hop applied?")
    if got != screen:
        return (f"navigation to Screen.{screen}: walked {' -> '.join(chain)} and landed on "
                f"{got!r}")
    return None


def nav_hops(has_agent: bool) -> tuple[dict, set]:
    """(Screen -> hop, flow-only screens) for this build, from the client source."""
    from testing.gate import nav_map, screen_atlas  # noqa: PLC0415
    return nav_map.build(has_agent=has_agent), screen_atlas.flow_only()


async def run_one(spec: FlowSpec, helper, *, platform=None, artifacts: Optional[Path] = None,
                  client_version: Optional[str] = None, start_timeout: float = 30.0,
                  hops: Optional[dict] = None, flow_only: frozenset | set = frozenset(),
                  variables: Optional[dict] = None, variable_notes: Sequence[str] = ()) -> FlowOutcome:
    """Run one flow. With `hops` (nav_map's Screen -> chain), the runner first
    WALKS to the flow's starting screen; without, it only waits for it."""
    refusal = check_client_floor(spec.client_floor, client_version)
    if refusal:
        print(f"\n FLOW {spec.flow} ({spec.csd_id}) — REFUSED by its floor\n   {refusal}")
        return FlowOutcome(spec.flow, spec.csd_id, REFUSED, refusal)

    start = spec.steps[0].requires.screen
    if start and hops is None:
        await _settle_on(helper, start, start_timeout)
    elif start:
        err = None
        if start in hops:
            # ALWAYS WALKED, even when the client already shows the screen.
            # Contacts sits in every circle's People tab, and the last flow
            # left the shell wherever it left it; being on the screen says
            # nothing about the circle it is shown in. Re-selecting the hop's
            # circle and tab — and verifying each landed — is what makes every
            # flow start from a known place rather than the previous flow's.
            print(f"\n FLOW {spec.flow} — walking to Screen.{start}: {' -> '.join(hops[start])}")
            err = await navigate(helper, start, hops[start],
                                 hop_timeout=start_timeout, arrive_timeout=start_timeout)
        elif start in flow_only:
            # Pre-login, wizards, leaves: nothing in the shell leads there,
            # so the flow must already be on it. Wait, then let `requires` judge.
            await _settle_on(helper, start, start_timeout)
        else:
            # A landing still composing is not a flow on the wrong screen: give
            # the client a moment before deciding it is elsewhere.
            cur = await _settle_on(helper, start, min(start_timeout, 5.0))
            if cur != start:
                err = (f"no nav hop for Screen.{start} on this build, and it is not a "
                       f"flow-only screen (on {cur!r})")
        if err:
            print(f"\n FLOW {spec.flow} ({spec.csd_id}) — CANNOT START\n   {err}")
            return FlowOutcome(spec.flow, spec.csd_id, CANNOT_START, err)

    runner = FlowRunner(helper, platform=platform, artifacts=artifacts,
                        variables=variables, variable_notes=variable_notes)
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
    if runner.cleanup_failures:
        # Said, not judged: the verdict is the flow's; a cleanup that did not
        # run is what the NEXT flow will fail for, so it is on the record here.
        detail += "; cleanup: " + "; ".join(runner.cleanup_failures)
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


def fixture_order(specs: Sequence[FlowSpec]) -> List[FlowSpec]:
    """Flows with no fixture first, in their order; then each fixture's flows.

    A fixture CHANGES the leg's node — two_node leaves it with a contact and a
    room — so a flow asserting the bare node (people.yaml's "no contacts yet")
    must run before any fixture does, and must not depend on file order."""
    plain = [s for s in specs if not s.fixture]
    return plain + [s for s in specs if s.fixture]


def run_all(specs: Sequence[FlowSpec], drv, *, platform=None, artifacts: Optional[Path] = None,
            client_version: Optional[str] = None, username: str = "qaadmin",
            password: str = "QaAdmin!2345", establish_session: bool = True,
            helper: Any = None, navigate_to_start: bool = True,
            fixtures: Optional[Callable[[str], Any]] = None) -> List[FlowOutcome]:
    """Run every flow against one live client. Signs in once, only if some flow
    will actually run.

    `fixtures(name)` builds the fixture a flow's `fixture:` asks for (an object
    with `up() -> {NAME: value}`, `down()` and `values.notes`). It is stood up
    ONCE, just before the first runnable flow that needs it, and torn down in a
    `finally` — so only runs whose flows ask for it pay for it, and a failed
    flow still cleans up. A fixture that cannot be stood up leaves its flows
    `cannot-start`, naming why: red, because they never ran."""
    from testing.gate.flow_helper import SyncFlowHelper  # noqa: PLC0415

    helper = helper or SyncFlowHelper(drv)
    runnable = [s for s in specs if not check_client_floor(s.client_floor, client_version)]
    if runnable and establish_session and drv is not None:
        landed = sign_in(drv, username, password)
        print(f"  session: signed in, on {landed!r}")

    hops, flow_only = None, frozenset()
    if runnable and navigate_to_start:
        # The build decides the tree: a node client has no agentOnly rows, so a
        # hop derived for the agent build would click tags that are not there.
        mode = ""
        if drv is not None:
            try:
                mode = str(drv.state().get("clientMode", ""))
            except Exception:  # noqa: BLE001 — unknown mode: the node tree, the subset
                mode = ""
        hops, flow_only = nav_hops(has_agent=mode.upper() == "AGENT")

    started: dict = {}      # fixture name -> (object, vars, notes) or (None, None, [why])

    def fixture_for(name: str):
        if name not in started:
            if fixtures is None:
                started[name] = (None, None, [
                    f"no `{name}` fixture was provided to this run (run_platform / run_flows "
                    f"need --node-binary to stand one up)"])
            else:
                fx = None
                try:
                    fx = fixtures(name)
                    got = fx.up()
                    notes = list(getattr(getattr(fx, "values", None), "notes", []) or [])
                    started[name] = (fx, got, notes)
                    print(f"\n fixture {name}: up — {sorted(got)}")
                    for n in notes:
                        print(f"   note: {n}")
                except Exception as e:  # noqa: BLE001 — a fixture that cannot stand up is a verdict
                    if fx is not None:
                        try:
                            fx.down()
                        except Exception:  # noqa: BLE001
                            pass
                    started[name] = (None, None, [f"the `{name}` fixture could not be stood up: "
                                                  f"{type(e).__name__}: {e}"])
                    print(f"\n fixture {name}: UNAVAILABLE — {started[name][2][0]}")
        return started[name]

    async def go() -> List[FlowOutcome]:
        out: List[FlowOutcome] = []
        for s in fixture_order(specs):
            variables, notes = None, ()
            if s.fixture and not check_client_floor(s.client_floor, client_version):
                _, variables, notes = fixture_for(s.fixture)
                if variables is None:
                    print(f"\n FLOW {s.flow} ({s.csd_id}) — CANNOT START\n   {notes[0]}")
                    out.append(FlowOutcome(s.flow, s.csd_id, CANNOT_START, notes[0]))
                    continue
            out.append(await run_one(s, helper, platform=platform, artifacts=artifacts,
                                     client_version=client_version, hops=hops,
                                     flow_only=flow_only, variables=variables,
                                     variable_notes=notes))
        return out

    try:
        outcomes = asyncio.run(go())
    finally:
        for name, (fx, _, _) in started.items():
            if fx is not None:
                try:
                    fx.down()
                    print(f" fixture {name}: down")
                except Exception as e:  # noqa: BLE001 — teardown must not hide the verdict
                    print(f" fixture {name}: teardown failed: {e}")
    print(f"\n flows: {summary(outcomes)}")
    for o in outcomes:
        print(f"  [{o.status:^12}] {o.flow} ({o.csd}): {o.detail}")
    return outcomes


def add_fixture_args(ap: argparse.ArgumentParser) -> None:
    """The flags a `fixture: two_node` flow needs. Shared by run_platform."""
    ap.add_argument("--node-binary", type=Path,
                    help="the ciris-server binary the leg downloaded; with it, flows that ask "
                         "for `fixture: two_node` get a second node (testing/gate/two_node.py)")
    ap.add_argument("--node-url", default="http://127.0.0.1:4243",
                    help="the read API of the node the client under test uses, as seen from "
                         "this host (the fixture signs in to it as the client's owner)")
    ap.add_argument("--peer-port", type=int, default=5242,
                    help="the second node's transport port; its read API is the next one")
    ap.add_argument("--peer-work", type=Path,
                    help="where the second node's home and log go (default: a fresh temp dir)")


def fixture_factory(args, leg: str = "") -> Optional[Callable[[str], Any]]:
    """A `fixtures(name)` callable for run_all, or None when no binary was given
    (then a fixture flow is `cannot-start`, saying so)."""
    binary = getattr(args, "node_binary", None)
    if not binary:
        return None

    def build(name: str):
        from testing.gate import two_node  # noqa: PLC0415
        if name != two_node.FIXTURE:
            raise ValueError(f"unknown fixture {name!r}")
        import tempfile  # noqa: PLC0415
        work = args.peer_work or Path(tempfile.mkdtemp(prefix=f"ciris-two-node-{leg or 'leg'}-"))
        return two_node.TwoNodeFixture(binary, work, node_url=args.node_url,
                                       username=args.username, password=args.password,
                                       port=args.peer_port)
    return build


def main(argv: Optional[List[str]] = None) -> int:
    utf8_console()
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
    add_fixture_args(ap)
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
                           password=args.password, establish_session=not args.no_sign_in,
                           fixtures=fixture_factory(args, leg=args.platform))
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
