"""A second ciris-server beside the leg's node, seeded to CIRISServer's chat scenario.

    # stand a peer up next to a node the client already claimed, seed it, print the values
    python3 -m testing.gate.two_node up --binary node/ciris-server \\
        --node-url http://127.0.0.1:4243 --username qaadmin --password 'QaAdmin!2345' \\
        --work "$RUNNER_TEMP/peer" --values peer.json
    # tear it down — kill the process, delete its home — even after a failed run
    python3 -m testing.gate.two_node down --work "$RUNNER_TEMP/peer"

PORTED FROM CIRISServer `harness/mesh-repro/` (`scenarios/chat.sh`,
`chat_drive.py`, `node_boot.sh`), WITHOUT DOCKER. That harness runs every node in
a container on one compose bridge, which only the Linux leg can host: macOS
runners have no Docker, Windows runners run Windows containers, and neither the
Android emulator nor the iOS simulator can see a compose network. So the peer
here is a NATIVE PROCESS on the leg's host, from the same `ciris-server` binary
the leg already downloaded, and everything the server harness did in a
container's shell is done here in stdlib Python — which is the only thing all
three runner images share.

WHAT IS THE SAME AS THE SERVER HARNESS, and why each piece is kept:

  * `config set net.listen_addr` / `net.bootstrap_peers` OFFLINE, before the
    first boot (node_boot.sh). Both are boot-structural signed config rows; set
    over HTTP after boot they would not be read until a restart. There is no
    port flag, so this is also how the peer gets its own ports.
  * `identity create` then `claim --cohort-scope self` on the node's own
    console, with the one-time PIN read from `<home>/claim_pin` — the harness
    standing in for an operator at the console. The claim answers the owner
    session and the owner's fed-ID, so no password round-trip is needed.
  * `POST /v1/federation/announce` right after the claim. Setup-complete binds
    the owner at `cohort_scope: self`, which no peer may hold; without the
    announce neither node can place the other in a room's audience and every
    chat row is withheld (measured 2026-09-03 in the server harness: 15
    withheld, 0 delivered). Loopback-only, which is fine: the fixture runs on
    the host both nodes serve on.
  * `POST /v1/federation/peering` both ways with the operator's prefix set,
    handing over each node's `self-key-record`.
  * The owner key crosses by replication, waited for and RECORDED; the room is
    opened by both sides and the message is sent only once the room is keyed.
  * The owner→node BINDING is waited for too, and it is a different, later
    thing than the key. CIRISServer `FSD/TOPOLOGY.md` (chore/adopt-edge-v33)
    §2.5 defines the relation this fixture must realise before the room:
    `reachable(A, q) >= n` — "`POST /v1/contacts` on A for q reports
    `reachable_nodes >= n` (q's owner→node binding held on A at federation
    scope); the gate CIRISServer#699 needs" — and §3 rule 5 says it needs one
    of q's nodes `announced: true`, which the announce above is. A contact
    added while `reachable_nodes=0` keys a pair room whose bodies read
    `not_granted` for good (#699: reproduced 3/3, fixed-by-ordering 2/2); the
    2026-09-29 matrix logged exactly `reachable_nodes=0` on both sides, then
    `awaiting_peer` for the whole 150 s. So [add_contact] re-asks the POST —
    the route TOPOLOGY names as the predicate; no read route answers it —
    until the count is >= 1, bounded, and records what it waited on and for
    how long. The two direct peers also satisfy §2.3: scoped content (chat
    bodies) reaches DIRECTLY-ATTACHED peers only (CC 5.4.6), and the peer
    dials the leg's node.

WHAT IS DIFFERENT, and why:

  * No canonical. The server harness needs one as the dial target and as the
    non-member its `dark` stage interrogates; this fixture seeds state for a
    CLIENT to render, so the peer dials the leg's node directly.
  * No `test-anchor` routes. `test-blessed-self-record` / `test-admit-peer` are
    compiled only into the harness build (`#[cfg(feature = "test-anchor")]`);
    the released binary a leg downloads does not serve them. Peering uses the
    production `GET /v1/federation/self-key-record` instead, and a peer whose
    owner key never replicates is added by its NODE key — recorded as
    `contact_via: node`, never passed off as the person.
  * The leg's own node is claimed by the CLIENT (session_fixture drives the
    wizard). The fixture signs in to it with the same credentials
    (`POST /v1/auth/login`) rather than claiming it a second time.

REACHABILITY PER LEG. The fixture talks to both nodes from the leg's host; the
client under test talks only to ITS node, and never needs to reach the peer:

  * Linux / macOS / Windows desktop — the leg's node is the host's
    127.0.0.1:4243; the peer is 127.0.0.1:5243.
  * Android emulator — the client reaches the host's node through
    `adb reverse tcp:4243` (bringup.py). The emulator never dials the peer; if a
    flow ever needs it to, it is `adb reverse tcp:5243` (or 10.0.2.2:5243).
  * iOS simulator — the app embeds its own node on 4242/4243 of the loopback it
    SHARES with the host, so from here it is still 127.0.0.1:4243, and the peer
    on 5242/5243 does not collide with it.

The two NODES talk to each other on host loopback, which is what peering needs.

ISOLATION (the one-home rule). The peer gets its own `--home` under the work
directory, a unique `--key-id`, and ports 5242/5243. It never touches the
leg's node's home, a developer's ~/ciris, or 4242/4243/8080. `down` kills the
process by the pidfile it wrote and deletes the home, and `TwoNodeFixture` is a
context manager so the flow runner tears it down in a `finally`.

WHAT IT DOES NOT GUARANTEE. Cross-node chat delivery depends on the server's
replication plane; the fixture waits a bounded time for the message to cross and
REPORTS whether it did (`message_arrived`). A flow that names
`${MESSAGE_ATTESTATION_ID}` when it did not cross fails naming the reason — it is
not given a value that would make a local-only row look delivered.
"""

