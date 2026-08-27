"""
Nodes for the client to be a client OF.

The client is meant to be complete against a node that is LOCAL or REMOTE, and
that is CARRYING A BRAIN or not. Those are two independent axes and the client
resolves them by two different mechanisms, so a harness that only ever points
the app at one auto-started node tests one corner of four and calls it done.

**Location** — is the mechanism in `PythonRuntime.desktop.startServer()`. The
app probes its configured URL first and only launches `ciris-server` if nothing
answers. So the axis is not "which URL" but "who started the node":

  local   nothing pre-booted, `ciris-server` on PATH  -> the app launches it,
          and the claim-PIN capture path runs for real
  remote  a node already answering, `CIRIS_API_URL` set -> the app must connect
          without launching, and must never try

**Brain** — is `ClientMode`, derived from `/v1/system/health` (CIRISServer#390):
AGENT iff the node reports a `cognitive_state`, a non-empty service map, or an
answering folded brain; NODE otherwise; and folded-but-not-answering is
UNDETERMINED — a retry signal the client must not latch.

A real bare node gives us NODE for free: the released binary boots in ~2s and
reports `role: fabric-node`, `agent: {folded:false, reachable:false}`, no
`cognitive_state`. A real AGENT would need a brain and an LLM bill, and it
still could not produce UNDETERMINED on demand — that state is a race. So the
brain axis is served by [BrainFacade]: a proxy in front of the real node that
rewrites ONLY `/v1/system/health`, exactly as the contract documents it, and
passes every other route through to the real node untouched. The client's mode
gate reads the contract; this drives the contract; nothing is stubbed that the
client actually calls.

ONE CORNER IS NOT REACHABLE HERE, and is not faked: local x agent. The released
node binds 4242/4243 with no port override (`ciris-server [--home <path>]
[--key-id <name>]` is the whole usage), so the facade cannot sit where a
self-launched node must be. Folding a real brain onto a local node is the
downstream mobile/manual test; see testing/README.md.
"""

from __future__ import annotations

import json
import os
import shutil
import socket
import subprocess
import threading
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

# The released node's fixed ports; see the module docstring.
NODE_LISTEN_PORT = 4242
NODE_API_PORT = 4243
LOCAL_NODE_URL = f"http://127.0.0.1:{NODE_API_PORT}"

# What a folded, answering brain adds to the node's health. Every key here is
# read by `clientModeFrom`; nothing is decorative.
BRAIN_MERGE = {
    "cognitive_state": "WORK",
    "role": "agent",
    "services": {
        f"service_{i:02d}": {"healthy": True, "status": "ok"} for i in range(22)
    },
    "agent": {"folded": True, "reachable": True},
}

# Folded but not answering: the UNDETERMINED verdict. No cognitive_state, no
# services -- the two positive signals are absent, so a client that latches
# NODE here has the bug this corner exists to catch.
UNREACHABLE_BRAIN_MERGE = {
    "agent": {"folded": True, "reachable": False},
}

# A configured brain's setup status.
#
# NOT decoration, and not a shortcut: `clientModeFrom` demotes an answering
# brain to NODE when it reports itself unconfigured (CIRISAgent#1075), and the
# real node behind this facade is a fresh one that says `setup_required: true`.
# A facade that folds a brain onto /v1/system/health and leaves setup saying
# "not set up yet" is presenting an INCOHERENT node, and the correct client
# verdict for it is NODE -- so the agent corner would have failed a blameless
# client. An agent presents both, or it is not an agent.
CONFIGURED_SETUP = {
    "setup_required": False,
    "has_env_file": True,
    "is_first_run": False,
    "config_exists": True,
}

