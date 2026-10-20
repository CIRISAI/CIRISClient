#!/usr/bin/env python3
"""CSD/4: a card's catalogue topology and its tests, against the pinned catalogue.

    python3 packaging/check_csd4.py FSD/CSD/CSD-*.md      # every card carrying `csd:tests`
    python3 packaging/check_csd4.py --verify-pin          # the vendored catalogue only
    python3 packaging/check_csd4.py --self-test           # plant each defect; each must go red

`check_csd_v3.py` calls `check_card()` for every card that carries the block, so
the per-card loop in build.yml / gates.sh covers it; this file holds the
catalogue (`packaging/csd4/`, CIRISServer's `harness/native/topologies/catalog/`
at the commit in `packaging/csd4/PIN.md`) and the self-test.

THE BLOCK. FSD/CSD4.md §1 (CIRISServer#802) adds three fields to a card:
`derives`, `topology: Tn` and `tests: {name: {at: [atoms], probe?}}`. Here they
are one typed block whose body is the card's own entry in the server's
`requirements.yaml`, plus the catalogue version it was written against:

    ```yaml csd:tests
    CSD-095:
      catalogue_version: 3
      topology: T10
      tests:
        add_held:
          at: [key.membership.proposed_add, device.join_requested]
          probe: {node: A2, path: /v1/self/roster/proposals, status: 200, expect: {...}}
    ```

Its name is `csd:tests`, not `csd:topology`: `csd:topology` is the pre-catalogue
LAYER GRAPH (CSD-107), which CSD/4 §1 retires ("one catalogue id; never a
topology block"). Two block names keep the two forms from sharing a parser, so a
catalogue id can never be read as an empty layer graph or vice versa — and a card
carrying both is refused.

REFUSED, BY NAME (CSD4.md §1, plus the card/server agreement CIRISClient#179 asks):
  1. `topology` outside T0–T19;
  2. an atom outside the vocabulary of the catalogue version the card names (and a
     version other than the pinned one);
  3. a test whose atoms no catalogue entry holds AT ONE INSTANT — read from the
     server model's own trace (`traces.json`), not re-derived here;
  4. any disagreement with `requirements.yaml` for the card's CSD id: no entry,
     a different topology, a missing or extra test, different atoms, a different probe;
  5. `building` or later without `derives:`.
Reported, not failed (the plan's `reselected` / the builder's lag, §5–§6):
the named entry does not reach a test's atoms but another does; the atoms hold
only past a step the builder refuses today.
"""
from __future__ import annotations

import hashlib
import json
import re
import sys
import tempfile
from pathlib import Path

try:
    import yaml
except ImportError:  # pragma: no cover
    sys.exit("check_csd4 needs PyYAML (as check_csd_v3 does)")

ROOT = Path(__file__).resolve().parents[1]
CATALOGUE_DIR = Path(__file__).resolve().parent / "csd4"
FIXTURE = ROOT / "testing" / "fixtures" / "csd4" / "CSD-095-self-key-roster.md"

BLOCK_NAME = "tests"
CATALOG_ID = re.compile(r"^T(0|[1-9][0-9]*)$")
CSD_ID = re.compile(r"^CSD-[0-9]{3}$")
ENTRY_KEYS = {"catalogue_version", "topology", "derives", "tests"}
TEST_KEYS = {"at", "probe"}
PROBE_KEYS = {"node", "path", "status", "expect"}
PINNED_FILES = ("requirements.yaml", "topology_vocabulary.json", "topology_vocabulary.pin", "traces.json")
PIN_ROW = re.compile(r"^\|\s*`([^`]+)`\s*\|\s*`([0-9a-f]{64})`\s*\|\s*$", re.M)
STAGES = ["envisioned", "sketched", "building", "testable", "verified", "shipped"]


class CatalogueError(Exception):
    """The vendored catalogue is not what PIN.md says it is."""


