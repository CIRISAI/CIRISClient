"""The shell hop is derived from the client, and must stay derived.

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


def test_the_tag_rules_match_the_clients_own(hops):
    """`navTag`, `circleTag`, `Tab.tag` and `Instrument.tag` are one rule each; two copies is one too many."""
    src = nav_map.TREE.read_text(encoding="utf-8")
    assert 'fun navTag(surface: NavSurface): String = "nav_epistemic_" + slug(surface.id)' in src
    assert 'fun circleTag(scope: CohortScope): String = "circle_" + slug(scope.id)' in src
    assert 'val tag: String get() = "tab_$id"' in src
    assert 'val tag: String get() = "nav_instrument_" + id.replace(\'-\', \'_\')' in src
    assert 'const val MY_THINGS_TAG = "btn_my_things"' in src
    assert nav_map.nav_tag("health-reputation") == "nav_epistemic_health_reputation"
    assert nav_map.circle_tag("local-community") == "circle_local_community"
    assert nav_map.instrument_tag("this-node") == "nav_instrument_this_node"


@pytest.mark.parametrize("screen", CSD_SCREENS)
def test_every_csd_screen_has_a_route(hops, screen):
    chain = hops.get(screen)
    assert chain, f"no route to {screen!r} — a CSD flow starting there cannot be reached"
    assert chain[0].startswith("circle_") or chain[0] == nav_map.MY_THINGS, f"{screen}: a chain starts on a circle or on My things"
    assert chain[-1].startswith(("nav_epistemic_", "tab_")), f"{screen}: the hop must end on a row or on the tab that shows it directly"


def test_a_single_card_tab_shows_the_card_and_the_chain_ends_on_the_tab(hops):
    """People is Contacts: in every circle but one it is the tab's only card, so
    the shell shows it directly and there is no row to click. A chain that
    named `nav_epistemic_contacts` there would be a ghost."""
    assert hops["Contacts"] == ["circle_agent", "tab_people"], hops["Contacts"]
    assert nav_map.expected_tail("contacts") == "tab_people"
    # and a multi-card tab ends on the row
    assert hops["Constitutional"] == ["circle_global_commons", "tab_files", "nav_epistemic_constitutional"], hops["Constitutional"]
    assert nav_map.expected_tail("constitutional") == "nav_epistemic_constitutional"


def test_an_instrument_surface_is_reached_through_my_things(hops):
    assert hops["ManageNodes"] == [nav_map.MY_THINGS, "nav_instrument_this_node", "nav_epistemic_nodes"], hops["ManageNodes"]


def test_the_node_build_is_a_subset(hops):
    node = nav_map.build(has_agent=False)
    assert "Interact" not in node and "Interact" in hops
    for screen, chain in node.items():
        assert screen in hops, screen


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