# Per-corner rewrites, keyed by the route they apply to.
NODE_REWRITES: dict[str, dict] = {}
AGENT_REWRITES = {
    "/v1/system/health": BRAIN_MERGE,
    "/v1/setup/status": CONFIGURED_SETUP,
}
# Folded, CONFIGURED, and not answering yet.
#
# The setup rewrite is load-bearing, and leaving it out is a mistake this
# harness made and its own run caught. `undetermined` is
#
#     agentFolded && !agentReachable && !brainUnconfigured && !declaredAgent
#
# so a facade that folds an unreachable brain onto a node still reporting
# `setup_required: true` is not presenting the undetermined state at all -- it
# is presenting an unconfigured brain, for which NODE is the CORRECT verdict
# (CIRISAgent#1075). The corner failed a blameless client until the node it
# presented was coherent. What this presents now is the real race: the fold
# boots the brain on a daemon thread after the node composes, so a probe can
# legitimately see folded=true/reachable=false on a fully configured home.
UNDETERMINED_REWRITES = {
    "/v1/system/health": UNREACHABLE_BRAIN_MERGE,
    "/v1/setup/status": CONFIGURED_SETUP,
}


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return int(s.getsockname()[1])


def _health_ok(url: str, timeout: float = 2.0) -> bool:
    try:
        with urllib.request.urlopen(f"{url}/v1/system/health", timeout=timeout) as r:
            return r.status == 200
    except Exception:
        return False


def wait_until_up(url: str, timeout: float = 90.0) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if _health_ok(url):
            return
        time.sleep(0.5)
    raise RuntimeError(f"node at {url} never became healthy within {timeout:.0f}s")


def wait_until_down(url: str, timeout: float = 30.0) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if not _health_ok(url, timeout=1.0):
            return
        time.sleep(0.5)
    raise RuntimeError(f"something is still answering at {url} after {timeout:.0f}s")


@dataclass
class RealNode:
    """A real released `ciris-server`, run against a throwaway home."""

    binary: str
    home: Path
    key_id: str = "ciris-client"
    proc: subprocess.Popen | None = None
    log: Path | None = None

    @property
    def url(self) -> str:
        return LOCAL_NODE_URL

    def start(self) -> "RealNode":
        # REFUSE to adopt a node we did not start. The port is fixed at 4243 and
        # a leftover node from an earlier run answers exactly like a fresh one --
        # so without this the suite silently drives a foreign node, with foreign
        # state, and goes green. Found by this fixture's own smoke test.
        if _health_ok(self.url, timeout=1.5):
            raise RuntimeError(
                f"something is ALREADY answering at {self.url}. Refusing to start, "
                f"because a run against a node this fixture does not own proves "
                f"nothing. Stop it first:  pkill -f 'ciris-server [-]-home'"
            )
        self.home.mkdir(parents=True, exist_ok=True)
        self.log = self.home.parent / "node.log"
        with open(self.log, "wb") as fh:
            self.proc = subprocess.Popen(
                [self.binary, "--home", str(self.home), "--key-id", self.key_id],
                stdout=fh,
                stderr=subprocess.STDOUT,
            )
        try:
            wait_until_up(self.url)
        except RuntimeError:
            raise RuntimeError(
                f"node did not come up; last log lines:\n{self.tail()}"
            ) from None
        return self

    def tail(self, n: int = 30) -> str:
        if not self.log or not self.log.exists():
            return "<no log>"
        return "\n".join(self.log.read_text("utf-8", "replace").splitlines()[-n:])

    def claim_pin(self, timeout: float = 20.0) -> str | None:
        """
        The one-time claim PIN, from the node's own home.

        Bounded retry, not a single read: the node writes this file DURING boot,
        and health goes green before it lands -- a single read a moment after
        startup returns None for a PIN that is about to exist. This is the same
        race `PythonRuntime.readLocalClaimPin` retries 20 times to close, and a
        fixture that reads once reproduces the bug rather than testing around it.
        """
        f = self.home / "claim_pin"
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if f.exists():
                pin = f.read_text().strip()
                if pin:
                    return pin
            time.sleep(0.5)
        return None

    def stop(self) -> None:
        if not self.proc:
            return
        self.proc.terminate()
        try:
            self.proc.wait(timeout=15)
        except subprocess.TimeoutExpired:
            self.proc.kill()
            self.proc.wait(timeout=10)
        self.proc = None
        # The next corner binds this port; "terminate() returned" is not the
        # same claim as "the listener is gone".
        wait_until_down(self.url, timeout=30)