from __future__ import annotations

import argparse
import atexit
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional, Tuple

from testing.gate.console import utf8_console

#: The flow-level key a flow sets to ask for this fixture (`fixture: two_node`).
FIXTURE = "two_node"

#: The peer's transport port; its read API is the next port, by the node's own
#: convention (the leg's node is 4242 / 4243). NOT 4242/4243: the leg's node,
#: and on iOS the app's embedded node, hold those.
PEER_PORT = 5242

#: The leg's node, as the client under test sees it on every leg (module doc).
DEFAULT_NODE_URL = "http://127.0.0.1:4243"

#: What each node consents to replicate to the other — the server harness's
#: `PEER_PREFIXES`, verbatim. `self:delegates_to:` carries the owner-bindings,
#: without which neither side can resolve who speaks for whom beyond one hop.
PEER_PREFIXES = ["capacity:", "chat:", "self:delegates_to:", "trace:"]

#: The message the peer sends. Distinct per run so a stale row cannot pass.
DEFAULT_MESSAGE = "two-node fixture: hello from the peer"

HTTP_TIMEOUT = 30.0


class FixtureUnavailable(RuntimeError):
    """The fixture could not be stood up. Raised, never swallowed into a skip."""


# ── command building (pure; unit-tested) ────────────────────────────────────


def resolve_binary(path: Path) -> Path:
    """The binary as given, or with `.exe` — Windows legs extract ciris-server.exe,
    every other leg has no suffix (the same rule .github/actions/ciris-node uses)."""
    path = Path(path)
    if path.is_file():
        return path
    exe = path.with_name(path.name + ".exe")
    if exe.is_file():
        return exe
    raise FixtureUnavailable(f"no ciris-server at {path} (or {exe.name})")


def unique_key_id(prefix: str = "ciris-gate-peer") -> str:
    """A key id no other node on this host uses. The identity lands in
    `<home>/identity/` under this alias, so two runs never share one."""
    return f"{prefix}-{uuid.uuid4().hex[:8]}"


def transport_of(read_url: str) -> str:
    """`http://127.0.0.1:4243` -> `127.0.0.1:4242`: a node's transport is one port
    below its read API. `net.bootstrap_peers` parses a SocketAddr, so this must
    be an IP and a port — a hostname is skipped with a warning and dials nothing
    (the server harness's docker-compose.chat.yml header)."""
    m = re.match(r"^https?://([^/:]+):(\d+)/?$", read_url.strip())
    if not m:
        raise ValueError(f"not a node read-API url with an explicit port: {read_url!r}")
    host = "127.0.0.1" if m.group(1) == "localhost" else m.group(1)
    return f"{host}:{int(m.group(2)) - 1}"


