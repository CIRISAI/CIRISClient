"""Where a flow's first screen lives in the shell, derived from the client.

    python3 -m testing.gate.nav_map                 # print the map
    python3 -m testing.gate.nav_map --screen HealthReputation

THE FLOW NEVER ENCODES THE HOP (FSD/CSD_STANDARD.md §5). Before a flow runs, the
runner walks to its first step's `requires: screen:` through the shell; the
flow asserts arrival and nothing else. That is why a re-home is one fix in the
runner instead of one per flow — and it only works if the runner can answer
"where does Screen X live" without anybody maintaining a table by hand.

DERIVED, NOT TRANSCRIBED. Everything here comes out of the client's own sources:

    CirclesNav.kt        Placement(NavSurface.X, Tab.Y, <circles>, agentOnly)
                         Instrument("<id>", …, listOf(NavSurface.A, …))
                         circleTag / Tab.tag / Instrument.tag / navTag — the tag rules
    EpistemicNav.kt      object <Name> : NavSurface(id = "<surface-id>", …)
    CIRISApp.kt          NavSurface.<Name> -> Screen.<Screen>

THE SHELL (locked spec, wave 1). Five circles, seven tabs, six instruments —
ONE tree. A surface in a tab is reached by its circle, then its tab, then its
row; a surface under an instrument by My things, the instrument, then its row.
A tab holding exactly ONE card shows that card directly, so its chain ends on
the tab — there is no row to click, and inventing one would be a ghost.

A hand-written map would be a second source for a question the client already
answers, and it would drift the first time a surface moved — silently, because
a wrong hop looks exactly like a screen that failed to compose. Parsed with `re`
per AGENTS.md: a check that requires a build has already lost.
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

CLIENT = Path(__file__).resolve().parents[2] / "client" / "shared" / "src" / "commonMain" / "kotlin" / "ai" / "ciris" / "mobile" / "shared"
NAV = CLIENT / "ui" / "nav" / "EpistemicNav.kt"
TREE = CLIENT / "ui" / "nav" / "CirclesNav.kt"
APP = CLIENT / "CIRISApp.kt"

#: The five circles, in rail order, by CohortScope id (ui/nav/CohortScope.kt).
CIRCLES = ["agent", "family", "local-community", "global-communities", "global-commons"]
#: The named circle sets CirclesNav.kt uses, mirrored so a placement resolves.
CIRCLE_SETS = {
    "ALL": CIRCLES,
    "FAMILY_OUT": CIRCLES[1:],
    "NEIGHBOURS_OUT": CIRCLES[2:],
}
SCOPE_NAMES = {
    "AGENT": "agent", "FAMILY": "family", "LOCAL_COMMUNITY": "local-community",
    "GLOBAL_COMMUNITIES": "global-communities", "GLOBAL_COMMONS": "global-commons",
}
MY_THINGS = "btn_my_things"


def slug(nav_id: str) -> str:
    """`CirclesNav.slug` — the one rule, mirrored: `-` becomes `_`."""
    return nav_id.replace("-", "_")


def nav_tag(surface_id: str) -> str:
    """`CirclesNav.navTag` — a surface's row, wherever it is listed."""
    return "nav_epistemic_" + slug(surface_id)


def circle_tag(scope_id: str) -> str:
    return "circle_" + slug(scope_id)


def tab_tag(tab_id: str) -> str:
    return "tab_" + tab_id


def instrument_tag(instrument_id: str) -> str:
    return "nav_instrument_" + slug(instrument_id)


def _declarations(nav_src: str):
    r"""Yield (surface name, argument text) for every `object X : NavSurface(...)`, paren-balanced."""
    for m in re.finditer(r"object\s+(\w+)\s*:\s*NavSurface\(", nav_src):
        name = m.group(1)
        i, depth = m.end(), 1
        while i < len(nav_src) and depth:
            if nav_src[i] == "(":
                depth += 1
            elif nav_src[i] == ")":
                depth -= 1
            i += 1
        yield name, nav_src[m.end():i - 1]


def _surface_ids(nav_src: str) -> dict[str, str]:
    """`object Delegations : NavSurface(...)` -> its surface id, both call forms."""
    out: dict[str, str] = {}
    for name, body in _declarations(nav_src):
        named = re.search(r'id\s*=\s*"([a-z0-9-]+)"', body)
        if named:
            out[name] = named.group(1)
            continue
        positional = re.match(r'\s*"([a-z0-9-]+)"', body)
        if positional:
            out[name] = positional.group(1)
    return out