def load_catalogue(root: Path = CATALOGUE_DIR) -> dict:
    """The pinned catalogue, refused outright if any file disagrees with PIN.md.

    A PIN.md from which no row parses is a broken parser, not an unpinned
    catalogue — it fails loudly, as does a traces.json with no steps."""
    pin_md = root / "PIN.md"
    if not pin_md.exists():
        raise CatalogueError(f"{pin_md}: missing — the catalogue is unpinned")
    rows = dict(PIN_ROW.findall(pin_md.read_text(encoding="utf-8")))
    if not rows:
        raise CatalogueError(f"{pin_md}: parsed ZERO `| file | sha256 |` rows — refusing rather than trusting unpinned files")
    for name in PINNED_FILES:
        if name not in rows:
            raise CatalogueError(f"{pin_md}: no sha256 row for {name}")
        got = hashlib.sha256((root / name).read_bytes()).hexdigest()
        if got != rows[name]:
            raise CatalogueError(f"{root / name}: sha256 {got[:12]}… but PIN.md pins {rows[name][:12]}… "
                                 f"— re-vendor with packaging/vendor_csd4.py, never hand-edit")

    vocab = json.loads((root / "topology_vocabulary.json").read_text(encoding="utf-8"))
    traces = json.loads((root / "traces.json").read_text(encoding="utf-8"))
    reqs = yaml.safe_load((root / "requirements.yaml").read_text(encoding="utf-8")) or {}
    version = vocab["_meta"]["catalogue_version"]
    atoms = set(vocab["atoms"])
    if not atoms:
        raise CatalogueError("topology_vocabulary.json: ZERO atoms parsed")
    if traces["_meta"]["catalogue_version"] != version:
        raise CatalogueError(f"traces.json is catalogue version {traces['_meta']['catalogue_version']}, "
                             f"the vocabulary is {version}")
    ids = traces["_meta"]["catalog_ids"]
    if ids != [f"T{i}" for i in range(20)] or sorted(traces["entries"], key=lambda c: int(c[1:])) != ids:
        raise CatalogueError(f"traces.json: the closed catalogue is T0–T19; got {sorted(traces['entries'])}")
    instants: dict[str, list[tuple[str, frozenset]]] = {}
    for cid, e in traces["entries"].items():
        if not e["steps"]:
            raise CatalogueError(f"traces.json: {cid} has ZERO steps — a trace of nothing is a broken generator")
        seq = [("initial", frozenset(e["initial"]))] + [(s["id"], frozenset(s["atoms"])) for s in e["steps"]]
        stray = sorted(set().union(*(st for _, st in seq)) - atoms)
        if stray:
            raise CatalogueError(f"traces.json: {cid} reaches atoms outside the vocabulary: {stray}")
        instants[cid] = seq
    if not isinstance(reqs, dict) or not reqs:
        raise CatalogueError("requirements.yaml: ZERO entries parsed")
    return {"version": version, "atoms": atoms, "instants": instants, "entries": traces["entries"],
            "requirements": reqs}


def card_id(doc: Path, text: str) -> str | None:
    m = re.match(r"(CSD-[0-9]{3})\b", doc.name) or re.search(r"\*\*CSD\*\*:\s*(CSD-[0-9]{3})", text)
    return m.group(1) if m else None


def _where(cat: dict, at: frozenset) -> list[tuple[str, str, int]]:
    """Every (entry, step id, step index) at which all of `at` hold at once —
    the first such step per entry. Index 0 is the state before the first step."""
    out = []
    for cid, seq in cat["instants"].items():
        for i, (sid, st) in enumerate(seq):
            if at <= st:
                out.append((cid, sid, i))
                break
    return out