@dataclass
class PeerSpec:
    """Everything that decides the peer's command lines."""

    binary: Path
    home: Path
    key_id: str
    port: int = PEER_PORT
    host: str = "127.0.0.1"
    bootstrap_peers: List[str] = field(default_factory=list)

    @property
    def listen_addr(self) -> str:
        return f"{self.host}:{self.port}"

    @property
    def read_url(self) -> str:
        return f"http://{self.host}:{self.port + 1}"

    def _node(self) -> List[str]:
        return ["--home", str(self.home), "--key-id", self.key_id]

    def config_commands(self) -> List[List[str]]:
        """The offline `config set` calls, in order. The value is JSON (the CLI
        parses it), so the address is a quoted JSON string."""
        cmds = [[str(self.binary), "config", "set", "net.listen_addr",
                 json.dumps(self.listen_addr), *self._node()]]
        if self.bootstrap_peers:
            cmds.append([str(self.binary), "config", "set", "net.bootstrap_peers",
                         json.dumps(self.bootstrap_peers), *self._node(),
                         "--reason", "client gate two-node fixture"])
        return cmds

    def serve_command(self) -> List[str]:
        return [str(self.binary), *self._node()]

    def identity_command(self) -> List[str]:
        # NO --label: `claim` re-opens the signer under the conventional alias
        # (`<key-id>-user`), so a label would mint a keyset the claim cannot find.
        return [str(self.binary), "identity", "create", "--backend", "software", *self._node()]

    def claim_command(self, node_code: str, claim_pin: str) -> List[str]:
        return [str(self.binary), "claim", "--backend", "software", *self._node(),
                "--node-code", node_code, "--claim-pin", claim_pin,
                "--cohort-scope", "self", "--target-url", self.read_url]


def parse_claim_output(text: str) -> Tuple[str, str]:
    """(access_token, owner fed-ID) from `ciris-server claim`'s stdout, which is
    log lines followed by one JSON object. Raises if either is missing."""
    start = text.find("\n{")
    blob = text[start + 1:] if start >= 0 else (text if text.lstrip().startswith("{") else "")
    try:
        body = json.loads(blob.strip()) if blob.strip() else {}
    except ValueError:
        body = {}
    token, owner = body.get("access_token") or "", body.get("identity_key_id") or ""
    if not token or not owner:
        raise FixtureUnavailable(f"claim did not yield a session: {text.strip()[-300:]!r}")
    return token, owner


# ── HTTP ────────────────────────────────────────────────────────────────────


def http(method: str, url: str, token: Optional[str] = None, body: Any = None,
         timeout: float = HTTP_TIMEOUT) -> Tuple[int, Any]:
    """One call -> (status, parsed-or-raw body). Never raises on an HTTP status:
    a refusal is data here. A transport failure is status 0."""
    data, headers = None, {}
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw, status = resp.read().decode(), resp.status
    except urllib.error.HTTPError as e:
        raw, status = e.read().decode(errors="replace"), e.code
    except Exception as e:  # noqa: BLE001 — transport failure is an outcome
        return 0, {"transport_error": f"{type(e).__name__}: {e}"}
    try:
        return status, json.loads(raw)
    except ValueError:
        return status, raw


def _ok(status: int) -> bool:
    return 200 <= status < 300