class _FacadeHandler(BaseHTTPRequestHandler):
    """Proxy everything; rewrite the routes the mode gate reads."""

    rewrites: dict = {}
    upstream: str = LOCAL_NODE_URL

    def log_message(self, fmt: str, *args) -> None:  # quiet
        pass

    def _proxy(self, method: str) -> None:
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length else None
        req = urllib.request.Request(f"{self.upstream}{self.path}", data=body, method=method)
        for h in ("Authorization", "Content-Type", "Accept"):
            if self.headers.get(h):
                req.add_header(h, self.headers[h])
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                status, payload = r.status, r.read()
                ctype = r.headers.get("Content-Type", "application/json")
        except urllib.error.HTTPError as e:
            status, payload = e.code, e.read()
            ctype = e.headers.get("Content-Type", "application/json")
        except Exception as e:
            status, payload, ctype = 502, json.dumps({"error": str(e)}).encode(), "application/json"

        # Merged into `data`, leaving every other field the real node reported
        # exactly as it reported it. Only the routes the mode gate reads.
        merge = next(
            (m for route, m in self.rewrites.items() if self.path.startswith(route)), None
        )
        if status == 200 and merge:
            try:
                doc = json.loads(payload)
                data = doc.get("data")
                if isinstance(data, dict):
                    data.update(merge)
                    payload = json.dumps(doc).encode()
            except (json.JSONDecodeError, AttributeError):
                pass  # not JSON we understand; pass the node's own answer through

        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self) -> None:
        self._proxy("GET")

    def do_POST(self) -> None:
        self._proxy("POST")

    def do_PUT(self) -> None:
        self._proxy("PUT")

    def do_DELETE(self) -> None:
        self._proxy("DELETE")


@dataclass
class BrainFacade:
    """A remote node URL that presents a chosen brain verdict, coherently."""

    rewrites: dict
    upstream: str = LOCAL_NODE_URL
    port: int = 0
    _srv: ThreadingHTTPServer | None = None
    _thread: threading.Thread | None = None

    @property
    def url(self) -> str:
        return f"http://127.0.0.1:{self.port}"

    def start(self) -> "BrainFacade":
        self.port = self.port or free_port()
        handler = type(
            "Handler", (_FacadeHandler,), {"rewrites": self.rewrites, "upstream": self.upstream}
        )
        self._srv = ThreadingHTTPServer(("127.0.0.1", self.port), handler)
        self._thread = threading.Thread(target=self._srv.serve_forever, daemon=True)
        self._thread.start()
        wait_until_up(self.url, timeout=20)
        return self

    def stop(self) -> None:
        if self._srv:
            self._srv.shutdown()
            self._srv.server_close()
            self._srv = None


def find_node_binary(explicit: str | None = None) -> str:
    """
    The released `ciris-server`, wherever CI or a developer put it.

    An explicit choice that does not exist is an ERROR, never a fallback. There
    is more than one thing called `ciris-server` in this ecosystem -- the Rust
    node this client drives, and a Python console script with a different CLI --
    so silently resolving a bad `--node-bin` to whatever is on PATH runs the
    whole suite against the wrong program and reports on it as if it were right.
    Caught by this harness's own mutation test.
    """
    for label, cand in (("--node-bin", explicit), ("CIRIS_SERVER_BIN", os.environ.get("CIRIS_SERVER_BIN"))):
        if cand:
            if Path(cand).is_file():
                return str(Path(cand).resolve())
            raise RuntimeError(
                f"{label}={cand!r} does not exist. Refusing to fall back to "
                f"PATH: the wrong `ciris-server` would run the whole suite and "
                f"report on it as though it were the right one."
            )
    found = shutil.which("ciris-server")
    if found:
        return found
    raise RuntimeError(
        "no `ciris-server` found. Download one from the CIRISServer releases:\n"
        "  gh release download <tag> -R CIRISAI/CIRISServer \\\n"
        "     -p 'ciris-server-<tag>-x86_64-unknown-linux-gnu.tar.gz'\n"
        "then set CIRIS_SERVER_BIN to the extracted binary, or put it on PATH."
    )
