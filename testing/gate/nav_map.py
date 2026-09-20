"""Where a flow's first screen lives in the sidebar, derived from the client.

    python3 -m testing.gate.nav_map                 # print the map
    python3 -m testing.gate.nav_map --screen HealthReputation

THE FLOW NEVER ENCODES THE HOP (FSD/CSD_STANDARD.md §5). Before a flow runs, the
runner walks to its first step's `requires: screen:` through the sidebar; the
flow asserts arrival and nothing else. That is why a sidebar reorder is one fix
in the runner instead of one per flow — and it only works if the runner can
answer "where does Screen X live" without anybody maintaining a table by hand.

DERIVED, NOT TRANSCRIBED. Everything here comes out of the client's own sources:

    EpistemicSidebar.kt   navTag(id) = "nav_epistemic_${id.replace('-','_')}"
                          group toggle = "nav_group_${group.id}"
    EpistemicNav.kt       object <Name> : NavSurface(id = "<surface-id>", …)
                          <GROUP> = NavGroup(id = "<g>", surfaces = listOf(…))
                          children = listOf(…)   — a child is reached via its parent
    CIRISApp.kt           NavSurface.<Name> -> Screen.<Screen>

A hand-written map would be a second source for a question the client already
answers, and it would drift the first time a surface moved group — silently,
because a wrong hop looks exactly like a screen that failed to compose. Parsed
with `re` per AGENTS.md: a check that requires a build has already lost.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

CLIENT = Path(__file__).resolve().parents[2] / "client" / "shared" / "src" / "commonMain" / "kotlin" / "ai" / "ciris" / "mobile" / "shared"
NAV = CLIENT / "ui" / "nav" / "EpistemicNav.kt"
SIDEBAR = CLIENT / "ui" / "nav" / "EpistemicSidebar.kt"
APP = CLIENT / "CIRISApp.kt"


def slug(nav_id: str) -> str:
    """`navSlug` from EpistemicSidebar.kt — the one rule, mirrored.

    It is one rule on each side and a test holds them together, because the
    alternative was measured: the client slugged the surface row and did not
    slug the chevron beside it, so one surface answered to
    `nav_epistemic_agent_settings` and `nav_expand_agent-settings` at once, and
    anything deriving the second from the first addressed a tag that did not
    exist.
    """
    return nav_id.replace("-", "_")


def nav_tag(surface_id: str) -> str:
    """`navTag` from EpistemicSidebar.kt, kept as one rule in one place."""
    return "nav_epistemic_" + slug(surface_id)


def expand_tag(surface_id: str) -> str:
    """`expandTag` — the chevron that opens a surface's children.

    Distinct from [nav_tag]: the row navigates TO a surface, the chevron opens
    what is UNDER it. Clicking the row to reveal a child goes to that screen and
    reveals nothing, which is why Sessions, Scheduler, LLM Settings and Skill
    Studio all read as unreachable from their parents.
    """
    return "nav_expand_" + slug(surface_id)


def group_tag(group_id: str) -> str:
    """`groupTag` — slugged like the other two, as of the DRY pass."""
    return "nav_group_" + slug(group_id)


def _declarations(nav_src: str):
    r"""Yield (surface name, argument text) for every `object X : NavSurface(...)`.

    PAREN-BALANCED, NOT REGEX-TERMINATED. These declarations close with `,)` on
    the same line as the last argument and nest `listOf(...)` inside, so a
    pattern ending at `\n\s*\)` silently skipped every surface written that way
    — including two of the four the CSD flows need. A missing surface reads as
    "no sidebar route", which looks like a nav gap rather than a parser bug.
    """
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


def _children(nav_src: str) -> dict[str, str]:
    """child surface -> parent surface. A child is reached through its parent."""
    out: dict[str, str] = {}
    for parent, body in _declarations(nav_src):
        kids = re.search(r"children\s*=\s*listOf\(([^)]*)\)", body, re.S)
        if not kids:
            continue
        for kid in re.findall(r"\b([A-Z]\w+)\b", kids.group(1)):
            out.setdefault(kid, parent)
    return out


def _groups(nav_src: str) -> dict[str, str]:
    """surface name -> the id of the group that offers it."""
    out: dict[str, str] = {}
    # `val X = NavGroup(` and `fun xGroup(...) = NavGroup(` both appear.
    for m in re.finditer(r"(?:val\s+\w+|fun\s+\w+\([^)]*\))\s*=\s*NavGroup\((.*?)\n\s*labelKey", nav_src, re.S):
        body = m.group(1)
        gid = re.search(r'id\s*=\s*"([a-z0-9-]+)"', body)
        if not gid:
            continue
        for surface in re.findall(r"NavSurface\.(\w+)", body):
            out.setdefault(surface, gid.group(1))
    return out


def _screen_routes(app_src: str) -> dict[str, str]:
    """Screen name -> NavSurface name, from CIRISApp's route table."""
    out: dict[str, str] = {}
    for m in re.finditer(r"NavSurface\.(\w+)\s*->\s*Screen\.(\w+)", app_src):
        surface, screen = m.group(1), m.group(2)
        # First writer wins: `Safety -> Moderation` precedes `Moderation ->
        # Moderation`, and the leaf is the honest way to reach that screen.
        out.setdefault(screen, surface)
        if surface == screen:
            out[screen] = surface
    return out