def wait_healthy(read_url: str, timeout: float, proc: Optional[subprocess.Popen] = None,
                 log: Optional[Path] = None) -> None:
    """`/health` on the read API answers only once the node serves (CIRISServer#548)."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        # A DEAD PROCESS IS NOT A SLOW ONE (node_fixture.py).
        if proc is not None and proc.poll() is not None:
            raise FixtureUnavailable(f"the peer exited with {proc.returncode} before serving; see {log}")
        status, _ = http("GET", f"{read_url}/health", timeout=3)
        if status == 200:
            return
        time.sleep(1.0)
    raise FixtureUnavailable(f"the peer never served {read_url}/health within {timeout:.0f}s; see {log}")


# ── the peer process ────────────────────────────────────────────────────────


@dataclass
class Party:
    """One node as the seeding sees it: where it answers and who owns it."""

    name: str
    url: str
    token: str
    owner_key_id: str = ""
    node_key_id: str = ""
    record: Any = None
    announce: Dict[str, Any] = field(default_factory=dict)


class PeerNode:
    """The second node: configured offline, booted, claimed, announced."""

    def __init__(self, spec: PeerSpec, work: Path) -> None:
        self.spec = spec
        self.work = Path(work)
        self.log = self.work / "peer.log"
        self.pidfile = self.work / "peer.pid"
        self.proc: Optional[subprocess.Popen] = None
        self._log_handle = None

    def _run(self, cmd: List[str], what: str, timeout: float = 120.0) -> str:
        got = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        with self.log.open("a", encoding="utf-8") as fh:
            fh.write(f"\n$ {' '.join(cmd)}\n{got.stdout}\n{got.stderr}\n")
        if got.returncode:
            raise FixtureUnavailable(f"{what} exited {got.returncode}: {got.stderr.strip()[-300:]}")
        return got.stdout

    def start(self, timeout: float = 120.0) -> None:
        self.work.mkdir(parents=True, exist_ok=True)
        self.spec.home.mkdir(parents=True, exist_ok=True)
        status, _ = http("GET", f"{self.spec.read_url}/health", timeout=2)
        if status:
            raise FixtureUnavailable(
                f"something already answers on {self.spec.read_url} — refusing to start a "
                f"second node on its ports")
        for cmd in self.spec.config_commands():
            self._run(cmd, f"config set {cmd[3]}")
        self._log_handle = self.log.open("ab")
        kwargs: Dict[str, Any] = {}
        if os.name == "nt":
            kwargs["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP  # type: ignore[attr-defined]
        else:
            kwargs["start_new_session"] = True
        self.proc = subprocess.Popen(self.spec.serve_command(), stdout=self._log_handle,
                                     stderr=subprocess.STDOUT, **kwargs)
        self.pidfile.write_text(f"{self.proc.pid}\n{self.spec.home}\n", encoding="utf-8")
        wait_healthy(self.spec.read_url, timeout, self.proc, self.log)

    def claim(self, pin_timeout: float = 90.0) -> Party:
        """The console claim, as the server harness does it on each container."""
        pin_file = self.spec.home / "claim_pin"
        deadline = time.monotonic() + pin_timeout
        while not pin_file.is_file() and time.monotonic() < deadline:
            time.sleep(1.0)
        if not pin_file.is_file():
            raise FixtureUnavailable(f"no claim PIN at {pin_file} — the peer never armed first-run setup")
        pin = pin_file.read_text(encoding="utf-8").strip()
        status, body = http("GET", f"{self.spec.read_url}/v1/federation/node-code")
        code = body.get("code") if isinstance(body, dict) else None
        if not code:
            raise FixtureUnavailable(f"GET /v1/federation/node-code on the peer answered {status}: {body!r}")
        self._run(self.spec.identity_command(), "identity create")
        token, owner = parse_claim_output(self._run(self.spec.claim_command(code, pin), "claim"))
        return Party("peer", self.spec.read_url, token, owner_key_id=owner)

    def stop(self, keep_home: bool = False) -> None:
        if self.proc is not None:
            _terminate(self.proc.pid, self.proc)
            self.proc = None
        if self._log_handle is not None:
            self._log_handle.close()
            self._log_handle = None
        self.pidfile.unlink(missing_ok=True)
        if not keep_home:
            shutil.rmtree(self.spec.home, ignore_errors=True)


def _terminate(pid: int, proc: Optional[subprocess.Popen] = None, grace: float = 10.0) -> None:
    """TERM, wait, KILL. By pid so `down` can do it from another process."""
    try:
        if os.name == "nt":
            subprocess.run(["taskkill", "/PID", str(pid), "/T", "/F"], capture_output=True)
        else:
            os.kill(pid, signal.SIGTERM)
    except (ProcessLookupError, PermissionError, OSError):
        return
    deadline = time.monotonic() + grace
    while time.monotonic() < deadline:
        if proc is not None:
            if proc.poll() is not None:
                return
        elif not _alive(pid):
            return
        time.sleep(0.3)
    try:
        if os.name != "nt":
            os.kill(pid, signal.SIGKILL)
        if proc is not None:
            proc.wait(timeout=5)
    except (ProcessLookupError, OSError, subprocess.TimeoutExpired):
        pass


def _alive(pid: int) -> bool:
    if os.name == "nt":
        got = subprocess.run(["tasklist", "/FI", f"PID eq {pid}"], capture_output=True, text=True)
        return str(pid) in got.stdout
    try:
        os.kill(pid, 0)
    except (ProcessLookupError, PermissionError):
        return False
    try:  # a zombie of ours still answers kill(0); reap it
        done, _ = os.waitpid(pid, os.WNOHANG)
        return done == 0
    except ChildProcessError:
        return True


def down(work: Path) -> str:
    """Kill the peer a previous `up` left, by its pidfile, and delete its home."""
    pidfile = Path(work) / "peer.pid"
    if not pidfile.is_file():
        return "no peer pidfile — nothing running from this work dir"
    lines = pidfile.read_text(encoding="utf-8").splitlines()
    pid, home = int(lines[0]), (Path(lines[1]) if len(lines) > 1 else None)
    _terminate(pid)
    pidfile.unlink(missing_ok=True)
    if home is not None:
        shutil.rmtree(home, ignore_errors=True)
    return f"stopped pid {pid}, removed {home}"


# ── seeding: the chat scenario, over HTTP ───────────────────────────────────


@dataclass
class FixtureValues:
    """What the seeding produced, for flows to name as `${NAME}`.

    Empty strings mean "not produced"; `notes` says why, and the substitution
    quotes it when a flow names a value that is empty."""

    peer_url: str = ""
    peer_node_key_id: str = ""
    peer_owner_key_id: str = ""
    #: The key the leg's node holds the contact under — the person when their
    #: key crossed, else the peer node (`contact_via`). The row tags are built
    #: from THIS (`contacts_row_<key>`, `btn_receipt_<key>`).
    peer_key_id: str = ""
    contact_via: str = ""
    peer_contact_code: str = ""
    local_owner_key_id: str = ""
    local_node_key_id: str = ""
    room_id: str = ""
    message_text: str = ""
    message_attestation_id: str = ""
    message_arrived: bool = False
    notes: List[str] = field(default_factory=list)

    def as_vars(self) -> Dict[str, str]:
        """`${NAME}` -> value. Only non-empty values: an absent key is what lets
        the substitution say WHY a value is missing instead of typing ''."""
        out = {
            "PEER_URL": self.peer_url,
            "PEER_KEY_ID": self.peer_key_id,
            "PEER_NODE_KEY_ID": self.peer_node_key_id,
            "PEER_OWNER_KEY_ID": self.peer_owner_key_id,
            "PEER_CONTACT_CODE": self.peer_contact_code,
            "LOCAL_OWNER_KEY_ID": self.local_owner_key_id,
            "LOCAL_NODE_KEY_ID": self.local_node_key_id,
            "ROOM_ID": self.room_id,
            "MESSAGE_TEXT": self.message_text if self.message_arrived else "",
            "MESSAGE_ATTESTATION_ID": self.message_attestation_id if self.message_arrived else "",
        }
        return {k: v for k, v in out.items() if v}


Log = Callable[[str], None]


def _say(msg: str) -> None:
    print(f"  [two-node] {msg}", flush=True)


def login(url: str, username: str, password: str) -> str:
    status, body = http("POST", f"{url}/v1/auth/login", body={"username": username, "password": password})
    token = body.get("access_token") if isinstance(body, dict) else None
    if not token:
        raise FixtureUnavailable(f"POST {url}/v1/auth/login answered {status}: {str(body)[:200]}")
    return token


def announce(party: Party, log: Log = _say) -> None:
    status, body = http("POST", f"{party.url}/v1/federation/announce", party.token, {})
    party.announce = {"status": status, **(body if isinstance(body, dict) else {"detail": str(body)[:200]})}
    roles = [f"{r.get('role')}:{r.get('key_id')}" for r in party.announce.get("bundle") or []]
    log(f"announce {party.name}: {status} discoverable={party.announce.get('federation_discoverable')} "
        f"bundle={roles}")


def self_record(party: Party) -> None:
    status, rec = http("GET", f"{party.url}/v1/federation/self-key-record")
    if not _ok(status) or not isinstance(rec, dict) or "record" not in rec:
        raise FixtureUnavailable(f"{party.name}: GET /v1/federation/self-key-record answered {status}: {str(rec)[:200]}")
    party.record, party.node_key_id = rec, rec["record"]["key_id"]


def owner_of(party: Party) -> None:
    """The owner's fed-ID for a node the CLIENT claimed (the fixture's own claim
    already knows it): the announce bundle's `owner_key` row, else a console
    ROOT's `root:<fed-ID>` username from `/v1/auth/me`."""
    if party.owner_key_id:
        return
    # The announce names the bundle a peer walks: owner_key, node_key,
    # owner_binding. It is the node's own statement of who owns it.
    for row in party.announce.get("bundle") or []:
        if row.get("role") == "owner_key" and row.get("key_id"):
            party.owner_key_id = row["key_id"]
            return
    _, me = http("GET", f"{party.url}/v1/auth/me", party.token)
    # A console-claimed ROOT is `root:<fed-ID>` (wa id `wa-root-<fed-ID>`).
    name = str(me.get("username") or "") if isinstance(me, dict) else ""
    if name.startswith("root:"):
        party.owner_key_id = name.removeprefix("root:")


