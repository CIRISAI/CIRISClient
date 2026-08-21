#!/usr/bin/env python3
"""Validate compat/matrix.json — the published client↔node compatibility record.

    python3 compat/validate.py

Also importable: ``validate(repo_root) -> list[str]`` returns problems (empty =
valid), so the ``compat-matrix`` readiness gate asks the same question CI does
without a second implementation.

Checks: schema id; row shape (required fields, version syntax); exactly one row
for the current VERSION; no duplicate client_version; version ordering sane
(node_min <= node_max_tested); locale count is 29 (A4 of the FSD: the number is
normative); capabilities non-empty and kebab/dotted ids. Append-only-ness is a
property of history, not of one state — it is reviewed at the diff, which is
one line in a table, not something this can prove from a single checkout.

Stdlib only.
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

SCHEMA = "ciris-client-compat/v1"
_VER = re.compile(r"^\d+\.\d+\.\d+$")
_CAP = re.compile(r"^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)*$")

REQUIRED = (
    "client_version", "flavors", "node_min", "node_max_tested",
    "agent_min_tested", "capabilities", "spec_source", "locale_bundle", "notes",
)


def _vertuple(v: str) -> tuple[int, ...]:
    return tuple(int(x) for x in v.split("."))


def validate(repo_root: Path) -> list[str]:
    problems: list[str] = []
    path = repo_root / "compat" / "matrix.json"
    if not path.is_file():
        return [f"{path} does not exist"]
    try:
        doc = json.loads(path.read_text(encoding="utf-8"))
    except ValueError as e:
        return [f"matrix.json does not parse: {e}"]

    if doc.get("schema") != SCHEMA:
        problems.append(f"schema is {doc.get('schema')!r}, expected {SCHEMA!r}")

    rows = doc.get("rows")
    if not isinstance(rows, list) or not rows:
        # A matrix with no rows answers no question; refuse rather than pass
        # on an empty denominator.
        return problems + ["rows is empty or missing — a matrix that lists nothing certifies nothing"]

    seen: dict[str, int] = {}
    for i, row in enumerate(rows):
        where = f"rows[{i}]"
        missing = [k for k in REQUIRED if k not in row]
        if missing:
            problems.append(f"{where}: missing {missing}")
            continue
        cv = row["client_version"]
        if not _VER.match(cv):
            problems.append(f"{where}: client_version {cv!r} is not MAJOR.MINOR.PATCH")
            continue
        if cv in seen:
            problems.append(f"{where}: duplicate client_version {cv} (first at rows[{seen[cv]}])")
        seen[cv] = i
        for field in ("node_min", "node_max_tested"):
            if not _VER.match(row[field]):
                problems.append(f"{where}: {field} {row[field]!r} is not MAJOR.MINOR.PATCH")
        if _VER.match(row["node_min"]) and _VER.match(row["node_max_tested"]):
            if _vertuple(row["node_min"]) > _vertuple(row["node_max_tested"]):
                problems.append(f"{where}: node_min {row['node_min']} > node_max_tested {row['node_max_tested']}")
        if not row["capabilities"]:
            problems.append(f"{where}: capabilities is empty — name what the release ships or the row says nothing")
        else:
            for cap in row["capabilities"]:
                if not _CAP.match(cap):
                    problems.append(f"{where}: capability id {cap!r} is not a dotted lowercase id")
        flavors = row["flavors"]
        if not flavors or not set(flavors) <= {"node", "agent"}:
            problems.append(f"{where}: flavors {flavors!r} must be a non-empty subset of node/agent")
        langs = (row.get("locale_bundle") or {}).get("languages")
        if langs != 29:
            problems.append(f"{where}: locale_bundle.languages is {langs!r}; 29 is normative (FSD A4)")

    version = (repo_root / "VERSION").read_text(encoding="utf-8").strip()
    matches = [r for r in rows if isinstance(r, dict) and r.get("client_version") == version]
    if len(matches) != 1:
        problems.append(
            f"exactly one row must match VERSION ({version}); found {len(matches)} — "
            f"a release without its matrix row does not merge (FSD §6)"
        )
    return problems


def main() -> int:
    problems = validate(Path(__file__).resolve().parents[1])
    if problems:
        for p in problems:
            print(f"[FAIL] {p}")
        return 1
    print("[OK] compat/matrix.json is valid and carries the current VERSION row")
    return 0


if __name__ == "__main__":
    sys.exit(main())