def build() -> dict[str, list[str]]:
    """screen -> the tags to click, in order, to get there."""
    nav_src = NAV.read_text(encoding="utf-8")
    app_src = APP.read_text(encoding="utf-8")
    ids, kids, groups = _surface_ids(nav_src), _children(nav_src), _groups(nav_src)

    hops: dict[str, list[str]] = {}
    for screen, surface in _screen_routes(app_src).items():
        if surface not in ids:
            continue
        chain: list[str] = []
        gid = groups.get(surface) or groups.get(kids.get(surface, ""))
        if gid:
            chain.append(group_tag(gid))
        parent = kids.get(surface)
        if parent and parent in ids:
            chain.append(nav_tag(ids[parent]))
        chain.append(nav_tag(ids[surface]))
        hops[screen] = chain
    return hops


def structure() -> dict:
    """The nav as a SHAPE, not just a set of routes.

    `build()` answers "what do I click to get there". A person redesigning the
    IA needs the other question — how the surfaces relate — and the answer is
    that there are TWO axes, not one:

      * **group** — which section of the rail a surface is filed under;
      * **parent** — which surface's chevron has to be open to reveal it.

    They are independent, and four surfaces prove it: `Config`, `Runtime` and
    `System` are filed under **node** while hanging off `AgentSettings`, which
    is filed under **agent**; `GraphMemory` is filed under node beneath
    `Memory`. The nav comment calls this deliberate — "the agent framing
    alongside the node-infra framing" — so a reader who assumes a single tree
    will mis-read those four every time.

    Fourteen surfaces carry no group at all. Thirteen of them are children and
    reach the rail through a parent; one, `AccordCeremony`, reaches it through
    nothing, because its own declaration says it opens from the Accord screen
    only when no accord family exists yet.
    """
    nav_src = NAV.read_text(encoding="utf-8")
    app_src = APP.read_text(encoding="utf-8")
    ids, kids, groups = _surface_ids(nav_src), _children(nav_src), _groups(nav_src)
    routed = set(_screen_routes(app_src))

    surfaces = {}
    for obj, surface_id in ids.items():
        parent = kids.get(obj)
        surfaces[obj] = {
            "id": surface_id,
            "group": groups.get(obj),
            "parent": parent,
            "parent_group": groups.get(parent) if parent else None,
            "has_screen": obj in routed,
        }
    for obj, rec in surfaces.items():
        rec["cross_framed"] = bool(
            rec["parent_group"] and rec["group"] and rec["parent_group"] != rec["group"]
        )
        rec["children"] = sorted(o for o, r in surfaces.items() if r["parent"] == obj)
    return {
        "surfaces": surfaces,
        "groups": sorted({g for g in groups.values()}),
        "ungrouped": sorted(o for o, r in surfaces.items() if not r["group"]),
        "cross_framed": sorted(o for o, r in surfaces.items() if r["cross_framed"]),
    }


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--screen", help="print the hop for one screen")
    args = ap.parse_args(argv)

    hops = build()
    if args.screen:
        chain = hops.get(args.screen)
        if not chain:
            print(f"no sidebar route to {args.screen!r}", file=sys.stderr)
            return 1
        print(" -> ".join(chain))
        return 0
    for screen in sorted(hops):
        print(f"  {screen:<28} {' -> '.join(hops[screen])}")
    print(f"\n  {len(hops)} screens reachable from the sidebar")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