def check_card(doc: Path, text: str, block: object, stage: str | None, cat: dict,
               has_layer_graph: bool = False) -> tuple[list[str], list[str]]:
    """(problems, notes) for one card's `csd:tests` block."""
    problems: list[str] = []
    notes: list[str] = []
    csd = card_id(doc, text)
    if has_layer_graph:
        problems.append("tests: the card carries `csd:tests` AND a `csd:topology` layer graph — "
                        "CSD/4 §1: one catalogue id, never a topology block")
    if not isinstance(block, dict) or len(block) != 1:
        return problems + [f"tests: the block must be ONE mapping `{csd or 'CSD-nnn'}: {{catalogue_version, topology, tests}}` "
                           f"(the card's entry in requirements.yaml); got {type(block).__name__} "
                           f"with {len(block) if isinstance(block, dict) else 0} key(s)"], notes
    (key, entry), = block.items()
    if not CSD_ID.match(str(key)):
        problems.append(f"tests: the block's key {key!r} is not a CSD id (CSD-nnn)")
    elif csd and key != csd:
        problems.append(f"tests: the block is keyed {key} but this card is {csd}")
    if not isinstance(entry, dict):
        return problems + [f"tests: {key} must map to {{catalogue_version, topology, tests}}"], notes
    unknown = sorted(set(entry) - ENTRY_KEYS)
    if unknown:
        problems.append(f"tests: unknown key(s) {unknown} — known: {sorted(ENTRY_KEYS)}; an unread key is a silent no")

    ver = entry.get("catalogue_version")
    if ver is None:
        problems.append(f"tests: no `catalogue_version:` — a card pins the version it was written against (CSD4.md §4); "
                        f"the pinned catalogue is {cat['version']}")
    elif ver != cat["version"]:
        problems.append(f"tests: catalogue_version {ver!r} is not the pinned catalogue ({cat['version']}, "
                        f"packaging/csd4/PIN.md) — its vocabulary is not here to check against")

    topo = entry.get("topology")
    if not isinstance(topo, str) or not CATALOG_ID.match(topo) or topo not in cat["instants"]:
        problems.append(f"tests: topology {topo!r} is outside the closed catalogue T0–T19 (CSD4.md §1 rule 1)")
        topo = None

    derives = entry.get("derives")
    if derives is not None and (not isinstance(derives, list) or not derives
                                or not all(isinstance(d, (str, int)) for d in derives)):
        problems.append(f"tests: `derives:` must be a non-empty list of plain-rule ids (or [ops]); got {derives!r}")
    if stage in STAGES and STAGES.index(stage) >= STAGES.index("building") and not derives:
        problems.append(f"tests: stage {stage!r} without `derives:` (CSD4.md §1 rule 4, CIRISClient#179 §2)")

    tests = entry.get("tests")
    if not isinstance(tests, dict) or not tests:
        problems.append("tests: `tests:` is missing or empty — a CSD/4 block that tests nothing is a parse that "
                        "found nothing, and is refused")
        tests = {}
    for name, t in tests.items():
        where = f"tests.{name}"
        if not isinstance(t, dict):
            problems.append(f"{where}: must be {{at: [atoms], probe?: …}}")
            continue
        extra = sorted(map(str, set(t) - TEST_KEYS))
        if extra:
            problems.append(f"{where}: unknown key(s) {extra} — known: {sorted(TEST_KEYS)}")
        at = t.get("at")
        if not isinstance(at, list) or not at or not all(isinstance(a, str) for a in at):
            problems.append(f"{where}: `at:` must be a non-empty list of atoms; got {at!r}")
            continue
        outside = sorted(set(at) - cat["atoms"])
        if outside:
            problems.append(f"{where}: atom(s) {outside} are not in the catalogue version {cat['version']} vocabulary "
                            f"(CSD4.md §1 rule 2)")
            continue
        probe = t.get("probe")
        if probe is not None:
            if not isinstance(probe, dict):
                problems.append(f"{where}: probe must be a mapping")
            else:
                if True in probe:
                    problems.append(f"{where}: the probe names its node with `on:`, which YAML reads as true — use `node:`")
                bad = sorted(str(k) for k in probe if k is not True and k not in PROBE_KEYS)
                if bad:
                    problems.append(f"{where}: probe key(s) {bad} — known: {sorted(PROBE_KEYS)}")
        hits = _where(cat, frozenset(at))
        if not hits:
            problems.append(f"{where}: no catalogue entry holds {sorted(at)} at one instant — the plan reports it "
                            f"`uncovered` (CSD4.md §1 rule 3)")
            continue
        if topo and topo not in {c for c, _, _ in hits}:
            notes.append(f"{where}: {topo} never holds {sorted(at)} at once; the planner would reselect "
                         f"{', '.join(c for c, _, _ in hits)}")
        for cid, sid, i in hits:
            if cid != (topo or cid):
                continue
            e = cat["entries"][cid]
            # index 0 is `initial`, so a step's trace index is i - 1.
            if i - 1 >= e["buildable_prefix"]:
                stop = e["steps"][e["buildable_prefix"]]["id"]
                notes.append(f"{where}: {cid} reaches it at {sid}, past {stop}, which the builder refuses today "
                             f"— {e['refused'].get(stop, '?')}")

    # The card and the server's copy are ONE fact written twice; any difference fails.
    if CSD_ID.match(str(key)):
        server = cat["requirements"].get(key)
        if server is None:
            problems.append(f"requirements: the pinned requirements.yaml has no {key} — the server must carry the "
                            f"card's tests before the card can claim them")
        else:
            if server.get("topology") != entry.get("topology"):
                problems.append(f"requirements: {key} topology {entry.get('topology')!r} on the card, "
                                f"{server.get('topology')!r} in requirements.yaml")
            stests = server.get("tests") or {}
            missing = sorted(set(stests) - set(tests))
            extra = sorted(set(tests) - set(stests))
            if missing:
                problems.append(f"requirements: {key} test(s) {missing} are in requirements.yaml and not on the card")
            if extra:
                problems.append(f"requirements: {key} test(s) {extra} are on the card and not in requirements.yaml")
            for name in sorted(set(stests) & set(tests)):
                mine, theirs = tests[name] or {}, stests[name] or {}
                if not isinstance(mine, dict):
                    continue
                if set(mine.get("at") or []) != set(theirs.get("at") or []):
                    problems.append(f"requirements: {key}.{name} atoms differ — card {sorted(mine.get('at') or [])}, "
                                    f"requirements.yaml {sorted(theirs.get('at') or [])}")
                if mine.get("probe") != theirs.get("probe"):
                    problems.append(f"requirements: {key}.{name} probe differs — card {mine.get('probe')!r}, "
                                    f"requirements.yaml {theirs.get('probe')!r}")
    return problems, notes