def _placements(tree_src: str) -> list[dict]:
    """Every `Placement(NavSurface.X, Tab.Y, <circles>, agentOnly = …)` in CirclesNav.kt."""
    out: list[dict] = []
    for m in re.finditer(r"Placement\(\s*NavSurface\.(\w+)\s*,\s*Tab\.(\w+)\s*,\s*(.*?)\)\s*,?\s*\n", tree_src):
        surface, tab, rest = m.group(1), m.group(2).lower(), m.group(3)
        agent_only = "agentOnly = true" in rest
        circles_txt = rest.split(", agentOnly")[0].strip()
        if circles_txt in CIRCLE_SETS:
            circles = list(CIRCLE_SETS[circles_txt])
        else:
            names = re.findall(r"\b([A-Z_]+)\b", circles_txt)
            circles = [SCOPE_NAMES[n] for n in names if n in SCOPE_NAMES]
        if not circles:
            raise ValueError(f"placement of {surface}: could not read its circles from {circles_txt!r}")
        out.append({"surface": surface, "tab": tab, "circles": circles, "agent_only": agent_only})
    if not out:
        raise ValueError("no Placement(...) parsed from CirclesNav.kt — the parser is wrong, not the tree")
    return out


def _instruments(tree_src: str) -> list[dict]:
    """Every `Instrument("id", …, listOf(NavSurface.A, …), agentOnly = setOf(…))`."""
    out: list[dict] = []
    for m in re.finditer(r'Instrument\(\s*"([a-z-]+)"', tree_src):
        i, depth = m.end(), 1
        while i < len(tree_src) and depth:
            depth += (tree_src[i] == "(") - (tree_src[i] == ")")
            i += 1
        body = tree_src[m.end():i - 1]
        lists = re.findall(r"listOf\((.*?)\)", body, re.S)
        surfaces = re.findall(r"NavSurface\.(\w+)", lists[0]) if lists else []
        agent_only = re.findall(r"NavSurface\.(\w+)", (re.search(r"agentOnly\s*=\s*setOf\((.*?)\)", body, re.S) or [None, ""])[1]) \
            if "agentOnly" in body else []
        # `requiresAgent = true` — the whole instrument goes with the agent (This agent).
        if re.search(r"requiresAgent\s*=\s*true", body):
            agent_only = list(surfaces)
        out.append({"id": m.group(1), "surfaces": surfaces, "agent_only": agent_only})
    if not out:
        raise ValueError("no Instrument(...) parsed from CirclesNav.kt — the parser is wrong, not the tree")
    # A comment between `Instrument(` and its id hid a whole instrument once,
    # and its surfaces simply vanished from the map. Every construction must parse.
    declared = len(re.findall(r"\n\s*Instrument\(", tree_src))
    if declared != len(out):
        raise ValueError(f"{declared} Instrument(...) in CirclesNav.kt but {len(out)} parsed — the parser is wrong, not the tree")
    return out


def _routes(app_src: str) -> list[tuple[str, str]]:
    """Every (NavSurface, Screen) pair in `surfaceToScreen` — ALL of them.

    Two surfaces may open one screen: `Account` and `AgentSettings` both route
    to `Screen.Settings` (CIRISClient#51). A Screen-keyed dict can hold one, and
    the `setdefault` this replaces kept AgentSettings and dropped Account without
    a word — 49 hops where there are 50, and a CSD for the Account card refused
    for a hop the map had thrown away.
    """
    pairs = [(m.group(1), m.group(2))
             for m in re.finditer(r"NavSurface\.(\w+)\s*->\s*Screen\.(\w+)", app_src)]
    if not pairs:
        raise ValueError("no NavSurface.X -> Screen.Y parsed from CIRISApp.kt — the parser is wrong, not the app")
    return pairs


def _lit_surfaces(app_src: str) -> dict[str, str | None]:
    """Screen -> the NavSurface `screenToSurface` lights for it (None for `-> null`).

    The app's own answer to "which card is this screen", read from the function
    rather than guessed, so a screen that two surfaces open is keyed by the one
    the shell highlights on it.
    """
    start = app_src.find("fun screenToSurface(")
    if start < 0:
        raise ValueError("fun screenToSurface not found in CIRISApp.kt — the parser is wrong, not the app")
    open_at = app_src.find("{", start)
    depth, i = 0, open_at
    while i < len(app_src):
        depth += {"{": 1, "}": -1}.get(app_src[i], 0)
        if depth == 0:
            break
        i += 1
    body = app_src[open_at + 1:i]
    out: dict[str, str | None] = {}
    branch = re.compile(
        r"((?:(?:is\s+)?Screen\.\w+\s*,\s*)*(?:is\s+)?Screen\.\w+)\s*->\s*"
        r"(?:[\w.]*NavSurface\.(\w+)|null)"
    )
    for m in branch.finditer(body):
        for screen in re.findall(r"Screen\.(\w+)", m.group(1)):
            out[screen] = m.group(2)
    if not out:
        raise ValueError("screenToSurface found but no branch parsed — the parser is wrong, not the app")
    return out


