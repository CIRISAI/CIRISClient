"""Every mobile platform must actually START its automation server (CIRISClient#31).

`CIRISApp` calls `startTestAutomationServer()` on every non-desktop platform once
test mode is armed. That is an `expect fun`, so each target supplies an `actual`
— and Android's was:

    actual fun startTestAutomationServer() {
        // TODO: Android test automation server (Ktor CIO)
        // For now, no-op — Android uses adb + Espresso for UI testing
    }

The comment was out of date by an entire implementation:
`AndroidTestAutomationServer` is a complete Ktor CIO server with routes, a
readiness thread, an idempotent `start()` and a `stop()`. Only the call was
missing, and nothing failed loudly — a no-op `actual` compiles perfectly.

What it cost: the five-platform gate's Android leg installed the APK, armed the
sentinel, forwarded the port, launched the app, and then waited its full 120s for
a server nobody had started. The error it reported —

    GET /health -> Remote end closed connection without response

— is adb accepting on the HOST socket and finding nothing on the device, which
is precisely the confusion `bringup.py`'s invariant 3 exists to name. So the leg
could never pass, and the reason was a TODO rather than anything about the app.

Parsed with `re` rather than compiled, per AGENTS.md: a readiness check that
needs a build has already lost — and this one additionally needs an Android SDK,
which not every developer machine has (mine does not, which is why this file
exists instead of a compile).
"""

from __future__ import annotations

import pathlib
import re

import pytest

SHARED = pathlib.Path(__file__).resolve().parents[1] / "client" / "shared" / "src"

#: The mobile targets whose `actual` must start a server. Desktop is excluded on
#: purpose: it starts its own from `Main.kt` before Compose, which is why
#: `CIRISApp` guards the call with `!isDesktop()`.
PLATFORMS = {
    "androidMain": "AndroidTestAutomationServer",
    "iosMain": "IOSTestAutomationServer",
}


def _actual_body(source_set: str) -> str:
    """The body of `actual fun startTestAutomationServer()` for one target."""
    hits = list((SHARED / source_set).rglob("Platform.*.kt"))
    assert hits, f"no Platform actual file under {source_set}"
    for path in hits:
        text = path.read_text(encoding="utf-8")
        m = re.search(
            r"actual fun startTestAutomationServer\(\)\s*\{(.*?)\n\}",
            text,
            re.S,
        )
        if m:
            return m.group(1)
    pytest.fail(f"{source_set} declares no actual startTestAutomationServer()")


@pytest.mark.parametrize("source_set,server", sorted(PLATFORMS.items()))
def test_the_actual_starts_the_server_it_has(source_set, server):
    body = _actual_body(source_set)
    # COMMENTS STRIPPED FIRST. The stub's body was entirely comments naming the
    # server it did not start, so a substring match over the raw body would have
    # found the name and passed — the same trap that caught three checks today
    # (a version regex matching the comment explaining the version, a CIRIS_HOME
    # guard passing on a mention, and an XCFramework task guard finding the task
    # name in the paragraph about it).
    code = "\n".join(
        ln for ln in body.splitlines()
        if ln.strip() and not ln.strip().startswith("//")
    )
    assert f"{server}.startIfEnabled()" in code, (
        f"{source_set}'s actual does not start {server} — it compiles, and the "
        f"gate then waits its whole budget for a server nobody launched. Body:\n{code!r}"
    )


@pytest.mark.parametrize("source_set", sorted(PLATFORMS))
def test_the_actual_is_not_an_empty_stub(source_set):
    """A no-op `actual` is the failure mode: it satisfies the compiler and nothing else."""
    body = _actual_body(source_set)
    code = [
        ln for ln in body.splitlines()
        if ln.strip() and not ln.strip().startswith("//")
    ]
    assert code, f"{source_set}'s startTestAutomationServer() is a comment-only stub"


# ── The reconfigure hold must ask a LIVENESS endpoint (CIRISClient#52) ────────

CIRISAPP = SHARED / "commonMain" / "kotlin" / "ai" / "ciris" / "mobile" / "shared" / "CIRISApp.kt"


def _is_node_reachable_body() -> str:
    text = CIRISAPP.read_text(encoding="utf-8")
    m = re.search(
        r"private suspend fun isNodeReachable\(nodeBaseUrl: String\): Boolean \{(.*?)\n\}",
        text,
        re.S,
    )
    assert m, "isNodeReachable() not found — the hold's liveness probe moved"
    return "\n".join(
        ln for ln in m.group(1).splitlines()
        if ln.strip() and not ln.strip().startswith("//")
    )


def test_the_hold_probes_liveness_not_identity():
    """`/v1/identity` is a question about node STATE, not about whether it is up.

    Using it to end the post-setup hold trapped macOS for 180 polls while the
    supervisor's /health probe reported the same node healthy throughout.

    This guard exists because the unit test alone did not catch it: the
    EndpointAnsweringTest pins what `isEndpointAnswering` MEANS, and reverting
    `isNodeReachable` to `isLocalNodeUp` left the whole Kotlin suite green. A
    primitive's contract and its use at the one call site that matters are two
    different assertions, and only one of them was being made.
    """
    body = _is_node_reachable_body()
    assert "isEndpointAnswering" in body, (
        f"the hold's liveness probe does not ask a liveness endpoint: {body!r}"
    )
    assert "isLocalNodeUp" not in body, (
        "isLocalNodeUp asks /v1/identity — an identity aggregate, not liveness"
    )


