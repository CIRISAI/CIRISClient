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
from testing.gate.console import utf8_console
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
    #: The run's ONE server version (testing/gate/server_version.py): the node
    #: this leg downloaded and talks to, and the two-node peer.
    node_version: str = ""
    #: iOS only: the simulator app embeds CIRISAgent's pinned ciris-server, which
    #: is NOT the run's version. The board says both; it never claims the run's
    #: version for a node the leg did not run (CIRISClient#166).
    embedded_node_version: str = ""
    #: With-AI legs only: the `ciris-agent==` this leg installed (the run's ONE
    #: agent version, testing/gate/agent_version.py). Its node is the agent's
    #: own pinned ciris-server, recorded as `embedded_node_version`.
    agent_version: str = ""
    steps: list[StepResult] = field(default_factory=list)
    #: One entry per CSD flow (run_flows.FlowOutcome), when `--flows` was given.
    flows: list[dict] = field(default_factory=list)

    def add(self, name: str, ok: bool, detail: str = "", shot: str | None = None) -> None:
        self.steps.append(StepResult(name, ok, detail, shot))


def plan_for(args) -> bringup.Plan:
    if args.platform == "android":
        if not args.apk:
            raise bringup.CannotRun("--apk is required for android")
        # The node is on THIS host; the app is not. Carry its claim PIN across
        # (bringup.android_plan) — None when the node is already owned.
        pin = bringup.node_claim_pin(args.node_url)
        return bringup.android_plan(Path(args.apk), args.package, serial=args.serial,
                                    activity=args.activity, claim_pin=pin,
                                    reverse_agent=bool(getattr(args, "with_ai", False)))
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
        return bringup.android_teardown(args.package, serial=args.serial,
                                        reverse_agent=bool(getattr(args, "with_ai", False)))
    if args.platform == "ios":
        return bringup.ios_teardown(args.bundle_id, udid=args.udid)
    return None



def state_problems(mode: str, node_url: str, with_ai: bool = False) -> list[str]:
    """What is wrong with `/state` for a client driven against a BARE node —
    or, on a with-AI leg, against a running agent.

    Split out of [walk] so it is testable without a device — the assertions this
    gate makes are exactly the ones worth having a red path for, and one needing
    an emulator to exercise is one nobody exercises.

    `unset` is deliberately ACCEPTED. The gate probe may not have answered yet
    when the walk runs, and "not probed" is a real state this client defines
    (CIRISClient#48) — reading it as a failure would make the gate flaky and
    would punish the client for being honest.
    """
    problems: list[str] = []
    if with_ai:
        # THE OTHER FACE OF #48. A brain answers :8080 on this leg; a client
        # that settles on NODE has missed it, and every agent flow after this
        # would fail for that reason under its own name. `unset` is accepted
        # for the reason above: not probed yet is not wrong.
        if mode.upper() == "NODE":
            problems.append("clientMode=NODE with an agent running (the probe never found :8080)")
        return problems
    if mode.upper() == "AGENT":
        problems.append("clientMode=AGENT against a bare node (no brain is folded here)")
    if node_url and ":8080" in node_url:
        problems.append(f"pointed at the agent port: {node_url}")
    return problems

