"""ONE agent version per with-AI QA run, from ONE place (CIRISClient#166's rule, for the brain).

    python3 -m testing.gate.agent_version                          # the latest CIRISAgent release
    python3 -m testing.gate.agent_version --override 2.16.2        # a dispatch input
    python3 -m testing.gate.agent_version --github-output "$GITHUB_OUTPUT" --json

WHY A SECOND RESOLVER. The release gate for a client is "an enhanced
five-platform run that uses the LATEST agent". `pip install ciris-agent` with no
pin would let every with-AI leg read PyPI at its own minute — the same moving
target `server_version.py` exists to stop for the node (run 39023854089: two
servers in one green matrix). So the version is decided once, in the `server`
job, and every with-AI leg installs exactly `ciris-agent==<pypi>`.

WHAT "LATEST" MEANS. CIRISAgent's latest GitHub release (`v2.16.2-stable`) —
not PyPI's newest upload, which can be a build the agent has not released — and
it must EXIST on PyPI under the release's version (`2.16.2`), or there is
nothing to install and the run fails here, in seconds, rather than on three
runners after their builds.

THE AGENT BRINGS ITS OWN NODE. `ciris-agent` depends on an exact
`ciris-server==` (2.16.2 → 0.5.229) and folds that node into its own process —
it binds :4243 itself, so a with-AI leg starts no standalone node. That pin is
read from the release's PyPI metadata and printed on the board beside the run's
server version, exactly as the iOS leg prints the node it embeds: neither is
claimed for the other.

Stdlib plus `gh` for the one GitHub read; `resolve()` takes the reads as values
so the decision is unit-tested without a network.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Optional, Sequence

AGENT_REPO = "CIRISAI/CIRISAgent"
PYPI_NAME = "ciris-agent"

#: A release tag as CIRISAgent writes it (`v2.16.2-stable`), or the PyPI form a
#: person types (`2.16.2`, `v2.16.2`). Nothing else names an installable build.
VERSION_RE = re.compile(r"^v?(\d+\.\d+\.\d+)(?:-stable)?$")
#: The node the agent wheel pins, from its PyPI `requires_dist`.
SERVER_PIN_RE = re.compile(r"^ciris-server\s*==\s*(\d+\.\d+\.\d+)\s*(?:;.*)?$")

INPUT, LATEST = "input", "latest-release"


class ResolveError(Exception):
    """The run cannot name one installable agent. The workflow must FAIL on it."""


@dataclass(frozen=True)
class AgentResolution:
    tag: str        # the GitHub release tag: v2.16.2-stable (or the input as typed)
    pypi: str       # what `pip install ciris-agent==` takes: 2.16.2
    server: str     # the ciris-server the agent pins and folds in: 0.5.229
    source: str     # INPUT | LATEST


def pypi_version(tag: str) -> str:
    """`v2.16.2-stable` -> `2.16.2`, or ResolveError for anything that is not one."""
    m = VERSION_RE.match((tag or "").strip())
    if not m:
        raise ResolveError(f"agent version {tag!r} is not a release (want v2.16.2-stable or 2.16.2)")
    return m.group(1)


def server_pin(requires_dist: Sequence[str]) -> str:
    pins = [m.group(1) for r in requires_dist or () if (m := SERVER_PIN_RE.match(r.strip()))]
    if len(pins) != 1:
        raise ResolveError(
            f"{PYPI_NAME}'s metadata pins ciris-server {len(pins)} times ({pins}); the board "
            f"cannot name the node the agent folds in")
    return pins[0]


def resolve(*, override: Optional[str], latest: Callable[[], str],
            pypi_meta: Callable[[str], Optional[dict]]) -> AgentResolution:
    """Decide the run's agent, raising ResolveError when there is none.

    `latest()` is CIRISAgent's latest release tag — read ONLY without an
    override. `pypi_meta(version)` is that version's PyPI `info` (None when PyPI
    has no such release)."""
    if override and override.strip():
        tag, source = override.strip(), INPUT
    else:
        tag, source = latest().strip(), LATEST
    version = pypi_version(tag)
    info = pypi_meta(version)
    if info is None:
        raise ResolveError(
            f"{PYPI_NAME}=={version} (from {tag}) is not on PyPI — nothing a leg could install")
    if str(info.get("version", "")).strip() != version:
        raise ResolveError(f"PyPI answered {info.get('version')!r} for {PYPI_NAME}=={version}")
    return AgentResolution(tag, version, server_pin(info.get("requires_dist") or ()), source)


# ── the two reads ───────────────────────────────────────────────────────────

def gh_latest() -> str:
    p = subprocess.run(["gh", "api", f"repos/{AGENT_REPO}/releases/latest", "--jq", ".tag_name"],
                       capture_output=True, text=True)
    if p.returncode != 0:
        raise ResolveError(f"could not read {AGENT_REPO}'s latest release: {p.stderr.strip()}")
    return p.stdout.strip()


def pypi_info(version: str) -> Optional[dict]:
    url = f"https://pypi.org/pypi/{PYPI_NAME}/{version}/json"
    try:
        with urllib.request.urlopen(url, timeout=30) as r:
            return json.loads(r.read().decode("utf-8")).get("info") or {}
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return None
        raise ResolveError(f"PyPI {url}: HTTP {e.code}") from e
    except (urllib.error.URLError, OSError, ValueError) as e:
        raise ResolveError(f"PyPI {url}: {e}") from e


def main(argv: Optional[Sequence[str]] = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--override", default="",
                    help="the workflow_dispatch `agent_version` input; empty means the latest release")
    ap.add_argument("--github-output", type=Path,
                    help="append agent_tag=/agent_pypi=/agent_server=/agent_source= here ($GITHUB_OUTPUT)")
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args(argv)
    try:
        r = resolve(override=args.override, latest=gh_latest, pypi_meta=pypi_info)
    except ResolveError as e:
        print(f"::error::{e}", file=sys.stderr)
        return 1
    if args.github_output:
        with args.github_output.open("a", encoding="utf-8") as fh:
            for k, v in (("agent_tag", r.tag), ("agent_pypi", r.pypi),
                         ("agent_server", r.server), ("agent_source", r.source)):
                fh.write(f"{k}={v}\n")
    if args.json:
        print(json.dumps({"tag": r.tag, "pypi": r.pypi, "server": r.server, "source": r.source}))
    else:
        print(r.pypi)
    return 0


if __name__ == "__main__":
    sys.exit(main())
