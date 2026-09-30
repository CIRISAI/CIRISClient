"""`check_csd_v3.check_topology` — the CSD/4 `csd:topology` block, structurally.

Each break below is planted against CSD-107's own block (the first CSD to carry
one), so the red half of the check has run: a check whose failure path has
never fired is a check with an untested half (AGENTS.md, Testing Guidelines).
"""

from __future__ import annotations

import copy
import pathlib
import re
import sys

import pytest

yaml = pytest.importorskip("yaml")

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "packaging"))

import check_csd_v3  # noqa: E402

CSD_107 = ROOT / "FSD" / "CSD" / "CSD-107-file-custody.md"


def _block() -> dict:
    text = CSD_107.read_text(encoding="utf-8")
    blocks = dict(check_csd_v3.BLOCK.findall(text))
    assert "topology" in blocks, "CSD-107 carries a csd:topology block"
    return yaml.safe_load(blocks["topology"])


def test_csd_107_block_is_clean() -> None:
    assert check_csd_v3.check_topology(_block()) == []


def test_csd_107_names_the_pending_relation_and_negative() -> None:
    t = _block()["topology"]
    assert {"rel": "custody", "person": "one", "device": "D1", "file": "last"} in t["relations"]
    assert [n["check"] for n in t["negatives"]] == ["no_wider_self_rows"]
    assert "custody" in check_csd_v3.TOPOLOGY_PENDING
    assert "no_wider_self_rows" in check_csd_v3.TOPOLOGY_PENDING


def _broken(mutate) -> list[str]:
    b = copy.deepcopy(_block())
    mutate(b["topology"])
    return check_csd_v3.check_topology(b)


@pytest.mark.parametrize(
    "why, mutate, expect",
    [
        ("a dial to an undeclared node", lambda t: t["nodes"][1]["dials"].append("D9"), r"names node 'D9'"),
        ("an unknown relation", lambda t: t["relations"].append({"rel": "teleport"}), r"relation 'teleport'"),
        ("an unknown negative", lambda t: t["negatives"].append({"check": "vibes"}), r"negative 'vibes'"),
        ("an unknown layer", lambda t: t.__setitem__("weather", []), r"unknown layer"),
        ("rule 2: a person accepting a root a device does not",
         lambda t: t["nodes"][1].__setitem__("accepts", "R2") or t["roots"].append({"id": "R2"}), r"rule 2"),
        ("the actor on a device they do not own",
         lambda t: t["persons"][0].__setitem__("owns", ["D2"]), r"actor's device 'D1'"),
        ("a file on a device its person does not own",
         lambda t: t["relations"].append({"rel": "file", "person": "one", "device": "D3"}), r"names node 'D3'"),
        ("a root nobody declared", lambda t: t["canonicals"][0].__setitem__("holds", "Q"), r"names root 'Q'"),
    ],
)
def test_a_planted_break_fails(why: str, mutate, expect: str) -> None:
    problems = _broken(mutate)
    assert any(re.search(expect, p) for p in problems), f"{why}: {problems}"


def test_not_a_mapping_fails() -> None:
    assert check_csd_v3.check_topology({"roots": []}) != []
    assert check_csd_v3.check_topology(None) != []
