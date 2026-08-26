#!/usr/bin/env python3
"""Is the client tree the state VENDORING.md records, and is nothing in it
that must never be vendored?

    python3 packaging/check_vendoring.py            # check
    python3 packaging/check_vendoring.py --print    # print the current digest
    python3 packaging/check_vendoring.py --merged-ref CIRISServer   # that upstream's
                                                    # last merged commit, per §1

Since the 0.5.185 three-way merge (`client/VENDORING.md` §1) this tree is the
tree of record: changes are authored here and git history is the changelog.
What this guard asks changed with it, from "did anything drift from the vendor
snapshot" to two questions that stay true after the consumers flip:

1. **State digest.** The sha256-of-sha256s over every git-TRACKED file under
   ``client/`` (except VENDORING.md, which records the digest) must equal the
   digest §1 records. Any PR that touches ``client/`` re-records it in the same
   commit (`--print`), which forces the VENDORING.md diff — and therefore the
   provenance question — into review whenever the tree moves. Tracked files
   only: a local Gradle build must not turn the guard red (Codex, PR #1).

2. **Exclusion classes.** No tracked file under ``client/`` may be one of the
   things §2 exists to keep out: other repositories' release binaries
   (substrate wheels, jniLibs, xcframeworks, the iOS Resources tree), key
   material (``.ciris_keys/``, ``*secrets*key*``), compiled Python, or a
   developer's ``local.properties``. The CIRISServer tree tracked two
   ``secrets_master.key`` files at the time of the merge; the merge filtered
   them, and this is what keeps that class of mistake from arriving with a
   future pull.

Per-file hashes, not a set-of-names: a DECLARED file that changes without the
record changing is exactly the silent middle the old single-digest check could
not see (Codex, PR #1).

Stdlib + git only. No clone, no network, no build.
"""

from __future__ import annotations

import fnmatch
import hashlib
import re
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
VENDORING = REPO / "client" / "VENDORING.md"

_DIGEST_RE = re.compile(r"state digest:\**\s*`([0-9a-f]{64})`")

# The §1 provenance table: | `CIRISAI/X` | `vN` (`sha`) | `branch` | date |
_MERGED_ROW_RE = re.compile(
    r"^\|\s*`CIRISAI/(?P<repo>\w+)`\s*\|\s*`(?P<tag>[^`]+)`\s*\(`(?P<sha>[0-9a-f]{7,40})`\)",
    re.MULTILINE,
)


def merged_ref(repo: str) -> tuple[str, str]:
    """(tag, commit) that §1 records as `repo`'s last merged state.

    WHY A WORKFLOW WANTS THIS. The localization guard has to be pointed at the
    Rust sources that emit localized ids (`--server-src`), and CI first pinned
    that checkout to `v$(cat VERSION)`. That works only while the client's
    version trails the server's releases — and the client LEADS by design: 0.5.189
    exists here so CIRISServer 0.5.189 can pin a client that has the feature. The
    tag did not exist yet, so every workflow failed at checkout.

    This is the right pin and was all along: the commit the tree was actually
    merged against is the emitter set the bundle was reconciled with, it is
    recorded here already, and it cannot fail to exist because merging it is
    what put the row in the table.
    """
    for m in _MERGED_ROW_RE.finditer(VENDORING.read_text(encoding="utf-8")):
        if m.group("repo") == repo:
            return m.group("tag"), m.group("sha")
    sys.exit(
        f"[FAIL] client/VENDORING.md §1 records no merged state for CIRISAI/{repo}; "
        f"the provenance table is where that lives and a pull always updates it"
    )

# Tracked paths under client/ that must never exist. Mirrors VENDORING.md §2.
FORBIDDEN = (
    "client/androidApp/wheels/*",
    "client/androidApp/src/main/jniLibs/*",
    "client/androidApp/src/main/assets/bin/*",
    "client/iosApp/Resources/*",
    "client/iosApp/Resources.zip",
    "client/iosApp/Frameworks/*",
    "client/iosApp/app_packages_native/*",
    "client/*/.ciris_keys/*",
    "client/.ciris_keys/*",
    "*/__pycache__/*",
    "*.pyc",
    "*/local.properties",
    "*secrets*key*",
)


def tracked_files() -> list[str]:
    out = subprocess.run(
        ["git", "-C", str(REPO), "ls-files", "-z", "client"],
        capture_output=True, check=True,
    ).stdout
    files = sorted(p for p in out.decode().split("\0") if p)
    if not files:
        sys.exit("[FAIL] git ls-files found nothing under client/ — that cannot be right")
    return files


def recorded_digest() -> str:
    m = _DIGEST_RE.search(VENDORING.read_text(encoding="utf-8"))
    if not m:
        sys.exit("[FAIL] client/VENDORING.md §1 records no `state digest:`")
    return m.group(1)


def compute(files: list[str]) -> str:
    outer = hashlib.sha256()
    for rel in files:
        if rel == "client/VENDORING.md":
            continue
        inner = hashlib.sha256((REPO / rel).read_bytes()).hexdigest()
        outer.update(f"{inner}  {rel}\n".encode())
    return outer.hexdigest()


def forbidden_hits(files: list[str]) -> list[str]:
    return [
        rel for rel in files
        if any(fnmatch.fnmatch(rel, pat) for pat in FORBIDDEN)
    ]


def main() -> int:
    files = tracked_files()

    hits = forbidden_hits(files)
    if hits:
        print("[FAIL] tracked files under client/ match a never-vendor class (§2):")
        for h in hits:
            print(f"    {h}")
        print("  These are other repos' release binaries, key material, or local")
        print("  state. Remove them (git rm --cached) — rehydration paths are")
        print("  git-ignored on purpose.")
        return 1

    digest = compute(files)
    expected = recorded_digest()

    print("  vendoring check — client/ against its recorded state")
    print(f"    tracked files : {len(files)}")
    print(f"    digest        : {digest}")

    if digest == expected:
        print("\n[OK] the tree matches VENDORING.md §1, and no never-vendor class is present")
        return 0

    print(f"    expected      : {expected}")
    print(
        "\n[FAIL] client/ changed without VENDORING.md §1 re-recording it.\n"
        "  Update the `state digest:` line in the same commit:\n"
        "      python3 packaging/check_vendoring.py --print\n"
        "  That one line is what puts the tree's provenance into every review\n"
        "  that moves the tree."
    )
    return 1


if __name__ == "__main__":
    # Each query prints ONE line and exits. `$(… --merged-ref X)` is how a
    # workflow reads these, so anything else on stdout becomes part of the value.
    if "--merged-ref" in sys.argv:
        _, sha = merged_ref(sys.argv[sys.argv.index("--merged-ref") + 1])
        print(sha)
        sys.exit(0)
    if "--print" in sys.argv:
        print(compute(tracked_files()))
        sys.exit(0)
    sys.exit(main())