def check_doc(doc: Path, cat: dict) -> tuple[list[str], list[str], bool]:
    """(problems, notes, carries_block) for a card file — standalone use."""
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    from check_csd_v3 import BLOCK  # noqa: PLC0415 — one block grammar
    text = doc.read_text(encoding="utf-8")
    blocks: dict[str, str] = {}
    for name, body in BLOCK.findall(text):
        blocks.setdefault(name, body)
    if BLOCK_NAME not in blocks:
        return [], [], False
    try:
        block = yaml.safe_load(blocks[BLOCK_NAME])
    except yaml.YAMLError as e:
        return [f"csd:tests does not parse: {e}"], [], True
    stage = None
    try:
        stage = (yaml.safe_load(blocks.get("stage", "")) or {}).get("stage")
    except yaml.YAMLError:
        pass
    p, n = check_card(doc, text, block, stage, cat, "topology" in blocks)
    return p, n, True


# ── self-test ────────────────────────────────────────────────────────────────

def _plants(clean: str) -> list[tuple[str, str, str]]:
    """(name, planted text, the refusal that must appear). Each is ONE edit."""
    def sub(old: str, new: str) -> str:
        assert clean.count(old) == 1, f"self-test fixture drifted: {old!r} occurs {clean.count(old)}×"
        return clean.replace(old, new)
    return [
        ("bad topology", sub("topology: T10", "topology: T20"), "outside the closed catalogue T0–T19"),
        ("unknown atom", sub("at: [key.membership.evicted, key.holder.thief]",
                             "at: [key.membership.evictd, key.holder.thief]"),
         "not in the catalogue version 3 vocabulary"),
        ("unreachable instant", sub("at: [key.membership.evicted, key.holder.thief]",
                                    "at: [key.membership.evicted, root.none]"),
         "at one instant"),
        ("card/requirements: different atoms", sub(
            "at: [key.membership.evicted, key.holder.thief]",
            "at: [key.membership.evicted, self.roster_size.three_more]"), "evicted_by_quorum atoms differ"),
        ("card/requirements: different probe", sub("expect: {proposals.0: null}", "expect: {proposals.0: 1}"),
         "evicted_by_quorum probe differs"),
        ("card/requirements: missing test", sub(
            "    evicted_by_quorum:\n      at: [key.membership.evicted, key.holder.thief]\n"
            "      probe: {node: A2, path: /v1/self/roster/proposals, status: 200, expect: {proposals.0: null}}\n", ""),
         "are in requirements.yaml and not on the card"),
        ("card/requirements: extra test", sub(
            "    evicted_by_quorum:\n",
            "    stolen:\n      at: [key.holder.thief]\n    evicted_by_quorum:\n"),
         "are on the card and not in requirements.yaml"),
        ("card/requirements: different topology", sub("topology: T10", "topology: T11"),
         "topology 'T11' on the card"),
        ("no catalogue_version", sub("  catalogue_version: 3\n", ""), "no `catalogue_version:`"),
        ("wrong catalogue_version", sub("  catalogue_version: 3\n", "  catalogue_version: 2\n"), "is not the pinned catalogue"),
        ("empty tests", clean.split("  tests:\n")[0] + "  tests: {}\n```\n", "is missing or empty"),
        ("building without derives", sub("stage: sketched", "stage: building"), "without `derives:`"),
        ("`on:` for node", sub("probe: {node: A2, path: /v1/self/roster/proposals, status: 200, expect: {proposals.0: null}}",
                               "probe: {on: A2, path: /v1/self/roster/proposals, status: 200, expect: {proposals.0: null}}"),
         "YAML reads as true"),
        ("keyed to another card", sub("\nCSD-095:\n", "\nCSD-094:\n"), "keyed CSD-094 but this card is CSD-095"),
        ("both block forms", clean + "\n```yaml csd:topology\ntopology: {roots: []}\n```\n", "never a topology block"),
    ]


