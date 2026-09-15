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
    tags = drv.tags()
    if "btn_local_login" in tags:
        drv.click("btn_local_login")
        time.sleep(settle)
    if "input_username" not in drv.tags():
        return "no login form (already signed in?)"
    drv.input("input_username", user)
    drv.input("input_password", password)
    drv.click("btn_login_submit")
    # The submit is a network round trip; the sidebar appears only after it.
    for _ in range(60):
        time.sleep(1.0)
        if "nav_group_manage" in drv.tags() or "btn_nav_drawer_open" in drv.tags():
            return "signed in"
    return "submitted, but no nav surface appeared"


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
            # A GROUP HEADER IS A TOGGLE, NOT A ROUTE. Clicking `nav_group_manage`
            # while MANAGE is already open COLLAPSES it, and the child click that
            # follows lands on nothing — which is why the first verified run said
            # "no navigation: still on Interact" for every screen under an open
            # group, and "never appeared" for the ones whose group it had just
            # shut. A hop is therefore clicked only when what it should reveal is
            # not already reachable.
            for i, tag in enumerate(chain):
                nxt = chain[i + 1] if i + 1 < len(chain) else None
                if nxt and nxt in drv.tags():
                    continue
                drv.wait_for_element(tag, timeout=6.0)
                drv.click(tag)
                time.sleep(settle)
            time.sleep(settle)
            after = drv.screen()
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

        hops = nav_map.build()
        print(f"{len(hops)} screens in the nav tree")
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
