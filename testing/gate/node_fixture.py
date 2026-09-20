"""A real ciris-server for a flow run, on this machine or a runner.

    python3 -m testing.gate.node_fixture --version v0.5.204 --home /tmp/n
    python3 -m testing.gate.node_fixture --binary ./node/ciris-server --home /tmp/n

THE SAME NODE THE GATE STANDS UP, available locally. `.github/actions/ciris-node`
does this in CI; a developer wanting to run a CSD flow on their own machine had
to reproduce it by hand, and every hand-reproduction is a chance to get one of
the two things wrong that took a day to find:

  * `--home`, NOT an env var. config.rs opens with "Server 0.5 — zero env vars.
    ciris-server boots with NO environment variables. The bootstrap floor is
    conventions + a single --home flag." CIRIS_HOME is the CLIENT's variable;
    exporting it here does nothing and the node writes to /var/lib/ciris, which
    no runner user and few laptops can create.
  * `/health` on **:4243**, not `/v1/system/health` on :8080. A bare node binds
    :4242 and :4243 and never the agent's port. Asking the wrong one reported
    "the node never became healthy" for months against a node that was serving.

`/health` serves a constant "ok" and is refused until the node is serving
(CIRISServer#548), so a 200 means serving with no intermediate state to miss.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import tarfile
import time
import urllib.error
import urllib.request
from pathlib import Path

#: The node's own port and liveness route. Not the agent's — see the module doc.
NODE_PORT = 4243
HEALTH = f"http://127.0.0.1:{NODE_PORT}/health"


class NodeUnavailable(RuntimeError):
    """No node could be stood up. Raised, never swallowed into a silent skip."""


def fetch(version: str, dest: Path, platform_slug: str = "aarch64-apple-darwin") -> Path:
    """Download and extract a released ciris-server. Returns the binary."""
    dest.mkdir(parents=True, exist_ok=True)
    asset = f"ciris-server-{version}-{platform_slug}.tar.gz"
    tarball = dest / asset
    if not tarball.exists():
        cmd = ["gh", "release", "download", version, "--repo", "CIRISAI/CIRISServer",
               "-p", asset, "-D", str(dest)]
        got = subprocess.run(cmd, capture_output=True, text=True)
        if got.returncode:
            raise NodeUnavailable(f"could not download {asset}: {got.stderr.strip()[:200]}")
    with tarfile.open(tarball) as tf:
        tf.extractall(dest)
    binary = dest / "ciris-server"
    if not binary.exists():
        raise NodeUnavailable(f"{asset} carried no ciris-server")
    binary.chmod(0o755)
    return binary


def serving() -> dict | None:
    """The node's health payload if it is serving, else None."""
    try:
        with urllib.request.urlopen(HEALTH, timeout=2) as r:
            return json.loads(r.read())
    except (urllib.error.URLError, OSError, json.JSONDecodeError):
        return None


def start(binary: Path, home: Path, log: Path, timeout: float = 120.0) -> subprocess.Popen:
    """Boot the node and block until it serves. Raises if it never does."""
    home.mkdir(parents=True, exist_ok=True)
    log.parent.mkdir(parents=True, exist_ok=True)

    if serving():
        raise NodeUnavailable(
            f"something is already serving {HEALTH} — refusing to start a second node "
            f"on one home, which is the CIRISClient#52 family"
        )

    handle = log.open("wb")
    proc = subprocess.Popen([str(binary), "--home", str(home)],
                            stdout=handle, stderr=subprocess.STDOUT)
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        # A DEAD PROCESS IS NOT A SLOW ONE. Without this the wait burns the whole
        # budget on a node that exited in the first second, and the log is the
        # only clue anyone gets.
        if proc.poll() is not None:
            raise NodeUnavailable(
                f"ciris-server exited with {proc.returncode} before serving; see {log}"
            )
        if (health := serving()) is not None:
            print(f"  node serving at {HEALTH} — {health.get('node', {}).get('standing')}, "
                  f"version {health.get('version')}")
            return proc
        time.sleep(1.0)
    proc.terminate()
    raise NodeUnavailable(f"node never served {HEALTH} within {timeout:.0f}s; see {log}")


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--version", help="a CIRISServer release tag to download")
    ap.add_argument("--binary", type=Path, help="an already-extracted ciris-server")
    ap.add_argument("--home", type=Path, required=True)
    ap.add_argument("--log", type=Path, default=Path("node.log"))
    ap.add_argument("--platform", default="aarch64-apple-darwin")
    args = ap.parse_args(argv)

    try:
        binary = args.binary or fetch(args.version, args.home.parent / "node", args.platform)
        proc = start(binary, args.home, args.log)
    except NodeUnavailable as e:
        # LOUD. A platform that cannot run FAILS; it never skips quietly.
        print(f"::error::{e}", file=sys.stderr)
        return 1
    print(f"  pid {proc.pid} — home {args.home}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
