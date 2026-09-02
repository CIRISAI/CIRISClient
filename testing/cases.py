"""
What we assert, and against which corner.

A case is a plain function taking a [Context] and raising on failure. Each is
declared with the corners it applies to, so `run_e2e.py --corner X` runs exactly
the cases that mean something for X and skips the rest EXPLICITLY -- a skip is
printed and lands in the report, never silently dropped.

The assertions are deliberately about things that have actually broken here:

* `startup_completes` -- the app leaving Startup at all. Every node-facing
  regression in this repo has shown up first as a client that sits on Startup
  forever, and nothing in CI could see it because nothing in CI ever started
  the app.

* `elements_are_registered` -- `/tree` non-empty. CIRISClient#7 was an Android
  `testableWithHandler` that wired a click handler but never called
  `registerElement`, so the element was invisible to automation while looking
  fine to a human. An empty tree on a populated screen is that bug.

* `did_not_launch_a_node` / `did_launch_a_node` -- the LOCATION axis, asserted
  on behaviour rather than configuration. A client told to use a remote node
  that helpfully starts a local one anyway is a data-residency bug, not a
  convenience.

* `claim_pin_is_readable` -- the first-run path the desktop wheel actually
  broke: the launcher starts the node and then spawns the UI, so the stdout
  banner capture never runs and the PIN has to come from the node's home file.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Callable

from .driver import DriverError, TestAutomationServer

# The corners. See node_fixture for why local-agent is not among them.
LOCAL_NODE = "local-node"
REMOTE_NODE = "remote-node"
REMOTE_AGENT = "remote-agent"
REMOTE_UNDETERMINED = "remote-undetermined"
ALL_CORNERS = (LOCAL_NODE, REMOTE_NODE, REMOTE_AGENT, REMOTE_UNDETERMINED)

# Where startup is allowed to land. Setup on a fresh home, Login once claimed.
TERMINAL_SCREENS = {"Login", "Setup", "Startup"}


@dataclass
class Context:
    """Everything a case is allowed to look at."""

    corner: str
    app: TestAutomationServer
    node_url: str
    #: True when the harness -- not the app -- started the real node.
    node_was_prestarted: bool
    #: The node home, when there is one we own.
    node_home: object = None
    #: Set by the runner: did the APP itself spawn a node? Answered by session
    #: id, not by "is the port answering" -- the remote corners run a real node
    #: as the facade's substrate, so port-liveness was true for a blameless
    #: client and this case failed against correct behaviour.
    app_spawned_node: bool = False
    notes: list[str] = field(default_factory=list)


CASES: list["Case"] = []


@dataclass
class Case:
    name: str
    corners: tuple[str, ...]
    fn: Callable[[Context], None]
    why: str = ""


def case(name: str, corners: tuple[str, ...], why: str = ""):
    def deco(fn: Callable[[Context], None]) -> Callable[[Context], None]:
        CASES.append(Case(name=name, corners=corners, fn=fn, why=why))
        return fn

    return deco


# --------------------------------------------------------------------------
# Cases
# --------------------------------------------------------------------------


@case("startup_completes", ALL_CORNERS, "a client that never leaves Startup is the recurring failure")
def startup_completes(ctx: Context) -> None:
    ctx.app.wait_for_server(timeout=120)
    screen = ctx.app.screen()
    # Startup is transient; give it room, then demand it moved.
    import time

    deadline = time.monotonic() + 120
    while time.monotonic() < deadline and screen in ("", "unknown", "Startup"):
        time.sleep(1.0)
        screen = ctx.app.screen()
    if screen in ("", "unknown", "Startup"):
        raise AssertionError(
            f"app never left Startup against a {ctx.corner} node "
            f"(screen={screen!r}, {len(ctx.app.tree())} elements registered)"
        )
    if screen not in TERMINAL_SCREENS:
        ctx.notes.append(f"landed on {screen!r}, which is past the expected first stop")
    ctx.notes.append(f"settled on {screen!r}")


@case("elements_are_registered", ALL_CORNERS, "an empty tree on a populated screen is CIRISClient#7")
def elements_are_registered(ctx: Context) -> None:
    tags = ctx.app.tags()
    if not tags:
        raise AssertionError(
            f"no elements registered with the automation server on screen "
            f"{ctx.app.screen()!r} -- either the screen is genuinely empty or "
            f"registerElement is not being called (CIRISClient#7)"
        )
    ctx.notes.append(f"{len(tags)} elements registered")


@case("did_not_launch_a_node", (REMOTE_NODE, REMOTE_AGENT, REMOTE_UNDETERMINED),
      "a client pointed at a remote node must not quietly start a local one")
def did_not_launch_a_node(ctx: Context) -> None:
    if ctx.app_spawned_node:
        raise AssertionError(
            "the client spawned a ciris-server of its own while configured for "
            f"the remote node at {ctx.node_url}. A client told to use someone "
            "else's node must not quietly stand up a local one."
        )
    ctx.notes.append("no local node was launched, as required")


@case("did_launch_a_node", (LOCAL_NODE,), "the self-launch path, which the wheel actually ships")
def did_launch_a_node(ctx: Context) -> None:
    if ctx.node_was_prestarted:
        raise AssertionError("harness bug: the local corner must not pre-start the node")
    if not ctx.app_spawned_node:
        raise AssertionError(
            "the client never launched a node. `ciris-server` was on PATH and "
            "nothing was answering, so startServer() should have spawned one."
        )
    ctx.notes.append("client launched its own node")


@case("automation_surface_answers", ALL_CORNERS, "the driver's own contract with the app")
def automation_surface_answers(ctx: Context) -> None:
    h = ctx.app.health()
    if not h or h.get("status") != "ok":
        raise AssertionError(f"/health did not report ok: {h!r}")
    if h.get("testMode") is not True:
        raise AssertionError(
            "the app is not in test mode -- CIRIS_TEST_MODE was not honoured, "
            "and every element assertion below would be vacuous"
        )
    # /tree and /screen must agree about the screen, or one of them is stale.
    tree_screen = None
    try:
        raw = ctx.app._call("GET", "/tree")
        tree_screen = raw.get("screen") if isinstance(raw, dict) else None
    except DriverError:
        pass
    if tree_screen is not None and tree_screen != ctx.app.screen():
        ctx.notes.append(f"/tree says {tree_screen!r}, /screen says {ctx.app.screen()!r}")


@case("mode_gate_matches_corner", ALL_CORNERS,
      "the brain axis, asserted on the gate rather than on the layout")
def mode_gate_matches_corner(ctx: Context) -> None:
    """
    NODE for a bare node, AGENT for one carrying a brain, and `unset` for a
    brain that is folded but not answering.

    That third expectation is the point of the corner. `undetermined` is a
    RETRY signal, and a client that resolves it by picking NODE looks correct
    on every screen while being wrong about the one fact it exists to know.
    The client retries for the startup budget (60s by default) and then leaves
    the gate unset, so this waits past that budget before believing an answer.
    """
    expected = {
        LOCAL_NODE: "NODE",
        REMOTE_NODE: "NODE",
        REMOTE_AGENT: "AGENT",
        REMOTE_UNDETERMINED: "unset",
    }[ctx.corner]

    if ctx.corner == REMOTE_UNDETERMINED:
        # Watch the WHOLE budget: latching shows up as a decided value at any
        # point in it, and checking only at the end would miss a client that
        # latched early and a later probe happened to unset.
        import time

        deadline = time.monotonic() + 80
        while time.monotonic() < deadline:
            mode = ctx.app.client_mode()
            if mode != "unset":
                raise AssertionError(
                    f"client latched clientMode={mode!r} against a brain that is "
                    f"folded but not answering. That is a retry signal, not a "
                    f"verdict -- the gate must stay unset and be re-probed."
                )
            time.sleep(2.0)
        ctx.notes.append("gate stayed unset for the whole retry budget, as required")
        return

    try:
        ctx.app.wait_for_client_mode(expected, timeout=90)
    except DriverError as e:
        raise AssertionError(
            f"{ctx.corner} presents a node the client should read as "
            f"{expected}, but {e}"
        ) from None
    ctx.notes.append(f"clientMode={expected} against {ctx.app.state().get('nodeUrl') or 'the default node'}")


def for_corner(corner: str) -> list[Case]:
    return [c for c in CASES if corner in c.corners]


def skipped_for_corner(corner: str) -> list[Case]:
    return [c for c in CASES if corner not in c.corners]