def peer(a: Party, b: Party, log: Log = _say) -> None:
    status, body = http("POST", f"{a.url}/v1/federation/peering", a.token, {
        "peer_key_id": b.node_key_id, "peer_key_record": b.record,
        "attestation_prefixes": PEER_PREFIXES})
    if not _ok(status):
        raise FixtureUnavailable(f"peering {a.name}->{b.name} answered {status}: {str(body)[:300]}")
    log(f"peering {a.name}->{b.name}: fresh={body.get('freshly_emitted')}")


def knows(host: Party, key_id: str) -> bool:
    status, _ = http("GET", f"{host.url}/v1/federation/peers/{key_id}", host.token)
    return status == 200


def _reachable(body: Any) -> int:
    try:
        return int((body or {}).get("reachable_nodes") or 0)
    except (TypeError, ValueError, AttributeError):
        return 0


def add_contact(host: Party, guest: Party, wait: float, notes: List[str],
                code: str = "", log: Log = _say, reachable_wait: float = 120.0) -> Tuple[str, str]:
    """(key the contact is held under, how). The person if their key crossed;
    their contact code if the node serves one; else their NODE, said so.

    Then the BINDING: the add answers `reachable_nodes`, and 0 means the
    guest's owner→node binding is not yet held on `host` at federation scope
    (module doc, TOPOLOGY §2.5). The POST is idempotent (a standing grant is
    `freshly_emitted: false`), so it is re-asked every 5 s for up to
    `reachable_wait` until the count is >= 1; what was waited on, and for how
    long, goes in `notes`. Unreachable is still not a refusal: the contact
    stands, and the note says the room may key without a grant (#699)."""
    deadline = time.monotonic() + wait
    while guest.owner_key_id and not knows(host, guest.owner_key_id) and time.monotonic() < deadline:
        time.sleep(5.0)
    tries: List[Tuple[str, str]] = []
    if guest.owner_key_id:
        tries.append((guest.owner_key_id, "owner"))
    if code:
        tries.append((code, "code"))
    tries.append((guest.node_key_id, "node"))
    for key, via in tries:
        status, body = http("POST", f"{host.url}/v1/contacts", host.token, {"key_id": key})
        if _ok(status) and isinstance(body, dict):
            log(f"contact {host.name}->{guest.name} via {via}: {status} key={body.get('key_id')} "
                f"reachable_nodes={body.get('reachable_nodes')} prefixes={body.get('consent_prefixes')}")
            held = str(body.get("key_id") or key)
            reachable = _reachable(body)
            if reachable == 0 and reachable_wait > 0:
                started, asks = time.monotonic(), 1
                while reachable == 0 and time.monotonic() - started < reachable_wait:
                    time.sleep(5.0)
                    again, body = http("POST", f"{host.url}/v1/contacts", host.token, {"key_id": key})
                    asks += 1
                    reachable = _reachable(body) if _ok(again) and isinstance(body, dict) else 0
                waited = time.monotonic() - started
                if reachable >= 1:
                    notes.append(
                        f"waited {waited:.0f}s (POST /v1/contacts asked {asks}x) for {guest.name}'s "
                        f"owner\u2192node binding to be held on {host.name}: reachable_nodes={reachable} "
                        f"(FSD/TOPOLOGY.md \u00a72.5 `reachable`; a room opened before it keys with "
                        f"bodies `not_granted`, CIRISServer#699)")
                else:
                    notes.append(
                        f"{guest.name} is still reachable_nodes=0 on {host.name} after {waited:.0f}s "
                        f"({asks} asks) \u2014 their owner\u2192node binding never crossed; the pair room "
                        f"opened next may key with bodies `not_granted` (CIRISServer#699)")
                log(notes[-1])
            return held, via
        reason = body.get("reason_id") if isinstance(body, dict) else body
        notes.append(f"contact {host.name}->{guest.name} via {via} refused: {status} {str(reason)[:120]}")
        log(notes[-1])
    raise FixtureUnavailable(f"{host.name} could not add {guest.name} as a contact: {notes[-3:]}")