def _screen_routes(app_src: str) -> dict[str, str]:
    """Screen name -> THE NavSurface its hop is keyed by.

    One surface per screen: the only one, or — when several open it — the one
    `screenToSurface` lights. A screen several surfaces open and the app does
    not disambiguate is an ERROR, not a coin toss: a parser that silently keeps
    one answer is how Account went missing. Every surface's own hop, including
    the ones this cannot key by screen, is in [build_surfaces].
    """
    by_screen: dict[str, list[str]] = {}
    for surface, screen in _routes(app_src):
        by_screen.setdefault(screen, [])
        if surface not in by_screen[screen]:
            by_screen[screen].append(surface)
    lit: dict[str, str | None] | None = None
    out: dict[str, str] = {}
    for screen, surfaces in by_screen.items():
        if len(surfaces) == 1:
            out[screen] = surfaces[0]
            continue
        if lit is None:
            lit = _lit_surfaces(app_src)
        pick = lit.get(screen)
        if pick not in surfaces:
            raise ValueError(
                f"Screen.{screen} is opened by {surfaces} and screenToSurface lights "
                f"{pick!r}, which is none of them — the map cannot say which hop "
                f"Screen.{screen} is, and will not guess"
            )
        out[screen] = pick
    return out


def _hop(surface: str, ids: dict[str, str], placements: list[dict], by_surface: dict[str, dict],
         inst_of: dict[str, dict], has_agent: bool) -> list[str] | None:
    """The chain to one surface on this build, or None when it has no row here."""
    if surface not in ids:
        return None
    p = by_surface.get(surface)
    if p is not None:
        if p["agent_only"] and not has_agent:
            return None
        circle = p["circles"][0]
        chain = [circle_tag(circle), tab_tag(p["tab"])]
        if len(_cards(placements, circle, p["tab"], has_agent)) > 1:
            chain.append(nav_tag(ids[surface]))
        return chain
    inst = inst_of.get(surface)
    if inst is not None:
        if surface in inst["agent_only"] and not has_agent:
            return None
        return [MY_THINGS, instrument_tag(inst["id"]), nav_tag(ids[surface])]
    return None


def screen_classes() -> set[str]:
    """Every member of `sealed class Screen` in CIRISApp.kt, brace-matched.

    The same read `packaging/check_csd_v3.py:_screen_classes` does: a one-line
    regex misses multi-line declarations (`UserChat`). Empty is an error — the
    class plainly exists, so finding nothing means the parser is wrong.
    """
    src = APP.read_text(encoding="utf-8")
    start = src.find("sealed class Screen")
    open_at = src.find("{", start)
    if start < 0 or open_at < 0:
        raise ValueError("sealed class Screen not found in CIRISApp.kt — the parser is wrong, not the app")
    depth, i = 0, open_at
    while i < len(src):
        depth += {"{": 1, "}": -1}.get(src[i], 0)
        if depth == 0:
            break
        i += 1
    members = set(re.findall(r"^\s+(?:data\s+)?(?:object|class)\s+(\w+)", src[open_at + 1:i], re.M))
    if not members:
        raise ValueError("sealed class Screen has no parsed members — the parser is wrong, not the app")
    return members


def _cards(placements: list[dict], circle: str, tab: str, has_agent: bool) -> list[str]:
    return [p["surface"] for p in placements
            if p["tab"] == tab and circle in p["circles"] and (has_agent or not p["agent_only"])]


def build(has_agent: bool = True) -> dict[str, list[str]]:
    """screen -> the tags to click, in order, to get there.

    `has_agent` picks the build: the node build is a subset (a placement
    marked agentOnly is absent), and a tab that has ONE card on this build
    shows it directly, so the chain ends on the tab.
    """
    nav_src = NAV.read_text(encoding="utf-8")
    tree_src = TREE.read_text(encoding="utf-8")
    app_src = APP.read_text(encoding="utf-8")
    ids = _surface_ids(nav_src)
    placements = _placements(tree_src)
    instruments = _instruments(tree_src)
    by_surface = {p["surface"]: p for p in placements}
    inst_of = {s: inst for inst in instruments for s in inst["surfaces"]}

    hops: dict[str, list[str]] = {}
    for screen, surface in _screen_routes(app_src).items():
        chain = _hop(surface, ids, placements, by_surface, inst_of, has_agent)
        if chain is not None:
            hops[screen] = chain
    return hops