def walk(drv: TestAutomationServer, rep: Report, shots: Path, platform,
         args_timeout: float = 120.0, with_ai: bool = False) -> None:
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
    # A SCREEN THAT NAMES ITSELF HAS COMPOSED, tags or not. /state reports
    # screen="unknown" until something is on screen, so a named screen with an
    # empty tree is a screen with nothing to press — Startup, before 0.5.224
    # tagged itself — and not an app that never rendered. The Android leg
    # reported the latter while its own screenshot showed the boot lights.
    screen = drv.screen() or "unknown"
    rep.add(
        "ui-composed",
        composed > 0 or screen != "unknown",
        f"{composed} element(s) on screen {screen!r}" if composed or screen != "unknown" else
        "the app started but never composed a UI — every check below would be vacuous",
    )

    # THE APP HAS TO ARRIVE SOMEWHERE. Startup composing is not the app being
    # usable: the Android leg went green the moment its Startup screen carried
    # a tag, at 0.0s, having asserted nothing about whether the client ever
    # reached the node it was pointed at. Every desktop leg lands on Login in
    # seconds against this same bare node; the Android client now attaches to
    # it instead of booting an embedded runtime, and this is the check that
    # says so. Setup is accepted for a node with no owner yet; Interact /
    # Contacts / ManageNodes for a session that survived a previous pass.
    entry = {"Login", "Setup", "Interact", "Contacts", "ManageNodes"}
    started = time.monotonic()
    landed = ""
    while time.monotonic() - started < args_timeout:
        cur = drv.screen() or ""
        if cur in entry:
            landed = cur
            break
        time.sleep(1.0)
    rep.add(
        "entry-screen",
        bool(landed),
        f"{landed!r} after {time.monotonic() - started:.0f}s" if landed else
        f"still on {drv.screen()!r} after {args_timeout:.0f}s — the app never reached Login/Setup",
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
    if with_ai and mode.upper() != "AGENT":
        # The probe may land a beat after the first screen; give it the time
        # the walk gives the screen, then judge.
        try:
            drv.wait_for_client_mode("AGENT", timeout=args_timeout)
        except DriverError:
            pass
        state = drv.state()
        mode = str(state.get("clientMode", "unset"))
        node_url = str(state.get("nodeUrl", ""))
    problems = state_problems(mode, node_url, with_ai=with_ai)
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
    utf8_console()
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--platform", required=True, choices=("desktop", "android", "ios"))
    ap.add_argument("--apk"); ap.add_argument("--app"); ap.add_argument("--jar")
    ap.add_argument("--package", default="ai.ciris.mobile.debug")
    ap.add_argument("--activity", default=bringup.ANDROID_ACTIVITY,
                    help="fully-qualified activity class; NOT relative to --package")
    ap.add_argument("--bundle-id", default="ai.ciris.mobile")
    ap.add_argument("--serial"); ap.add_argument("--udid", default="booted")
    ap.add_argument("--xvfb", action="store_true", help="wrap desktop in xvfb-run")
    ap.add_argument("--shots", type=Path, default=Path("shots"))
    ap.add_argument("--report", type=Path)
    ap.add_argument("--node-version", default="",
                    help="the run's server version (testing/gate/server_version.py) — the node this "
                         "leg talks to and the peer it stands up")
    ap.add_argument("--embedded-node-version", default="",
                    help="iOS: the ciris-server the simulator app EMBEDS (CIRISAgent's pin), which is "
                         "the node the client actually talks to there; recorded beside --node-version")
    ap.add_argument("--timeout", type=float, default=120.0)
    # WITH AI (the release gate, CIRISClient#166). The leg runs a pip-installed
    # `ciris-agent==<the run's version>` instead of a bare node; the wizard
    # answers "with an AI assistant" and is given a real provider key. The key
    # is read from the environment by NAME — never a command-line value.
    ap.add_argument("--with-ai", action="store_true",
                    help="drive the with-AI wizard against a running agent (see --llm-*)")
    ap.add_argument("--agent-version", default="",
                    help="with-AI: the ciris-agent version this leg installed, for the board")
    ap.add_argument("--llm-provider", default="anthropic")
    ap.add_argument("--llm-model", default="claude-sonnet-5-5",
                    help="the model to pick from the provider's live list; never substituted")
    ap.add_argument("--llm-key-env", default="ANTHROPIC_API_KEY",
                    help="the environment variable that holds the provider key")
    ap.add_argument("--flows", action="append",
                    help="after the smoke walk, run these CSD flows (a file or a directory; "
                         "repeatable) against the same app — see testing/flows/README.md")
    ap.add_argument("--client-version", default=None,
                    help="the version under test, for flows' `client:` floors (default: VERSION)")
    ap.add_argument("--username", default="qaadmin")
    ap.add_argument("--password", default="QaAdmin!2345")
    # `fixture: two_node` flows: the leg's own ciris-server binary, and where
    # the node the client uses answers from this host (testing/gate/two_node.py).
    from testing.gate.run_flows import add_fixture_args
    add_fixture_args(ap)
    args = ap.parse_args()

    rep = Report(platform=args.platform, node_version=args.node_version,
                 embedded_node_version=args.embedded_node_version,
                 agent_version=args.agent_version if args.with_ai else "")
    llm = None
    if args.with_ai:
        from testing.gate.session_fixture import LlmChoice, SessionUnavailable
        try:
            llm = LlmChoice.from_env(args.llm_provider, args.llm_model, args.llm_key_env)
        except SessionUnavailable as e:
            rep.add("with-ai", False, str(e))
            return _finish(args, rep)
        rep.add("with-ai", True, f"provider {llm.provider}, model {llm.model}, key from ${args.llm_key_env}")
    args.llm = llm
    args.shots.mkdir(parents=True, exist_ok=True)

    from testing.gate.platforms import build_platform
    platform = build_platform(args)

    # FLOWS LOAD BEFORE ANYTHING BOOTS. A flow that does not parse, or names a
    # CSD that does not, is found in a second — not after the emulator.
    specs = None
    if args.flows:
        from testing.gate import run_flows
        from testing.gate.flow_spec import SpecError
        try:
            specs = run_flows.load_flows(args.flows)
        except SpecError as e:
            rep.add("flows-load", False, str(e))
            return _finish(args, rep)

    plan = None
    try:
        plan = plan_for(args)
        rep.artifact = str(args.apk or args.app or args.jar or "")
        bringup.run(plan)
        rep.add("bring-up", True, " -> ".join(plan.names()))

        drv = TestAutomationServer(base_url=plan.test_url)
        # PROVEN, NOT ASSUMED.
        drv.wait_for_server(timeout=args.timeout)
        walk(drv, rep, args.shots, platform, args_timeout=args.timeout, with_ai=args.with_ai)
        if specs is not None:
            flows(drv, rep, specs, args, platform)
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
    return _finish(args, rep)


def flows(drv: TestAutomationServer, rep: Report, specs, args, platform) -> None:
    """The CSD flows, in the SAME app the walk just proved — no second bring-up.

    Only after a clean walk: the walk is what proves there is a composed app on
    a real node to drive, and flows run on anything less would fail for the
    walk's reason under their own names.
    """
    from testing.gate import run_flows
    from testing.gate.session_fixture import SessionUnavailable

    if not all(s.ok for s in rep.steps):
        rep.add("flows", False, "not run — the smoke walk above failed, so there is no app to drive")
        return
    # Per LEG, not per platform: the linux desktop and the android emulator
    # share a runner and a `shots/` directory, and must not overwrite each other.
    leg = args.report.stem if args.report else args.platform
    version = args.client_version if args.client_version is not None else run_flows.default_client_version()
    try:
        outcomes = run_flows.run_all(specs, drv, platform=platform,
                                     artifacts=args.shots / f"flows-{leg}",
                                     client_version=version, username=args.username,
                                     password=args.password,
                                     fixtures=run_flows.fixture_factory(args, leg=leg),
                                     llm=getattr(args, "llm", None))
    except SessionUnavailable as e:
        llm = getattr(args, "llm", None)
        why = llm.scrub(str(e)) if llm is not None else str(e)
        rep.add("flows", False, f"no session to run them in: {why}")
        return
    rep.flows = [asdict(o) for o in outcomes]
    rep.add("flows", run_flows.leg_ok(outcomes), run_flows.summary(outcomes))
    # THE QUIET PASS, JUDGED HERE, beside the verdict it hides in. An optional
    # step skipped is a step that did not run; the count per flow is compared
    # with testing/flows/optional_skips_baseline.json, and a rise OR a fall
    # reddens the leg (CIRISClient#168 — testing/gate/optional_skips.py).
    from testing.gate import optional_skips
    ok, detail = optional_skips.verdict(leg, rep.flows)
    rep.add("optional-skips", ok, detail)


def _finish(args, rep: Report) -> int:
    rep.ok = rep.ok and all(s.ok for s in rep.steps)
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
    print(board_line(args.report.stem if args.report else args.platform, rep))
    return 0 if rep.ok else 1


def board_line(leg: str, rep: Report) -> str:
    """`linux: PASS · server v0.5.231 · optional skips 10` — the one line a
    reader scans five of. The server version is the run's (one per run,
    CIRISClient#166); the skip count is what "0 FAIL" was hiding (#168)."""
    from testing.gate import optional_skips  # noqa: PLC0415
    parts = [f"{leg}: {'PASS' if rep.ok else 'FAIL'}",
             f"server {rep.node_version or 'unknown'}"]
    if rep.agent_version:
        # With AI: the brain is the run's ONE agent, and the node the client
        # talks to is the one that agent pins and folds in — not the run's
        # server, which is this leg's two-node peer.
        parts.append(f"agent ciris-agent {rep.agent_version} (with AI)")
        if rep.embedded_node_version:
            parts.append(f"agent's node {rep.embedded_node_version} (its pin; the run's server is the peer)")
    elif rep.embedded_node_version:
        parts.append(f"embedded node {rep.embedded_node_version} (the Agent's pin; the client "
                     f"talks to this one, the run's server is its peer)")
    now, _ = optional_skips.counts_for(rep.flows)
    parts.append(f"optional skips {optional_skips.describe(now)}")
    return " · ".join(parts)


if __name__ == "__main__":
    sys.exit(main())
