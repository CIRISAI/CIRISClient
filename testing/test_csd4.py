"""`packaging/check_csd4.py` — a CSD/4 card's `csd:tests` block against the
pinned catalogue (CIRISClient#179, CIRISServer#802 @ 003871fd).

The clean fixture is CSD-095's §5 block (CIRISClient#218) retagged; every
plant is one edit to it, and each must be refused BY NAME — a check whose red
path has never run is a check with an untested half (AGENTS.md).
"""
from __future__ import annotations

import pathlib
import sys

import pytest

yaml = pytest.importorskip("yaml")

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "packaging"))

import check_csd4  # noqa: E402
import check_csd_v3  # noqa: E402

REGISTRY = ROOT / "client" / "ceg" / "namespace_registry.json"


@pytest.fixture(scope="module")
def cat() -> dict:
    return check_csd4.load_catalogue()


def _clean() -> str:
    return check_csd4.FIXTURE.read_text(encoding="utf-8")


def test_catalogue_is_v3_closed_and_has_the_new_atom(cat: dict) -> None:
    assert cat["version"] == 3
    assert sorted(cat["instants"], key=lambda c: int(c[1:])) == [f"T{i}" for i in range(20)]
    assert "key.membership.proposed_add" in cat["atoms"]


def test_every_server_requirement_holds_at_one_instant_somewhere(cat: dict) -> None:
    # The server's own `evaluate` says so at the pin; the vendored trace must
    # agree, or the trace is not the model it claims to be.
    for csd, spec in cat["requirements"].items():
        for name, t in spec["tests"].items():
            assert check_csd4._where(cat, frozenset(t["at"])), f"{csd}/{name}"


def test_clean_fixture_is_green(cat: dict, tmp_path: pathlib.Path) -> None:
    card = tmp_path / check_csd4.FIXTURE.name
    card.write_text(_clean(), encoding="utf-8")
    problems, _, has = check_csd4.check_doc(card, cat)
    assert has and problems == []


@pytest.mark.parametrize("name,text,want", check_csd4._plants(_clean()), ids=lambda x: x if isinstance(x, str) and len(x) < 50 else None)
def test_each_plant_is_refused_by_name(cat: dict, tmp_path: pathlib.Path, name: str, text: str, want: str) -> None:
    card = tmp_path / check_csd4.FIXTURE.name
    card.write_text(text, encoding="utf-8")
    problems, _, _ = check_csd4.check_doc(card, cat)
    assert any(want in p for p in problems), (name, problems)


def test_a_card_without_the_block_is_untouched(cat: dict) -> None:
    problems, notes, has = check_csd4.check_doc(ROOT / "FSD" / "CSD" / "CSD-107-file-custody.md", cat)
    assert (problems, notes, has) == ([], [], False)


def _envisioned(tmp_path: pathlib.Path, body: str) -> pathlib.Path:
    """The block alone, at `envisioned` (which needs nothing else), through
    check_csd_v3 — the path build.yml and gates.sh actually run."""
    block = body.split("```yaml csd:tests\n", 1)[1]
    doc = tmp_path / "CSD-095-self-key-roster.md"
    doc.write_text("# CSD-095\n\n```yaml csd:stage\nstage: envisioned\n```\n\n```yaml csd:tests\n" + block,
                   encoding="utf-8")
    return doc


def test_check_csd_v3_runs_the_csd4_check(tmp_path: pathlib.Path) -> None:
    reg = check_csd_v3.load_registry(REGISTRY)
    assert check_csd_v3.check(_envisioned(tmp_path, _clean()), reg) == []
    planted = _clean().replace("[key.membership.proposed_add, device.join_requested]",
                               "[key.membership.proposed_ad, device.join_requested]")
    problems = check_csd_v3.check(_envisioned(tmp_path, planted), reg)
    assert any("key.membership.proposed_ad" in p and "vocabulary" in p for p in problems), problems


def test_self_test_passes() -> None:
    assert check_csd4.self_test() == 0