def build_surfaces(has_agent: bool = True) -> dict[str, list[str]]:
    """NavSurface -> its chain, for EVERY routed surface.

    [build] is keyed by Screen, and a Screen two surfaces open can have one key.
    This is keyed by surface, so both `Account` and `AgentSettings` — two rows,
    two hops, one `Screen.Settings` — are here.
    """
    nav_src = NAV.read_text(encoding="utf-8")
    tree_src = TREE.read_text(encoding="utf-8")
    app_src = APP.read_text(encoding="utf-8")
    ids = _surface_ids(nav_src)
    placements = _placements(tree_src)
    instruments = _instruments(tree_src)
    by_surface = {p["surface"]: p for p in placements}
    inst_of = {s: inst for inst in instruments for s in inst["surfaces"]}
    out: dict[str, list[str]] = {}
    for surface, _screen in _routes(app_src):
        chain = _hop(surface, ids, placements, by_surface, inst_of, has_agent)
        if chain is not None:
            out[surface] = chain
    return out


def screen_of(surface: str) -> str | None:
    """The Screen a surface opens, from `surfaceToScreen`."""
    for s, screen in _routes(APP.read_text(encoding="utf-8")):
        if s == surface:
            return screen
    return None


def expected_tail(surface_id: str, has_agent: bool = True) -> str | None:
    """The tag a chain to this surface must END on — its row, or its tab when it is the tab's only card."""
    nav_src = NAV.read_text(encoding="utf-8")
    tree_src = TREE.read_text(encoding="utf-8")
    ids = _surface_ids(nav_src)
    name = next((n for n, i in ids.items() if i == surface_id), None)
    if name is None:
        return None
    placements = _placements(tree_src)
    p = next((p for p in placements if p["surface"] == name), None)
    if p is None:
        return nav_tag(surface_id)
    circle = p["circles"][0]
    return nav_tag(surface_id) if len(_cards(placements, circle, p["tab"], has_agent)) > 1 else tab_tag(p["tab"])


def structure() -> dict:
    """The nav as a SHAPE: one tree. Circles × tabs holding cards, and instruments.

    `build()` answers "what do I click to get there". A person redesigning the
    IA needs the other question — how the surfaces relate — and since wave 1 the
    answer is a single tree: every surface is placed once, in a tab for some
    circles or under an instrument; nothing is cross-framed and nothing is
    reached only through a chevron.
    """
    nav_src = NAV.read_text(encoding="utf-8")
    tree_src = TREE.read_text(encoding="utf-8")
    app_src = APP.read_text(encoding="utf-8")
    ids = _surface_ids(nav_src)
    placements = _placements(tree_src)
    instruments = _instruments(tree_src)
    # Surfaces that open a screen. This was the set of SCREEN names, tested
    # against surface names, so `has_screen` was true only where the two
    # happened to be spelled alike.
    routed = {surface for surface, _screen in _routes(app_src)}
    surfaces: dict[str, dict] = {}
    for p in placements:
        surfaces[p["surface"]] = {
            "id": ids.get(p["surface"]), "tab": p["tab"], "circles": p["circles"],
            "agent_only": p["agent_only"], "instrument": None, "has_screen": p["surface"] in routed,
        }
    for inst in instruments:
        for s in inst["surfaces"]:
            surfaces[s] = {
                "id": ids.get(s), "tab": None, "circles": [], "agent_only": s in inst["agent_only"],
                "instrument": inst["id"], "has_screen": s in routed,
            }
    placed = set(surfaces)
    return {
        "circles": CIRCLES,
        "tabs": ["files", "chats", "people", "safety", "rules", "decisions", "record"],
        "instruments": [i["id"] for i in instruments],
        "surfaces": surfaces,
        "unplaced": sorted(n for n in ids if n not in placed),
        "cross_framed": [],
    }


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--screen", help="print the hop for one screen")
    ap.add_argument("--node", action="store_true", help="the node build (no agent-only surfaces)")
    args = ap.parse_args(argv)

    hops = build(has_agent=not args.node)
    if args.screen:
        chain = hops.get(args.screen)
        if not chain:
            print(f"no route to {args.screen!r}", file=sys.stderr)
            return 1
        print(" -> ".join(chain))
        return 0
    for screen in sorted(hops):
        print(f"  {screen:<28} {' -> '.join(hops[screen])}")
    print(f"\n  {len(hops)} screens reachable from the shell")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
