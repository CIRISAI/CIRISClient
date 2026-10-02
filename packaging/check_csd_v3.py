#!/usr/bin/env python3
"""Validate a CSD/3 document's typed blocks against the CEG namespace registry.

    python3 packaging/check_csd_v3.py CSD.md [--registry manifests/namespace_registry.json]

A PROTOTYPE, AND IT SAYS SO. `check_csd.py` upstream owns the flow byte-identity
diff and the §1-§6 structure; this validates only what CSD/3 adds — the typed
`yaml csd:<section>` blocks, the stage machine, and the binding of every rendered
field to a constitutional family. It exists to demonstrate that v3's claims are
mechanically checkable rather than aspirational, which is the whole difference
between v3 and the version it supersedes.

WHY BLOCKS AND NOT PROSE. A check that reads a whole self-documenting file finds
the paragraph EXPLAINING a defect and reports the defect it documents. That
happened four separate times in this repo in one day — a version regex matched
the comment saying why the version had moved, a CIRIS_HOME guard passed on a
mention, an XCFramework task guard found the task name in the note about it, and
an automation-server guard matched a stub whose body was entirely comments. Typed
blocks make "what executes" and "what explains" different objects.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path

try:
    import yaml
except ImportError:  # pragma: no cover
    sys.exit("check_csd_v3 needs PyYAML")

BLOCK = re.compile(r"```yaml csd:([a-z_]+)\n(.*?)```", re.S)

#: The stage machine. Order matters: a stage requires everything before it.
STAGES = ["envisioned", "sketched", "building", "testable", "verified", "shipped"]

#: What must be present, per stage. Absence EARLIER than this is correct.
REQUIRED_AT = {
    "envisioned": ["stage"],
    "sketched": ["stage", "shows", "states"],
    "building": ["stage", "shows", "states"],
    "testable": ["stage", "shows", "states"],
    "verified": ["stage", "shows", "states"],
    "shipped": ["stage", "shows", "states"],
}

USES = {"read", "display-only", "emit"}
#: The standard's §2.1.1 set. `enum[…]` and `list[…]` are parameterised, so the
#: check is on the base name — and `list` was missing from the first version,
#: which failed two CSDs that were correct. A checker's own vocabulary drifting
#: from the standard it enforces is the same defect class it exists to catch.
TYPES = {"string", "int", "float", "bool", "timestamp", "unconfirmed", "enum", "list"}
#: An upstream blocker reference: `<Repo>#<n>`, e.g. `CIRISServer#676`.
BLOCKER = re.compile(r"^CIRIS[A-Za-z]+#[0-9]+$")
VOCAB = re.compile(r"^[a-z0-9][a-z0-9_.-]*$")


#: The keys `csd:surface` may carry. `scopes:` is F8 (one CSD, several circles).
SURFACE_KEYS = {"surface", "screen", "scopes", "flow_only", "entry", "exit"}

SCREEN_MEMBER = re.compile(r"^\s+(?:data\s+)?(?:object|class)\s+(\w+)", re.M)


def _screen_classes() -> set[str]:
    """Every member of `sealed class Screen` in CIRISApp.kt, read by brace-matching
    its body — a one-line regex missed multi-line declarations (`UserChat`)."""
    app = (Path(__file__).resolve().parents[1] / "client/shared/src/commonMain/kotlin/"
           "ai/ciris/mobile/shared/CIRISApp.kt")
    if not app.exists():
        return set()
    src = app.read_text()
    start = src.find("sealed class Screen")
    open_at = src.find("{", start)
    if start < 0 or open_at < 0:
        return set()
    depth, i = 0, open_at
    while i < len(src):
        depth += {"{": 1, "}": -1}.get(src[i], 0)
        if depth == 0:
            break
        i += 1
    return set(SCREEN_MEMBER.findall(src[open_at + 1:i]))


def grammar_sha256(reg: dict) -> str:
    """CIRISConstitution#112's pin: sha256 over the GRAMMAR — `families` plus
    `_meta` minus the prose hash, the pin itself and `cc_version` — as canonical
    JSON (sorted keys, no whitespace). Mirrors `tools/build_cc_namespace.py` at
    CC v1.0-rc6, so a wording edit in Part 3 does not move every card's pin."""
    meta = {k: v for k, v in reg["_meta"].items()
            if k not in ("source_sha256", "registry_sha256", "cc_version")}
    body = json.dumps({"_meta": meta, "families": reg["families"]},
                      sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(body.encode("utf-8")).hexdigest()


def load_registry(path: Path) -> dict:
    raw = path.read_bytes()
    reg = json.loads(raw)
    meta = reg["_meta"]
    # rc6+ carries `registry_sha256` (the grammar hash, what a CSD pins). A
    # registry that claims one it does not hash to is refused outright: the pin
    # every card is compared against would otherwise be whatever the file says.
    # rc5 and earlier have only the prose hash, which was the convention then.
    if "registry_sha256" in meta:
        got = grammar_sha256(reg)
        if got != meta["registry_sha256"]:
            sys.exit(f"{path}: _meta.registry_sha256 {meta['registry_sha256'][:12]}… "
                     f"but the grammar hashes to {got[:12]}… — refusing a registry that misstates its own pin")
        pin = meta["registry_sha256"]
    else:
        pin = meta["source_sha256"]
    return {
        "families": {f["prefix"]: f for f in reg["families"]},
        "sha256": pin,
        "private": reg["_meta"]["private_use_prefix"],
        "vocab": re.compile(reg["_meta"]["case_rule"]["vocab_pattern"]),
        "refusal": reg["_meta"]["case_rule"]["refusal_token"],
    }


def _family_for(ceg: str, reg: dict) -> tuple[dict | None, dict[str, str]]:
    """Resolve a `ceg:` id to its family, plus the placeholder binds it filled.

    A parameterised family is written instantiated — `capacity_assurance:witness:
    {domain}:{band}:v1` — so this matches segment by segment against the
    registry's declared shape rather than by string equality.
    """
    fams = reg["families"]
    if ceg in fams:
        return fams[ceg], {}
    parts = ceg.split(":")
    for prefix, fam in fams.items():
        segs = fam["segments"]
        if len(segs) != len(parts):
            continue
        binds: dict[str, str] = {}
        ok = True
        for seg, got in zip(segs, parts):
            name = seg["segment"]
            if seg["class"] == "literal":
                if name != got:
                    ok = False
                    break
            elif seg["class"] == "wildcard":
                continue
            else:
                binds[name.strip("{}")] = got
        if ok:
            return fam, binds
    return None, {}


#: CSD/4 `csd:topology` — the layers CIRISServer `FSD/TOPOLOGY.md` §2 names,
#: plus `checks:` (FSD/CSD4_EVALUATION.md §1.3). An unknown layer fails for the
#: same reason an unknown `csd:surface` key does: an unread key is a silent no.
TOPOLOGY_LAYERS = {"roots", "canonicals", "nodes", "persons", "relations", "actor", "negatives", "checks"}
#: TOPOLOGY.md §2.5's relations (CIRISServer v0.5.218, 405acc17), plus
#: `community`, which CSD4_EVALUATION §4.1 declares as LACKING and the builder
#: refuses by name. `custody` and `session` arrived with 0.5.218.
TOPOLOGY_RELATIONS = {
    "peered", "rooted_with", "reachable", "contact", "room", "message", "file",
    "member", "quorum_change", "community", "custody", "session",
}
#: TOPOLOGY.md §2.6's negatives (v0.5.218; `no_wider_self_rows` listed there).
TOPOLOGY_NEGATIVES = {"cannot_list_room", "holds_no_row", "no_wider_self_rows"}
#: Named by a CSD before TOPOLOGY.md on the server's main lists them — which is
#: not the same as unbuilt: an entry may already run in the server's builder on
#: a branch, and its note says where. Accepted
#: so a CSD can state the fixture it needs; each entry says who asked and where
#: the server stands. Remove an entry when TOPOLOGY.md on main lists it.
TOPOLOGY_PENDING: dict[str, str] = {}


def check_topology(topo: object) -> list[str]:
    """Structural checks on a CSD/4 `csd:topology` block — NOT realizability.

    What this asserts: the block is `topology:` with only known layers; every
    id a layer references (`holds`, `accepts`, `dials`, `owns`, `between`,
    `person`, `device`, the actor) is declared; each relation and negative is
    one TOPOLOGY.md names or one listed in TOPOLOGY_PENDING; and rule 2 (a
    person accepts a root only if every node they own does). What it does NOT:
    rules 1 and 3-7, which are the builder's (`harness/native`, CIRISServer),
    and whether the server can build the block today.
    """
    problems: list[str] = []
    if not isinstance(topo, dict) or not isinstance(topo.get("topology"), dict):
        return ["topology: the block must be a mapping under `topology:`"]
    t = topo["topology"]
    unknown = sorted(set(t) - TOPOLOGY_LAYERS)
    if unknown:
        problems.append(f"topology: unknown layer(s) {unknown} — known: {sorted(TOPOLOGY_LAYERS)}")

    def _rows(layer: str) -> list[dict]:
        rows = t.get(layer) or []
        if not isinstance(rows, list) or not all(isinstance(r, dict) for r in rows):
            problems.append(f"topology: `{layer}` must be a list of mappings")
            return []
        return rows

    roots = {r.get("id") for r in _rows("roots")}
    canon = {r.get("id"): r for r in _rows("canonicals")}
    nodes = {r.get("id"): r for r in _rows("nodes")}
    persons = {r.get("id"): r for r in _rows("persons")}
    every_node = set(canon) | set(nodes)

    def _ref(where: str, got: object, pool: set, what: str) -> None:
        for g in got if isinstance(got, list) else [got]:
            if g not in pool:
                problems.append(f"topology: {where} names {what} {g!r}, which no layer declares")

    for cid, c in canon.items():
        _ref(f"canonical {cid}", c.get("holds"), roots, "root")
    for nid, n in nodes.items():
        _ref(f"node {nid}", n.get("dials", []), every_node, "node")
        _ref(f"node {nid}", n.get("accepts"), roots, "root")
    for pid, p in persons.items():
        _ref(f"person {pid}", p.get("owns", []), set(nodes), "node")
        _ref(f"person {pid}", p.get("accepts"), roots, "root")
        # Realizability rule 2 (TOPOLOGY.md §3).
        if p.get("accepts") in roots:
            bad = [o for o in p.get("owns", []) if o in nodes and nodes[o].get("accepts") != p["accepts"]]
            if bad:
                problems.append(
                    f"topology: person {pid} accepts {p['accepts']!r} but owns {bad}, which do not "
                    f"— rule 2: a person accepts a root only if every node they own does"
                )

    for r in _rows("relations"):
        rel = r.get("rel")
        if rel not in TOPOLOGY_RELATIONS and rel not in TOPOLOGY_PENDING:
            problems.append(f"topology: relation {rel!r} is not in TOPOLOGY.md §2.5 nor declared pending")
        if "between" in r:
            _ref(f"relation {rel}", r["between"], every_node, "node")
        if "person" in r:
            _ref(f"relation {rel}", r["person"], set(persons), "person")
        if "device" in r:
            _ref(f"relation {rel}", r["device"], set(nodes), "node")
            if r.get("person") in persons and r["device"] not in persons[r["person"]].get("owns", []):
                problems.append(f"topology: relation {rel} puts person {r['person']!r} on {r['device']!r}, which they do not own")

    for n in _rows("negatives"):
        check_name = n.get("check")
        if check_name not in TOPOLOGY_NEGATIVES and check_name not in TOPOLOGY_PENDING:
            problems.append(f"topology: negative {check_name!r} is not in TOPOLOGY.md §2.6 nor declared pending")
        if "person" in n:
            _ref(f"negative {check_name}", n["person"], set(persons), "person")

    actor = t.get("actor")
    if actor is not None:
        if not isinstance(actor, dict):
            problems.append("topology: `actor` must be {person, device}")
        else:
            _ref("actor", actor.get("person"), set(persons), "person")
            _ref("actor", actor.get("device"), set(nodes), "node")
            p = persons.get(actor.get("person"))
            if p is not None and actor.get("device") not in p.get("owns", []):
                problems.append(f"topology: the actor's device {actor.get('device')!r} is not one {actor.get('person')!r} owns")
    return problems


def check(doc: Path, reg: dict) -> list[str]:
    text = doc.read_text(encoding="utf-8")
    blocks: dict[str, object] = {}
    problems: list[str] = []

    for name, body in BLOCK.findall(text):
        try:
            blocks[name] = yaml.safe_load(body)
        except yaml.YAMLError as e:
            problems.append(f"csd:{name} does not parse: {e}")

    stage_block = blocks.get("stage") or {}
    stage = (stage_block or {}).get("stage")
    if stage not in STAGES:
        problems.append(f"stage {stage!r} is not one of {STAGES}")
        return problems

    for need in REQUIRED_AT[stage]:
        if need not in blocks:
            problems.append(
                f"stage {stage!r} requires a `csd:{need}` block and there is none — "
                f"an advance is refused rather than inferred"
            )

    # THE STAGE MACHINE IS ABOUT CONTENT, NOT ABOUT BLOCKS BEING PRESENT.
    #
    # The first version of this checked only that the required blocks existed,
    # so advancing `sketched` -> `building` with every contract still
    # `unconfirmed` passed — the one thing the stage machine exists to prevent,
    # unenforced by the checker written to enforce it. Caught by its own
    # negative test, which is the only reason it is here.
    reached = STAGES.index(stage)
    fields = (blocks.get("shows") or {}).get("fields", []) or []

    def _is_unconfirmed(f: dict) -> bool:
        return "unconfirmed" in str(f.get("type", "")) or "unconfirmed" in str(f.get("range", ""))

    # `blocked_by:` separates "someone asked and the answer was no" from
    # "someone asked and stopped". Without it both read as `unconfirmed`, and the
    # more thoroughly a team chases its gaps to named upstream issues, the more
    # its cards look abandoned. A blocker must be a real reference, and it must
    # still describe a gap: one left on a field that has since been confirmed is
    # stale, and stale is exactly the drift this checker exists to catch.
    for f in fields:
        if "blocked_by" not in f:
            continue
        refs = f["blocked_by"] if isinstance(f["blocked_by"], list) else [f["blocked_by"]]
        bad = [r for r in refs if not BLOCKER.match(str(r).strip())]
        if not refs or bad:
            problems.append(
                f"{f.get('ceg', '?')}: blocked_by must name an issue as <Repo>#<n> "
                f"(e.g. CIRISServer#676); got {bad or refs!r}"
            )
        if not _is_unconfirmed(f):
            problems.append(
                f"{f.get('ceg', '?')}: blocked_by on a field that is no longer unconfirmed "
                f"is stale: remove it, or the field is not confirmed after all"
            )

    if reached >= STAGES.index("building"):
        # A field the substrate has definitively declined, with the issue that
        # says so, does not hold the card back from `building`. One nobody
        # chased still does.
        unconfirmed = [
            f.get("ceg", "?") for f in fields
            if _is_unconfirmed(f) and not (reached == STAGES.index("building") and f.get("blocked_by"))
        ]
        if unconfirmed:
            problems.append(
                f"stage {stage!r} requires the substrate to have answered, and these are "
                f"still unconfirmed: {unconfirmed}. At `sketched` that is correct; here it "
                f"means someone stopped, which is the distinction this stage machine makes."
            )

    if reached >= STAGES.index("testable"):
        proposed = [f.get("ceg", "?") for f in fields if str(f.get("tag", "")).startswith("proposed:")]
        if proposed:
            problems.append(
                f"stage {stage!r} requires every tag to be real, and these are still "
                f"proposed: {proposed}. A proposed tag may never enter a flow."
            )

    shows = (blocks.get("shows") or {})
    pinned = shows.get("registry_sha256")
    if pinned and pinned != reg["sha256"]:
        problems.append(
            f"registry_sha256 {pinned[:12]}… does not match the registry validated "
            f"against ({reg['sha256'][:12]}…) — a bump must be a deliberate edit"
        )

    for field in shows.get("fields", []) or []:
        ceg = field.get("ceg")
        tag = field.get("tag", "<no tag>")
        if not ceg:
            problems.append(f"{tag}: no `ceg:` — every rendered value names its family")
            continue

        if ceg.startswith(reg["private"]):
            fam, binds = None, {}
        else:
            fam, binds = _family_for(ceg, reg)
            if fam is None:
                problems.append(
                    f"{ceg}: not in the registry and not {reg['private']}… — "
                    f"an unregistered bare prefix is refused"
                )
                continue

        for placeholder, got in binds.items():
            if not reg["vocab"].match(got):
                problems.append(
                    f"{ceg}: segment {got!r} for {{{placeholder}}} fails vocab_pattern "
                    f"— {reg['refusal']}"
                )

        use = field.get("use")
        if use not in USES:
            problems.append(f"{ceg}: use {use!r} is not one of {sorted(USES)}")
        elif use == "emit" and fam and fam.get("reserved"):
            rule = (fam.get("reserved_rule") or {}).get("rule", "reserved")
            ref = (fam.get("reserved_rule") or {}).get("cc_ref", "")
            problems.append(
                f"{ceg}: use: emit on a RESERVED family — {rule} ({ref}). "
                f"Owned by {fam.get('owning_component')}/{fam.get('owning_repo')}."
            )

        typ = field.get("type")
        base = str(typ).split("[")[0] if typ else None
        if base not in TYPES:
            problems.append(f"{ceg}: type {typ!r} is not a declared type")

        if "example" not in field:
            problems.append(f"{ceg}: no `example:` — a field with no sample output cannot be built from")

    # THE SURFACE MUST BE REACHABLE, and the client answers that — not the CSD.
    # A CSD naming a surface the sidebar cannot reach fails here rather than at
    # 2am against a timeout, and the hop itself is never written down: it is
    # derived from the client's own tag rules (testing/gate/nav_map.py).
    surface = blocks.get("surface") or {}
    # AN UNKNOWN KEY IS A FAILURE, NOT A NO-OP. `screen_class:` was briefly the
    # convention for flow-only screens; it is not a key this checker reads, so it
    # made `screen` None and skipped both checks below. A CSD claiming Nodes is
    # Screen.Telemetry passed that way, and so did the typo `screeen:`.
    unknown = sorted(set(surface) - SURFACE_KEYS)
    if unknown:
        problems.append(
            f"surface: unknown key(s) {unknown} — known: {sorted(SURFACE_KEYS)}. "
            f"An unread key silently disables the reachability check"
        )
    if surface.get("flow_only"):
        # A screen no sidebar row reaches (Startup, Login, Setup, a claim, a
        # ceremony): it must exist, must NOT be sidebar-reachable, and must say
        # how a person arrives, since no hop can.
        screen = surface.get("screen")
        if not screen:
            problems.append("surface: flow_only needs `screen:` — the Screen class the flow lands on")
        elif screen not in _screen_classes():
            problems.append(f"surface: Screen.{screen} is not declared in CIRISApp.kt")
        if not str(surface.get("entry") or "").strip():
            problems.append("surface: flow_only needs `entry:` — how a person reaches this screen")
    if surface:
        try:
            sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
            from testing.gate import nav_map  # noqa: PLC0415 — optional, repo-local
            hops = nav_map.build()
        except Exception as e:  # noqa: BLE001
            problems.append(f"surface: could not derive the nav map ({type(e).__name__}: {e})")
        else:
            screen = surface.get("screen")
            sid = surface.get("surface")
            if surface.get("flow_only"):
                if screen and screen in hops:
                    problems.append(
                        f"surface: Screen.{screen} is sidebar-reachable via {hops[screen][-1]!r}, "
                        f"so it is not flow_only — name its surface instead"
                    )
            elif screen and screen not in hops:
                problems.append(
                    f"surface: no sidebar route to Screen.{screen} — a flow starting "
                    f"there cannot be reached, so the CSD cannot be tested"
                )
            elif screen and sid:
                # A surface's chain ends on its row — or on its tab, when it is
                # the tab's only card and the shell shows it directly.
                want = nav_map.expected_tail(sid)
                if want is None or hops[screen][-1] != want:
                    problems.append(
                        f"surface: {sid!r} derives {want!r} but Screen.{screen} is reached "
                        f"via {hops[screen][-1]!r} — the surface id and the screen disagree"
                    )

    if "topology" in blocks:
        problems.extend(check_topology(blocks["topology"]))

    states = blocks.get("states") or {}
    if states:
        for required in ("populated", "empty", "loading", "error"):
            if required not in states:
                problems.append(
                    f"states: {required!r} is missing — all four are required, and "
                    f"`error` must be distinguishable from `empty`"
                )
    return problems


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("document", type=Path)
    ap.add_argument("--registry", type=Path, required=True)
    args = ap.parse_args(argv)

    reg = load_registry(args.registry)
    problems = check(args.document, reg)

    print(f"\n{args.document}  (registry {reg['sha256'][:12]}…)")
    if not problems:
        print("  [OK] every typed block validates against the registry")
        return 0
    for p in problems:
        print(f"  [FAIL] {p}")
    return 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