def test_the_hold_takes_its_health_path_from_the_active_endpoint():
    """Not a literal. A node on a custom port and an agent build ask their own
    question, and #52's whole family is probes aimed at the wrong surface."""
    body = _is_node_reachable_body()
    assert "ActiveBackend.endpoint.healthPath" in body, (
        f"the health path is hardcoded rather than resolved: {body!r}"
    )


# ── The CSDs in this repo must validate against the pinned registry ──────────

CSD_DIR = pathlib.Path(__file__).resolve().parents[1] / "FSD" / "CSD"
STANDARD = pathlib.Path(__file__).resolve().parents[1] / "CSD.md"
REGISTRY = pathlib.Path("/tmp/nsreg.json")


@pytest.mark.skipif(not REGISTRY.exists(), reason="registry snapshot not fetched")
@pytest.mark.parametrize(
    "doc", [STANDARD] + sorted(CSD_DIR.glob("CSD-*.md")), ids=lambda p: p.name
)
def test_every_csd_validates(doc):
    """A CSD that does not validate is a document, not a contract.

    The checker's own type vocabulary drifted from the standard's on its first
    run — `list[…]` is declared in §2.1.1 and was missing from the checker, so
    two correct CSDs failed. A checker disagreeing with the standard it enforces
    is the defect class it exists to catch, one level up.
    """
    import subprocess
    got = subprocess.run(
        ["python3", "packaging/check_csd_v3.py", str(doc), "--registry", str(REGISTRY)],
        capture_output=True, text=True,
        cwd=pathlib.Path(__file__).resolve().parents[1],
    )
    assert got.returncode == 0, got.stdout


@pytest.mark.skipif(not REGISTRY.exists(), reason="registry snapshot not fetched")
def test_every_csd_surface_is_reachable():
    """A CSD naming a surface the sidebar cannot reach cannot be tested at all.

    Resolved through the same `nav_map` the runner walks, so the CSD and the
    harness cannot disagree about where a screen lives.
    """
    import re as _re
    from testing.gate import nav_map
    hops = nav_map.build()
    for doc in sorted(CSD_DIR.glob("CSD-*.md")):
        m = _re.search(r"```yaml csd:surface\n(.*?)```", doc.read_text(), _re.S)
        assert m, f"{doc.name}: no csd:surface block"
        screen = _re.search(r"screen:\s*(\w+)", m.group(1)).group(1)
        assert screen in hops, f"{doc.name}: no sidebar route to Screen.{screen}"


# ── A disabled control a flow clicks must be disabled to `/click` too (#69) ───
#
# Windows, run 36600766576 (2026-09-29), csd_068 `fips_and_a_path`: the
# "Confirm your YubiKey…" banner was on screen before anything had been
# submitted. The flow's second step had clicked `btn_provision_holder_submit`
# while it was greyed out, and the click RAN `provision()`: the Button passed
# `enabled = canProvision` but its `testableClickable` did not, so the
# automation handler stayed registered — CIRISClient#69's shape, which
# `bindClickHandler` exists to close and which every call site has to opt into.
#
# Linux and macOS passed the same step by accident: the runner had scrolled
# down to reach the submit, the banner composed above the fold with a clipped,
# zero-size bounds, and the geometry-based `absent:` read it as gone. Windows'
# shorter window scrolled back up for the checkbox and the banner was there.
#
# Pinned for the tags flows CLICK (a flow asserting "a disabled submit does
# nothing" is asserting exactly this), parsed with `re`, per AGENTS.md.

FLOWS_DIR = pathlib.Path(__file__).resolve().parents[1] / "testing" / "flows"
COMMON = SHARED / "commonMain" / "kotlin"
BUTTONS = re.compile(
    r"\b(?:Button|OutlinedButton|TextButton|FilledTonalButton|ElevatedButton|IconButton|FilledIconButton)\("
)


def _flow_click_targets() -> set[str]:
    """Every literal tag a shipped flow clicks (`${...}` tags cannot be grepped),
    including `click_refused:` — the control a flow asserts is disabled is the
    one whose handler most needs to follow `enabled`."""
    tags: set[str] = set()
    for flow in FLOWS_DIR.glob("*.yaml"):
        for tag in re.findall(r"^\s*-\s*click(?:_refused)?:\s*\"?([A-Za-z0-9_]+)\"?\s*$", flow.read_text(encoding="utf-8"), re.M):
            tags.add(tag)
    return tags


def _args_of(src: str, open_paren: int) -> str:
    """The text inside the parentheses that open at `open_paren`."""
    depth = 0
    for i in range(open_paren, len(src)):
        if src[i] == "(":
            depth += 1
        elif src[i] == ")":
            depth -= 1
            if depth == 0:
                return src[open_paren + 1:i]
    return src[open_paren + 1:]