def room_state(party: Party, cid: str) -> Dict[str, Any]:
    status, body = http("GET", f"{party.url}/v1/chat/{cid}/messages", party.token)
    if not isinstance(body, dict):
        return {"status": status, "ready": False, "messages": []}
    msgs = body.get("messages") or []
    ready = body.get("ready")
    if ready is None and status == 200:
        ready = not any(m.get("kind") == "system" for m in msgs)
    notes = [m.get("message_id") for m in msgs if m.get("kind") == "system"]
    return {"status": status, "ready": bool(ready), "messages": msgs,
            "state": notes[0] if notes else None, "reason_id": body.get("reason_id")}


def open_room(party: Party, with_key: str) -> str:
    status, body = http("POST", f"{party.url}/v1/chat", party.token, {"key_id": with_key})
    cid = body.get("community_id") if isinstance(body, dict) else None
    if not cid:
        raise FixtureUnavailable(f"POST /v1/chat on {party.name} answered {status}: {str(body)[:200]}")
    return cid


def seed(local: Party, remote: Party, *, message: str = DEFAULT_MESSAGE,
         owner_wait: float = 90.0, ready_wait: float = 150.0, arrive_wait: float = 120.0,
         reachable_wait: float = 120.0, log: Log = _say) -> FixtureValues:
    """The chat scenario between the leg's node (`local`) and the peer (`remote`).

    Both announced; peered both ways; each owner adds the other and WAITS for
    the other to be reachable from it (TOPOLOGY §2.5 `reachable`, #699 — see
    the module doc); both open the pair room; the PEER speaks once the room is
    keyed; the arrival on the leg's node is waited for and recorded."""
    v = FixtureValues(peer_url=remote.url, message_text=message)
    for p in (remote, local):
        announce(p, log)
        self_record(p)
    owner_of(local)
    v.local_owner_key_id, v.local_node_key_id = local.owner_key_id, local.node_key_id
    v.peer_owner_key_id, v.peer_node_key_id = remote.owner_key_id, remote.node_key_id
    log(f"local node={local.node_key_id} owner={local.owner_key_id or '?'}; "
        f"peer node={remote.node_key_id} owner={remote.owner_key_id}")
    peer(local, remote, log)
    peer(remote, local, log)

    status, body = http("GET", f"{remote.url}/v1/self/contact-code", remote.token)
    if _ok(status) and isinstance(body, dict):
        v.peer_contact_code = str(body.get("code") or body.get("contact_code") or "")
    else:
        v.notes.append(f"the peer serves no contact code (GET /v1/self/contact-code: {status}) — "
                       f"it ships in ciris-server 0.5.218 (CIRISServer#673)")

    v.peer_key_id, v.contact_via = add_contact(local, remote, owner_wait, v.notes, v.peer_contact_code, log,
                                               reachable_wait=reachable_wait)
    back_key, back_via = add_contact(remote, local, owner_wait, v.notes, "", log,
                                     reachable_wait=reachable_wait)
    if v.contact_via != "owner":
        v.notes.append(f"the peer's owner key never reached the leg's node within {owner_wait:.0f}s; "
                       f"the contact is held under the peer NODE ({v.peer_key_id})")

    # Both sides open the pair room. It is derived, not invited: each node
    # computes the same id from the two keys (pair_community_key_id).
    cid_remote = open_room(remote, back_key)
    v.room_id = open_room(local, v.peer_key_id)
    if cid_remote != v.room_id:
        v.notes.append(f"the two nodes derived different rooms: peer {cid_remote}, local {v.room_id}")
        log(v.notes[-1])
        return v
    log(f"room {v.room_id}")

    deadline = time.monotonic() + ready_wait
    hs = room_state(remote, cid_remote)
    while not hs["ready"] and time.monotonic() < deadline:
        time.sleep(10.0)
        hs = room_state(remote, cid_remote)
    if not hs["ready"]:
        v.notes.append(f"the room never keyed on the peer within {ready_wait:.0f}s "
                       f"(state={hs['state']} reason={hs['reason_id']}) — the other side's "
                       f"KeyPackage did not cross")
        log(v.notes[-1])
        return v

    status, body = http("POST", f"{remote.url}/v1/chat/{cid_remote}/messages", remote.token, {"body": message})
    v.message_attestation_id = str(body.get("attestation_id") or "") if isinstance(body, dict) else ""
    if not v.message_attestation_id:
        v.notes.append(f"the peer's send answered {status}: {str(body)[:200]}")
        log(v.notes[-1])
        return v
    log(f"peer sent {v.message_attestation_id}")

    deadline = time.monotonic() + arrive_wait
    while time.monotonic() < deadline:
        for m in room_state(local, v.room_id)["messages"]:
            if m.get("attestation_id") == v.message_attestation_id and m.get("body"):
                v.message_arrived = True
                log("the message ARRIVED on the leg's node")
                return v
        time.sleep(10.0)
    v.notes.append(f"the peer's message {v.message_attestation_id} did not arrive on the leg's node "
                   f"within {arrive_wait:.0f}s")
    log(v.notes[-1])
    return v