def self_test() -> int:
    cat = load_catalogue()
    clean = FIXTURE.read_text(encoding="utf-8")
    failures = 0
    with tempfile.TemporaryDirectory(prefix="csd4-selftest-") as tmp:
        card = Path(tmp) / FIXTURE.name

        card.write_text(clean, encoding="utf-8")
        p, n, has = check_doc(card, cat)
        verdict = "green" if (has and not p) else "RED"
        print(f"  [{'ok' if verdict == 'green' else 'FAIL'}] clean fixture ({FIXTURE.relative_to(ROOT)}) -> {verdict}")
        for x in p + n:
            print(f"        {x}")
        failures += verdict != "green"

        for name, text, want in _plants(clean):
            card.write_text(text, encoding="utf-8")
            p, _, _ = check_doc(card, cat)
            hit = [x for x in p if want in x]
            ok = bool(hit)
            print(f"  [{'ok' if ok else 'FAIL'}] planted {name} -> {'red: ' + hit[0] if ok else 'NOT refused by name ' + repr(want) + ': ' + repr(p)}")
            failures += not ok

    # The catalogue itself: a tampered byte and an unparseable pin each refuse.
    for name, mutate, want in (
        ("tampered requirements.yaml", lambda d: (d / "requirements.yaml").write_text(
            (d / "requirements.yaml").read_text() + "\n# edited\n"), "PIN.md pins"),
        ("PIN.md with zero rows", lambda d: (d / "PIN.md").write_text("# nothing pinned\n"), "parsed ZERO"),
    ):
        with tempfile.TemporaryDirectory(prefix="csd4-selftest-") as tmp:
            d = Path(tmp)
            for f in (*PINNED_FILES, "PIN.md"):
                (d / f).write_bytes((CATALOGUE_DIR / f).read_bytes())
            mutate(d)
            try:
                load_catalogue(d)
                ok, msg = False, "loaded"
            except CatalogueError as e:
                ok, msg = want in str(e), str(e)
            print(f"  [{'ok' if ok else 'FAIL'}] planted {name} -> {'red: ' if ok else 'NOT refused: '}{msg}")
            failures += not ok

    print(f"check_csd4 --self-test: {'[OK] every plant red, the clean card green' if not failures else f'[FAIL] {failures} case(s)'}")
    return 1 if failures else 0


def main(argv: list[str]) -> int:
    if argv == ["--self-test"]:
        return self_test()
    try:
        cat = load_catalogue()
    except CatalogueError as e:
        print(f"  [FAIL] {e}")
        return 1
    print(f"CSD/4 catalogue v{cat['version']}: {len(cat['atoms'])} atoms, {len(cat['instants'])} entries, "
          f"{len(cat['requirements'])} requirement entries (packaging/csd4/PIN.md)")
    if argv == ["--verify-pin"]:
        print("  [OK] every vendored file matches its pin")
        return 0
    if not argv or any(a.startswith("-") for a in argv):
        print(__doc__)
        return 2
    rc, carried = 0, set()
    for a in argv:
        doc = Path(a)
        p, n, has = check_doc(doc, cat)
        if not has:
            continue
        carried.add(card_id(doc, doc.read_text(encoding="utf-8")))
        print(f"\n{doc}")
        for x in n:
            print(f"  [note] {x}")
        for x in p:
            print(f"  [FAIL] {x}")
        if not p:
            print("  [OK] topology, atoms, instants and requirements.yaml agree")
        rc |= bool(p)
    pending = sorted(set(cat["requirements"]) - carried)
    if pending:
        print(f"\n[info] requirements.yaml names {len(pending)} CSD id(s) whose card carries no csd:tests block "
              f"(not checked): {', '.join(pending)}")
    return rc


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
