"""One screenshot per reachable screen, for people who redesign the UI.

WHY THIS IS NOT `run_platform`. The gate walks a handful of screens to prove the
client is drivable and honest; it ends on whatever surface the walk finished on
and takes ONE picture. A designer needs the opposite: every surface, each named,
each with the click-path that reached it, in a mode where the agent surfaces
actually exist. So this reuses the gate's driver and `nav_map`'s derived
hierarchy and does the breadth the gate deliberately does not.

AGENT MODE IS THE POINT. `ClientMode.NODE` hides Interact, Tools, Memory and the
agent settings — a third of the product. The backend here must therefore be a
configured agent (`cognitive_state` set, services > 0, setup complete), not the
bare `ciris-server` the nightly uses. Point `--api` at one; `--expect-mode`
fails loudly rather than quietly shooting the smaller product.

The output is a directory of PNGs plus `atlas.json`, which carries each screen's
nav chain so a viewer can rebuild the tree without re-deriving it.
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from testing.driver import TestAutomationServer  # noqa: E402
from testing.gate import nav_map  # noqa: E402

#: Where the app's automation server listens. Same port the gate drives.
TEST_PORT = 9091

#: Surfaces the nav tree does not reach: pre-login, wizards, and screens that
#: only exist inside a flow. Captured separately or not at all — listed here so
#: the manifest can say "known unreachable" instead of "failed".
FLOW_ONLY = {
    "Startup", "Login", "Setup", "ServerConnection", "ClaimNode",
    "VerifyAgent", "AddFederationId", "DutyConferral", "Help",
    "SkillImport", "Manage",
    # Declared in EpistemicNav but in NO group, and its own doc comment says
    # "Reachable from the Accord screen ONLY when no accord family exists yet".
    # nav_map still hands it a one-hop chain, so it read as a screen the atlas
    # kept failing to reach. It is not a gap; there is no rail route to it.
    "AccordCeremony",
}


def launch(jar: Path, api: str, log: Path, xvfb: bool = True) -> subprocess.Popen:
    """The app, in test mode, pointed at a real agent."""
    env = dict(os.environ)
    env["CIRIS_TEST_MODE"] = "true"
    # CIRIS_NODE_URL IS THE ONLY NAME THAT REACHES THE CLIENT. Main.kt resolves
    # `CIRIS_NODE_URL ?: DEFAULT_LOCAL_NODE_URL` and says so in a comment: after
    # CIRISClient#48/#52, CIRIS_API_URL "no longer reaches this value at all".
    # Setting only CIRIS_API_URL leaves the app on :4243 — the log then reads
    #
    #     CIRIS_API_URL env: http://127.0.0.1:8000
    #     CIRISApiClient initialized with baseUrl=http://127.0.0.1:4243
    #
    # which is the whole bug in two lines. Both are set: the node URL is what
    # the client obeys, and the agent URL keeps anything reading it honest.
    env["CIRIS_NODE_URL"] = api
    env["CIRIS_API_URL"] = api
    cmd = ["java", "-jar", str(jar)]
    if xvfb:
        cmd = ["xvfb-run", "-a"] + cmd
    handle = log.open("wb")
    return subprocess.Popen(cmd, env=env, stdout=handle, stderr=subprocess.STDOUT)


def sign_in(drv: TestAutomationServer, user: str, password: str, settle: float) -> str:
    """Get past the login wall, because the nav tree lives behind it.

    The first atlas run photographed nothing at all: every nav tag timed out,
    twenty seconds each, because the app was sitting on `Screen.Login` the whole
    time and the sidebar does not exist there. A capture tool that cannot log in
    can only ever photograph the login screen.

    `btn_local_login` reveals the username/password form on a desktop build that
    also offers OAuth; on a build that shows the form outright the button is
    absent and the fields are already there, so its absence is not an error.
    """
    # The login form composes a beat after the mode settles; reading the tree
    # once, at that instant, saw an empty screen and reported "already signed
    # in" for a client that was sitting on Login. Wait for something to appear.
    tags: set[str] = set()
    for _ in range(60):
        tags = drv.tags()
        if tags & {"btn_local_login", "input_username", "btn_my_things", "circle_local_community"}:
            break
        time.sleep(1.0)
    if "btn_my_things" in tags or "circle_local_community" in tags:
        return "already signed in"
    if "btn_local_login" in tags:
        drv.click("btn_local_login")
        time.sleep(settle)
    if "input_username" not in drv.tags():
        return "no login form and no shell — the app is on " + (drv.screen() or "an unknown screen")
    drv.input("input_username", user)
    drv.input("input_password", password)
    drv.click("btn_login_submit")
    # The submit is a network round trip; the sidebar appears only after it.
    for _ in range(60):
        time.sleep(1.0)
        if "btn_my_things" in drv.tags() or "circle_local_community" in drv.tags():
            return "signed in"
    return "submitted, but no nav surface appeared"


#: The shell rail's scroll container, named so `/scroll` moves the rail and not
#: whatever the current screen happens to scroll. Mirrors CirclesNav.RAIL_SCROLLABLE.
NAV_RAIL = "shell_rail"


def on_screen(drv: TestAutomationServer, tag: str) -> bool:
    """Has this tag real pixels?

    SIZE, NOT PRESENCE. A row below the rail's fold is composed and in the
    element map with a zero-size rect, so `tags()` reports it and a click on it
    is refused. Asking for width and height is the only question whose answer
    means "you may click this now".
    """
    el = drv.element(tag)
    return bool(el and el.width > 0 and el.height > 0)


def reach(drv: TestAutomationServer, tag: str, settle: float, tries: int = 16) -> None:
    """Bring `tag` into the rail and click it.

    Rewinds the rail to the top before scanning down, because the rail keeps
    its position between screens and is usually left at the bottom: a target
    ABOVE the viewport is never found by scrolling further down.
    """
    from testing.driver import DriverError

    def nudge(direction: str, amount: int) -> bool:
        # "already at the bottom" comes back as a 404. That is an answer, not a
        # failure: stop going that way.
        try:
            drv.scroll_to(tag, direction, amount, container=NAV_RAIL)
            return True
        except DriverError:
            return False

    if on_screen(drv, tag):
        drv.click(tag)
        return
    for _ in range(12):
        if not nudge("up", 400):
            break
    for _ in range(tries):
        if on_screen(drv, tag):
            drv.click(tag)
            return
        if not nudge("down", 200):
            break
        time.sleep(settle / 3)
    drv.click(tag)  # let the app refuse it, so the reason reaches the report


def open_hop(drv: TestAutomationServer, hop: str, child: str, settle: float) -> bool:
    """Open `hop` until `child` exists, and correct the toggle if it shut it.

    Inferring a group's state from whether its children are in the tree is not
    enough. The check can read the tree mid-recomposition, conclude the group is
    shut, click it — and close a group that was open, after which the child
    never composes and the screen is reported unreachable. That is most of what
    the atlas was missing: `nav_epistemic_safety` "never appeared" on the screen
    immediately after a capture that had just used it.

    So this does not predict the toggle's state; it acts and then checks, and a
    hop that made things worse is simply clicked again.
    """
    for _ in range(3):
        # A circle or a tab is ALWAYS clicked: the seven tabs are on screen in
        # every circle, so "the child already exists" says nothing about which
        # circle is selected — the first shell atlas photographed other
        # circles' tabs for that reason. My things is a sheet, so it is opened
        # only when its instruments are not already showing.
        if not hop.startswith(("circle_", "tab_")) and child in drv.tags():
            return True
        control = hop
        try:
            drv.wait_for_element(control, timeout=6.0)
            reach(drv, control, settle)
        except Exception:  # noqa: BLE001
            return False
        for _ in range(8):
            time.sleep(settle / 2)
            if child in drv.tags():
                return True
    return child in drv.tags()


def capture(drv: TestAutomationServer, shots: Path, hops: dict[str, list[str]],
            settle: float) -> list[dict]:
    """Walk to every screen and photograph it.

    Each screen is approached from scratch rather than from wherever the last
    one left us: a chain starts at a group in the sidebar, and a screen that
    covered the sidebar would otherwise poison every screen after it.
    """
    # WALK THE SIDEBAR IN ITS OWN ORDER. Alphabetical order jumps between
    # groups on every screen, so each hop re-toggles a group and re-scrolls the
    # rail, and a tag that is not composed cannot be clicked: an alphabetical
    # pass captured 28 of 54. Grouping by chain keeps the rail still — every
    # screen under one group is visited while that group is open.
    results: list[dict] = []
    ordered = sorted(hops.items(), key=lambda kv: (kv[1][:-1], kv[0]))
    for screen, chain in ordered:
        if screen in FLOW_ONLY:
            results.append({"screen": screen, "chain": chain, "ok": False,
                            "shot": None, "tags": 0, "resolved": "",
                            "detail": "no nav route — reached inside a flow",
                            "flow_only": True})
            print(f"  [FLOW] {screen:28s} no nav route by design")
            continue
        entry = {"screen": screen, "chain": chain, "ok": False,
                 "shot": None, "detail": "", "tags": 0, "resolved": ""}
        try:
            # A PICTURE IS NOT A NAVIGATION. The first run of this tool wrote
            # `Accord.png` showing the Interact screen: the sidebar click never
            # landed (the target sits below the fold and this driver has no
            # /scroll), and the screenshot was taken anyway. Fifty-six files all
            # showing the home screen, each labelled as a different surface, is
            # worse than no atlas — a designer would redesign from it.
            #
            # So the screen the app REPORTS is what decides `ok`. If it did not
            # move, this says so and keeps no picture.
            before = drv.screen()
            # A hop is clicked only when what it should reveal is not already
            # reachable: the circle and the tab are idempotent, but My things is
            # a sheet and clicking it twice would close it.
            for i, tag in enumerate(chain):
                nxt = chain[i + 1] if i + 1 < len(chain) else None
                if nxt is None:
                    drv.wait_for_element(tag, timeout=6.0)
                    reach(drv, tag, settle)
                    time.sleep(settle)
                elif not open_hop(drv, tag, nxt, settle):
                    raise RuntimeError(f"{tag} would not reveal {nxt}")
            time.sleep(settle)
            after = drv.screen()
            # THE RAIL MOVES UNDER THE CLICK. Opening a subtree re-lays the
            # sidebar, and a click resolved against the old layout lands on the
            # neighbouring row — "landed on ManageNodes, not Contacts". Once the
            # rail has settled the same click goes to the right place, so a
            # single honest retry beats widening every timeout.
            if after.lower() != screen.lower():
                time.sleep(settle * 2)
                try:
                    reach(drv, chain[-1], settle)
                    time.sleep(settle)
                    after = drv.screen()
                except Exception:  # noqa: BLE001
                    pass
            entry["resolved"] = after
            if after.lower() != screen.lower():
                entry["detail"] = f"landed on {after}, not {screen}"
            else:
                shot = shots / f"{screen}.png"
                drv.screenshot(str(shot.resolve()))
                entry.update(ok=shot.exists(), shot=shot.name,
                             tags=len(drv.tags()), detail=after)
        except Exception as exc:  # noqa: BLE001
            entry["detail"] = f"{type(exc).__name__}: {exc}"[:160]
        results.append(entry)
        print(f"  [{'OK ' if entry['ok'] else 'MISS'}] {screen:28s} {entry['detail'][:70]}")
    return results


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--jar", required=True, type=Path)
    ap.add_argument("--api", default="http://127.0.0.1:8000",
                    help="a CONFIGURED agent, not a bare node")
    ap.add_argument("--out", default=Path("atlas"), type=Path)
    ap.add_argument("--expect-mode", default="AGENT")
    ap.add_argument("--settle", type=float, default=1.2,
                    help="seconds to let a surface finish composing")
    ap.add_argument("--no-xvfb", action="store_true")
    ap.add_argument("--user", default="", help="local account to sign in as")
    ap.add_argument("--password", default="")
    args = ap.parse_args()

    shots = args.out / "shots"
    shots.mkdir(parents=True, exist_ok=True)
    proc = launch(args.jar, args.api, args.out / "app.log", xvfb=not args.no_xvfb)

    try:
        drv = TestAutomationServer(f"http://127.0.0.1:{TEST_PORT}")
        drv.wait_for_server(timeout=180.0)
        drv.wait_for_ui(timeout=180.0)
        # THE MODE IS PROBED, NOT KNOWN AT STARTUP. Asking the instant the UI
        # composes returns "unset" — the client has not yet heard back from the
        # backend — so wait for the answer rather than reading the silence as
        # a verdict.
        if args.expect_mode:
            try:
                drv.wait_for_client_mode(args.expect_mode, timeout=180.0)
            except Exception as exc:  # noqa: BLE001
                print(f"mode never settled: {exc}", file=sys.stderr)
        mode = drv.client_mode()
        print(f"client mode: {mode}")
        if args.expect_mode and mode != args.expect_mode:
            print(f"REFUSING: wanted {args.expect_mode}, got {mode}. The agent "
                  f"surfaces are absent in {mode} and the atlas would be a "
                  f"picture of the smaller product.", file=sys.stderr)
            return 2

        if args.user:
            print(f"login: {sign_in(drv, args.user, args.password, args.settle)}")

        # The node build is a subset: an agent-only surface is not a miss on a
        # node capture, it is absent by design.
        hops = nav_map.build(has_agent=(mode == "AGENT"))
        print(f"{len(hops)} screens in the nav tree ({mode})")
        results = capture(drv, shots, hops, args.settle)

        manifest = {
            "captured_at": time.strftime("%Y-%m-%d %H:%M UTC", time.gmtime()),
            "mode": mode,
            "api": args.api,
            "jar": args.jar.name,
            "captured": sum(1 for r in results if r["ok"]),
            "total": len(results),
            "flow_only": sorted(FLOW_ONLY),
            "screens": results,
        }
        (args.out / "atlas.json").write_text(json.dumps(manifest, indent=2))
        print(f"\n{manifest['captured']}/{manifest['total']} captured -> {args.out}")
        return 0 if manifest["captured"] else 1
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=20)
        except subprocess.TimeoutExpired:
            proc.kill()


if __name__ == "__main__":
    raise SystemExit(main())