# ── the fixture the runner holds ────────────────────────────────────────────


class TwoNodeFixture:
    """Stand the peer up beside `node_url`, seed, hand back `${NAME}` values.

    A context manager: `__exit__` kills the peer and deletes its home whatever
    happened inside, which is how a failed flow still cleans up."""

    def __init__(self, binary: Path, work: Path, *, node_url: str = DEFAULT_NODE_URL,
                 username: str = "qaadmin", password: str = "QaAdmin!2345",
                 local_token: str = "", port: int = PEER_PORT, key_id: str = "",
                 message: str = "", keep_home: bool = False, **waits: float) -> None:
        self.work = Path(work)
        self.node_url = node_url.rstrip("/")
        self.username, self.password, self.local_token = username, password, local_token
        self.message = message or f"{DEFAULT_MESSAGE} ({uuid.uuid4().hex[:6]})"
        self.keep_home, self.waits = keep_home, waits
        self.spec = PeerSpec(binary=Path(binary), home=self.work / "home",
                             key_id=key_id or unique_key_id(), port=port,
                             bootstrap_peers=[transport_of(self.node_url)])
        self.node: Optional[PeerNode] = None
        self.values: Optional[FixtureValues] = None

    def up(self) -> Dict[str, str]:
        self.spec.binary = resolve_binary(self.spec.binary)
        self.node = PeerNode(self.spec, self.work)
        # CLEAN UP EVEN WHEN KILLED. The runner's `finally` covers a failed
        # flow; a job timeout or a cancelled step sends SIGTERM, which skips
        # `finally` unless it is turned into an exit. The peer runs in its own
        # session, so nothing else would reap it. (The workflow also runs
        # `two_node down` under `if: always()` for a SIGKILL.)
        atexit.register(self.down)
        if threading.current_thread() is threading.main_thread() and hasattr(signal, "SIGTERM"):
            signal.signal(signal.SIGTERM, lambda *_: sys.exit(143))
        _say(f"peer {self.spec.key_id} on {self.spec.listen_addr} (read {self.spec.read_url}), "
             f"home {self.spec.home}, dialling {self.spec.bootstrap_peers}")
        self.node.start()
        remote = self.node.claim()
        token = self.local_token or login(self.node_url, self.username, self.password)
        local = Party("local", self.node_url, token)
        self.values = seed(local, remote, message=self.message, **self.waits)
        (self.work / "values.json").write_text(json.dumps(asdict(self.values), indent=2), encoding="utf-8")
        return self.values.as_vars()

    def detach(self) -> None:
        """Hand the running peer to a later `two_node down`: drop the exit and
        SIGTERM cleanup `up` installed, so a standalone `up` that returns does
        not kill the peer it was asked to leave running (Codex, PR #130)."""
        atexit.unregister(self.down)
        if threading.current_thread() is threading.main_thread() and hasattr(signal, "SIGTERM"):
            signal.signal(signal.SIGTERM, signal.SIG_DFL)
        self.node = None

    def down(self) -> None:
        if self.node is not None:
            self.node.stop(keep_home=self.keep_home)
            self.node = None

    def __enter__(self) -> "TwoNodeFixture":
        return self

    def __exit__(self, *exc: Any) -> None:
        self.down()


