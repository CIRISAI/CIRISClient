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
TYPES = {"string", "int", "float", "bool", "timestamp", "unconfirmed"}
VOCAB = re.compile(r"^[a-z0-9][a-z0-9_.-]*$")


def load_registry(path: Path) -> dict:
    raw = path.read_bytes()
    reg = json.loads(raw)
    return {
        "families": {f["prefix"]: f for f in reg["families"]},
        "sha256": reg["_meta"]["source_sha256"],
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

    if reached >= STAGES.index("building"):
        unconfirmed = [
            f.get("ceg", "?") for f in fields
            if "unconfirmed" in str(f.get("type", "")) or "unconfirmed" in str(f.get("range", ""))
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
        if base not in TYPES and not str(typ).startswith("enum"):
            problems.append(f"{ceg}: type {typ!r} is not a declared type")

        if "example" not in field:
            problems.append(f"{ceg}: no `example:` — a field with no sample output cannot be built from")

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
