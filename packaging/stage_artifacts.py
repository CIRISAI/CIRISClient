#!/usr/bin/env python3
"""Stage Gradle output into the ciris_client package, then write its manifest.

Kotlin is not compiled inside pip. The Gradle job builds the client, this script
copies what it produced into ``ciris_client/_artifacts/`` and records what it
copied, and only then is the wheel built. Three steps, each of which can fail in
its own right, instead of one that needs a JDK and an Android SDK on every
consumer's machine.

    # after ./gradlew -p client :desktopApp:packageUberJarForCurrentOS -PhasAgent=true
    python3 packaging/stage_artifacts.py \
        --artifact desktop-uber-jar@linux-x86_64=client/desktopApp/build/compose/jars/*.jar

    # no Gradle run available — build a wheel that REFUSES rather than one that lies
    python3 packaging/stage_artifacts.py --placeholder "no gradle job"

``--flavor`` records WHICH build produced the staged jar (it lands in the
manifest, and `has_agent` with it). It no longer selects a destination: one
client ships, carrying the agent surfaces, gated at runtime on the probed node.

Stdlib only, on purpose: this runs between a Gradle job and a pip build, before
anything is installed.
"""

from __future__ import annotations

import argparse
import datetime
import glob
import hashlib
import json
import os
import shutil
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
FLAVORS = ("node", "agent")
SCHEMA = "ciris-client-artifacts/v1"

# PyPI's per-file limit. Stated as bytes because "100 MB" is 100 MiB and the
# difference has been the whole margin before now.
PYPI_LIMIT_BYTES = 104_857_600


def payload_dir() -> Path:
    return REPO / "ciris_client" / "_artifacts"


def read_version() -> str:
    version = (REPO / "VERSION").read_text(encoding="utf-8").strip()
    if not version:
        sys.exit("VERSION is empty — refusing to stamp a bundle with no version")
    return version


def vendoring() -> dict[str, str]:
    """Pull the vendored commit out of VENDORING.md so it rides in the manifest.

    An artifact that cannot say which client source it was built from is an
    artifact nobody can bisect. Parsed rather than duplicated, so there is one
    place to update when the tree is re-vendored.
    """
    text = (REPO / "client" / "VENDORING.md").read_text(encoding="utf-8")
    out: dict[str, str] = {}
    for line in text.splitlines():
        if line.startswith("| **Commit** |"):
            out["commit"] = line.split("|")[2].strip().strip("`")
        elif line.startswith("| **Source repo** |"):
            cell = line.split("|")[2].strip()
            out["repo"] = cell.split("`")[1] if "`" in cell else cell
        if len(out) == 2:
            break
    missing = {"commit", "repo"} - out.keys()
    if missing:
        # AGENTS.md, Gate Rules: a parser that finds nothing where the construct
        # plainly exists must fail loudly rather than emit a manifest that is
        # quietly missing HALF its provenance — a commit with no repo does not
        # identify which repository the bundled source came from.
        sys.exit(
            f"could not read {sorted(missing)} from client/VENDORING.md §1 — "
            f"both the Source repo and Commit rows are required"
        )
    return out


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def resolve(pattern: str) -> Path:
    matches = sorted(glob.glob(pattern, recursive=True))
    files = [Path(m) for m in matches if Path(m).is_file()]
    if not files:
        sys.exit(f"no file matched {pattern!r} — did the Gradle task actually run?")
    if len(files) > 1:
        sys.exit(f"{pattern!r} matched {len(files)} files, expected 1: {files}")
    return files[0]


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument(
        "--flavor",
        default="agent",
        choices=FLAVORS,
        help="which build produced this jar — recorded in the manifest, not a "
             "destination. Defaults to the shipped build (agent: every surface "
             "present, gated at runtime).",
    )
    ap.add_argument(
        "--artifact",
        action="append",
        default=[],
        metavar="KIND[@PLATFORM]=PATH",
        help="artifact to stage, e.g. desktop-uber-jar@linux-x86_64=client/.../"
             "ciris.jar (globs ok). A platform-specific artifact staged without "
             "its @PLATFORM ships silently broken to every other OS — the "
             "desktop uber-jar embeds compose.desktop.currentOs.",
    )
    ap.add_argument(
        "--placeholder",
        metavar="REASON",
        help="stage nothing; write a manifest that makes every lookup raise",
    )
    args = ap.parse_args()

    if bool(args.artifact) == bool(args.placeholder):
        return int(bool(sys.stderr.write(
            "pass either --artifact (one or more) or --placeholder REASON, not both\n"
        ))) or 2

    dest = payload_dir()
    if dest.exists():
        shutil.rmtree(dest)
    dest.mkdir(parents=True)
    (dest / ".gitkeep").touch()

    version = read_version()
    manifest = {
        "schema": SCHEMA,
        "flavor": args.flavor,
        "has_agent": args.flavor == "agent",
        "client_version": version,
        "vendored_from": vendoring(),
        "built": {
            "at": datetime.datetime.now(datetime.timezone.utc).isoformat(
                timespec="seconds"
            ),
            "github_run": os.environ.get("GITHUB_RUN_ID"),
            "github_sha": os.environ.get("GITHUB_SHA"),
        },
        "artifacts": [],
    }

    if args.placeholder:
        manifest["placeholder"] = True
        manifest["placeholder_reason"] = args.placeholder
        print(f"[placeholder] {args.flavor}: {args.placeholder}")
    else:
        total = 0
        for spec in args.artifact:
            if "=" not in spec:
                sys.exit(f"--artifact wants KIND=PATH, got {spec!r}")
            kind_spec, _, pattern = spec.partition("=")
            kind, _, plat = kind_spec.partition("@")
            src = resolve(pattern)
            target = dest / src.name
            shutil.copy2(src, target)
            size = target.stat().st_size
            total += size
            entry = {
                "kind": kind,
                "path": src.name,
                "bytes": size,
                "sha256": sha256(target),
            }
            if plat:
                entry["platform"] = plat
            manifest["artifacts"].append(entry)
            tag = f"{kind}@{plat}" if plat else kind
            print(f"[staged] {tag:<22} {src}  ({size / 1048576:.2f} MiB)")

        if total > PYPI_LIMIT_BYTES:
            sys.exit(
                f"staged payload is {total:,} bytes, over PyPI's "
                f"{PYPI_LIMIT_BYTES:,}-byte limit before compression. "
                f"Localization is the product and is not what gets cut — "
                f"split a flavor or a target instead."
            )

    (dest / "manifest.json").write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    print(f"[manifest] {dest / 'manifest.json'}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