def main(argv: Optional[List[str]] = None) -> int:
    utf8_console()
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    u = sub.add_parser("up", help="start, claim, peer and seed; leave the peer running")
    u.add_argument("--binary", type=Path, required=True)
    u.add_argument("--work", type=Path, required=True, help="the peer's home and log live here")
    u.add_argument("--node-url", default=DEFAULT_NODE_URL)
    u.add_argument("--username", default="qaadmin")
    u.add_argument("--password", default="QaAdmin!2345")
    u.add_argument("--token", default="", help="an owner session for the leg's node, instead of logging in")
    u.add_argument("--port", type=int, default=PEER_PORT)
    u.add_argument("--values", type=Path, help="write the ${NAME} values here as JSON")
    d = sub.add_parser("down", help="kill the peer and delete its home")
    d.add_argument("--work", type=Path, required=True)
    args = ap.parse_args(argv)

    if args.cmd == "down":
        print(down(args.work))
        return 0
    fx = TwoNodeFixture(args.binary, args.work, node_url=args.node_url, username=args.username,
                        password=args.password, local_token=args.token, port=args.port)
    try:
        values = fx.up()
    except FixtureUnavailable as e:
        print(f"::error::two-node fixture: {e}", file=sys.stderr)
        fx.down()
        return 1
    if args.values:
        args.values.write_text(json.dumps(values, indent=2), encoding="utf-8")
    print(json.dumps({"vars": values, "notes": fx.values.notes if fx.values else []}, indent=2))
    # `up` means "leave the peer running": the in-process runner keeps its
    # cleanup, the standalone command hands the peer to `two_node down`.
    fx.detach()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