def _top_level(text: str) -> str:
    """`text` with nested braces and parentheses blanked, so a Button's own
    `enabled =` is found and a lambda's or a nested call's is not."""
    out, depth = [], 0
    for ch in text:
        if ch in "({":
            depth += 1
        elif ch in ")}":
            depth -= 1
        elif depth == 0:
            out.append(ch)
    return "".join(out)


def _buttons_that_do_not_disable_their_click_handler() -> dict[str, str]:
    """tag -> file:line for every Button whose `enabled =` is not mirrored into
    its `testableClickable(...)`, restricted to the tags flows click."""
    wanted = _flow_click_targets()
    found: dict[str, str] = {}
    for path in sorted(COMMON.rglob("*.kt")):
        src = path.read_text(encoding="utf-8")
        for m in BUTTONS.finditer(src):
            args = _args_of(src, m.end() - 1)
            if not re.search(r"(?:^|,)\s*enabled\s*=", _top_level(args), re.M):
                continue
            tc = re.search(r"testableClickable\(", args)
            if not tc:
                continue
            tc_args = _args_of(args, tc.end() - 1)
            tag = re.search(r'"([A-Za-z0-9_]+)"', tc_args)
            if not tag or tag.group(1) not in wanted:
                continue
            if re.search(r"\benabled\s*=", _top_level(tc_args)):
                continue
            line = src[:m.start()].count("\n") + 1
            found[tag.group(1)] = f"{path.relative_to(SHARED)}:{line}"
    return found


def test_the_probe_sees_the_flows_click_targets():
    """A parser that finds nothing where the construct plainly exists must fail loudly."""
    tags = _flow_click_targets()
    assert "btn_provision_holder_submit" in tags and "btn_nav_back" in tags, tags


def test_a_button_a_flow_clicks_disables_its_click_handler_with_itself():
    offenders = _buttons_that_do_not_disable_their_click_handler()
    assert not offenders, (
        "a Button's `enabled =` must be passed to its testableClickable too, or "
        "`/click` presses what the person cannot (CIRISClient#69): "
        + ", ".join(f"{t} at {where}" for t, where in sorted(offenders.items()))
    )


# ── A node that ANSWERS is not a node that is OWNED (Android leg) ────────────
#
# Run 36600766576: the Android leg's node was fresh (the desktop leg's seeded
# node had been stopped, as the workflow intends), the client's
# `checkFirstRunStatus` took its NODE-only branch, and that branch returned
# "setup complete" the moment the node answered `/health` — the #48 shortcut,
# written for the run-without-AI hand-off, where the node HAD been claimed.
# Login rendered with isFirstRun=false, "Local login" opened a password form
# for an owner that did not exist, and the session fixture called it "did not
# reach Setup". Desktop never saw it because its ActiveBackend was still the
# agent pin at that point, so it took the /v1/setup/status path and got FRESH.
#
# Pinned at the source: the NODE-only branch must ask the node whether it has
# an owner (`probeNodeOwnership` / `nodeHasOwner`) before it may answer false.

CIRISAPP = SHARED / "commonMain" / "kotlin" / "ai" / "ciris" / "mobile" / "shared" / "CIRISApp.kt"


def _block(src: str, open_brace: int) -> str:
    depth = 0
    for i in range(open_brace, len(src)):
        if src[i] == "{":
            depth += 1
        elif src[i] == "}":
            depth -= 1
            if depth == 0:
                return src[open_brace:i + 1]
    return src[open_brace:]


def _node_only_branch_of_first_run_check() -> str:
    src = CIRISAPP.read_text(encoding="utf-8")
    start = src.index("private suspend fun checkFirstRunStatus(")
    body = _block(src, src.index("{", src.index(")", start)))
    # The function opens with `val nodeUrl = if (ActiveBackend.endpoint == …)`,
    # which is the same test on a different question; the branch wanted is the
    # one that decides on the node answering.
    blocks = [_block(body, m.end() - 1)
              for m in re.finditer(r"if \(ActiveBackend\.endpoint == NODE_ONLY_ENDPOINT\) \{", body)]
    deciding = [b for b in blocks if "isNodeReachable(" in b]
    assert deciding, "checkFirstRunStatus has no NODE-only branch that decides on the node answering (did it move?)"
    return deciding[0]


def test_the_node_only_first_run_check_asks_the_node_whether_it_is_owned():
    branch = _node_only_branch_of_first_run_check()
    asks = re.search(r"probeNodeOwnership\(|nodeHasOwner\(", branch)
    first_false = re.search(r"return false", branch)
    assert asks, "the NODE-only branch never asks whether the node has an owner: a fresh node is called configured"
    assert first_false and asks.start() < first_false.start(), (
        "the NODE-only branch answers 'configured' before asking whether the node has an owner"
    )
    assert re.search(r"NodeOwnership\.FRESH[^\n]*\n[^\n]*\n?[^\n]*return true|FRESH.*?return true", branch, re.S), (
        "a FRESH node must be a first run (return true) on the NODE-only branch"
    )
