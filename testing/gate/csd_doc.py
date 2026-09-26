"""The half of a CSD a flow needs: its `shows:` fields and its `states:` tags.

A flow in `testing/flows/` names its CSD (`csd: CSD-005`). This module finds that
document and reads the two typed blocks the runner cannot work without:

  * `csd:shows`  -> `field_tags`, the `ceg:` field id -> the tag drawing it. A
    `relation:` operand is a field id, so without this a flow can only relate
    boxes rather than constitutional values (CSD/3 §3).
  * `csd:states` -> `state_tags`, state -> tag. `state: empty` is asserted as
    "that state's tag is on screen, and every other state's tag is not" — so an
    error cannot pass for an empty list, which is the one confusion CSD/3 §2.2
    makes mandatory to prevent.

It also records which tags are still `proposed:`, because a flow may not drive or
assert a tag no client has shipped: that would fail as "element not found", which
is indistinguishable from a broken app.

ONE BLOCK GRAMMAR. The fenced-block pattern is `packaging/check_csd_v3.py`'s own
`BLOCK`, loaded from that file rather than re-typed here, so the checker and the
runner cannot disagree about what counts as a typed block. (It is loaded by path:
`import packaging` would find the PyPI package of that name first.)

EVERY FAILURE IS A LOAD ERROR. A CSD that is missing, ambiguous or does not parse
raises `CsdError` — never a silent skip, because a flow that quietly loses its CSD
loses the checks that make its `state:` and `relation:` mean anything.
"""

from __future__ import annotations

import importlib.util
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, Optional, Set

import yaml

#: Where this repo keeps its CSDs.
DEFAULT_CSD_ROOT = Path(__file__).resolve().parents[2] / "FSD" / "CSD"
_CHECKER = Path(__file__).resolve().parents[2] / "packaging" / "check_csd_v3.py"
_ID = re.compile(r"^CSD-\d{3}$")
PROPOSED = "proposed:"


class CsdError(Exception):
    """The CSD a flow names cannot be used. Always a load error."""


def _block_pattern() -> "re.Pattern[str]":
    spec = importlib.util.spec_from_file_location("_check_csd_v3", _CHECKER)
    if spec is None or spec.loader is None:  # pragma: no cover — the file is in the tree
        raise CsdError(f"cannot load the CSD block grammar from {_CHECKER}")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod.BLOCK


BLOCK = _block_pattern()


@dataclass
class CsdDoc:
    csd_id: str
    path: Path
    stage: Optional[str]
    #: `ceg:` field id -> tag. A parameterised id is keyed both as written
    #: (`consent:{kind}`) and as bound (`consent:replication`).
    field_tags: Dict[str, str] = field(default_factory=dict)
    #: state name -> tag, for the states that name one.
    state_tags: Dict[str, str] = field(default_factory=dict)
    #: Every tag the CSD marks `proposed:` (prefix stripped).
    proposed: Set[str] = field(default_factory=set)
    #: Field ids whose tag is still proposed.
    proposed_fields: Set[str] = field(default_factory=set)
    #: State names whose tag is still proposed.
    proposed_states: Set[str] = field(default_factory=set)


def _bound(ceg: str, bind: Dict[str, str]) -> str:
    out = ceg
    for k, v in bind.items():
        out = out.replace("{" + str(k) + "}", str(v))
    return out


def parse(path: Path, csd_id: str = "") -> CsdDoc:
    """Read one CSD's typed blocks. Raises CsdError on anything unusable."""
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as e:
        raise CsdError(f"{path}: cannot be read ({e})") from e

    blocks: Dict[str, object] = {}
    for name, body in BLOCK.findall(text):
        try:
            blocks[name] = yaml.safe_load(body)
        except yaml.YAMLError as e:
            raise CsdError(f"{path}: its `csd:{name}` block does not parse: {e}") from e
    if "stage" not in blocks:
        # Every CSD/3 document has one; a file without it is not a CSD, and a
        # flow bound to it would be bound to prose.
        raise CsdError(f"{path}: no `yaml csd:stage` block — not a CSD/3 document")

    doc = CsdDoc(csd_id=csd_id or path.stem, path=path,
                 stage=(blocks.get("stage") or {}).get("stage"))

    shows = blocks.get("shows") or {}
    if not isinstance(shows, dict):
        raise CsdError(f"{path}: `csd:shows` is not a mapping")
    for i, f in enumerate(shows.get("fields") or []):
        if not isinstance(f, dict) or not f.get("ceg"):
            raise CsdError(f"{path}: csd:shows field[{i}] has no `ceg:`")
        raw_tag = str(f.get("tag") or "")
        if not raw_tag:
            continue
        proposed = raw_tag.startswith(PROPOSED)
        tag = raw_tag[len(PROPOSED):] if proposed else raw_tag
        ids = {str(f["ceg"]), _bound(str(f["ceg"]), f.get("bind") or {})}
        for fid in ids:
            doc.field_tags[fid] = tag
            if proposed:
                doc.proposed_fields.add(fid)
        if proposed:
            doc.proposed.add(tag)

    states = blocks.get("states") or {}
    if not isinstance(states, dict):
        raise CsdError(f"{path}: `csd:states` is not a mapping")
    for name, row in states.items():
        raw_tag = str((row or {}).get("tag") or "") if isinstance(row, dict) else ""
        if not raw_tag:
            continue
        if raw_tag.startswith(PROPOSED):
            doc.proposed.add(raw_tag[len(PROPOSED):])
            doc.proposed_states.add(str(name))
            continue  # a proposed state tag cannot be asserted; see flow_spec
        doc.state_tags[str(name)] = raw_tag
    return doc


def load(csd_id: str, root: Optional[Path] = None) -> CsdDoc:
    """Find `CSD-NNN-*.md` under `root` and parse it."""
    if not isinstance(csd_id, str) or not _ID.match(csd_id):
        raise CsdError(f"`csd: {csd_id!r}` is not a CSD id; write it as e.g. `CSD-005`")
    root = Path(root) if root else DEFAULT_CSD_ROOT
    hits = sorted(root.glob(f"{csd_id}-*.md"))
    if not hits:
        raise CsdError(f"{csd_id}: no {csd_id}-*.md under {root} — a flow cannot name a CSD that does not exist")
    if len(hits) > 1:
        raise CsdError(f"{csd_id}: ambiguous, {len(hits)} documents match: {[h.name for h in hits]}")
    return parse(hits[0], csd_id)
