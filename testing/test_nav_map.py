"""The sidebar hop is derived from the client, and must stay derived.

FSD/CSD_STANDARD.md §5: a flow never encodes the walk to its first screen. The
runner does it, so a sidebar reorder is one fix instead of one per flow. That
only holds while the runner can answer "where does Screen X live" from the
client's own tables — the moment anyone writes that map by hand it becomes a
second source, and it drifts the first time a surface changes group. Silently:
a wrong hop looks exactly like a screen that failed to compose.
"""

from __future__ import annotations

import pathlib
import re

import pytest

from testing.gate import nav_map

#: The four the CSD flows need (CIRISClient#45). Named explicitly because they
#: are the reason this module exists, and because two of them were MISSING from
#: the first version of the parser — it terminated on `\n\s*\)` and those
#: declarations close with `,)` on the same line as their last argument. The
#: symptom was "no sidebar route", which reads as a nav gap rather than a parser
#: bug, and that is exactly the kind of wrong answer a derived map must not give.
CSD_SCREENS = ["HealthReputation", "Constitutional", "LayerFamily", "LayerLocalCommunity"]


@pytest.fixture(scope="module")
def hops() -> dict[str, list[str]]:
    return nav_map.build()


def test_the_tag_rule_matches_the_clients_own(hops):
    """`navTag` is one rule; two copies of it is one copy too many."""
    src = nav_map.SIDEBAR.read_text(encoding="utf-8")
    m = re.search(r'"nav_epistemic_\$\{surfaceId\.replace\(\'-\', \'_\'\)\}"', src)
    assert m, (
        "EpistemicSidebar's navTag no longer spells the rule this module mirrors — "
        "re-read it before trusting any hop"
    )
    assert nav_map.nav_tag("health-reputation") == "nav_epistemic_health_reputation"


def test_the_group_tag_rule_matches_the_clients_own(hops):
    src = nav_map.SIDEBAR.read_text(encoding="utf-8")
    assert 'testableClickable("nav_group_${group.id}")' in src, (
        "the group toggle tag rule moved"
    )
    assert nav_map.group_tag("manage") == "nav_group_manage"


@pytest.mark.parametrize("screen", CSD_SCREENS)
def test_every_csd_screen_has_a_route(hops, screen):
    chain = hops.get(screen)
    assert chain, f"no sidebar route to {screen!r} — a CSD flow starting there cannot be reached"
    assert chain[-1].startswith("nav_epistemic_"), f"{screen}: the hop must end on a surface"


def test_a_child_surface_is_reached_through_its_parent(hops):
    """`Constitutional` is a child of the Global Commons layer, not a top-level
    entry. A hop that clicked it directly would fail on a sidebar that has not
    expanded its parent — and would fail as "element not found", the message
    that is indistinguishable from a broken screen."""
    chain = hops["Constitutional"]
    assert chain == [
        "nav_group_commons-layers",
        "nav_epistemic_layer_global_commons",
        "nav_epistemic_constitutional",
    ], chain


def test_the_map_is_not_trivially_small(hops):
    """A parser that silently matches nothing returns {} and every lookup then
    fails as "no route", which reads as a nav problem. `nav-gate-registry` in
    this repo's readiness gates exists for the same reason: a parser that finds
    nothing where the construct plainly exists must fail loudly."""
    assert len(hops) > 40, (
        f"only {len(hops)} screens resolved; the client declares far more, so the "
        f"parser is matching a subset of the declaration forms"
    )


def test_no_hop_repeats_a_tag(hops):
    """`Sessions -> nav_epistemic_interact -> nav_epistemic_interact` was the
    first parser's output: a child whose own id had not been resolved fell back
    to its parent's tag twice. A repeated tag means a lookup returned nothing
    and something else filled the gap."""
    for screen, chain in hops.items():
        assert len(chain) == len(set(chain)), f"{screen}: repeated tag in {chain}"
